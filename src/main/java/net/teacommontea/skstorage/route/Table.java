package net.teacommontea.skstorage.route;

import ch.njol.skript.Skript;
import ch.njol.skript.aliases.ItemType;
import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.registrations.Classes;
import net.teacommontea.skstorage.util.Log;
import net.teacommontea.skstorage.SkStoragePlugin;
import net.teacommontea.skstorage.util.BukkitItemCodec;
import net.teacommontea.skstorage.util.SqliteSetup;
import net.teacommontea.skstorage.util.StringDict;
import net.teacommontea.skstorage.util.UuidExtractor;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public final class Table {

    private static final String UPSERT =
        "INSERT INTO variables (name, type_id, value) VALUES (?, ?, ?) " +
        "ON CONFLICT(name) DO UPDATE SET type_id=excluded.type_id, value=excluded.value";
    private static final String DELETE = "DELETE FROM variables WHERE name = ?";
    private static final String SCHEMA =
        "CREATE TABLE IF NOT EXISTS variables (" +
        "  name    TEXT PRIMARY KEY," +
        "  type_id INTEGER NOT NULL," +
        "  value   BLOB" +
        ") WITHOUT ROWID";

    private final String name;
    private final boolean splitFileByUuid;
    private final boolean permanent;
    private final int index;
    private final File location;
    private final int shardChars;
    private final SqliteSetup.Options sqliteOptions;

    private final Object lock = new Object();
    private final java.util.concurrent.atomic.AtomicLong writesTotal =
        new java.util.concurrent.atomic.AtomicLong();

    @Nullable private Handle single;

    private static final int MAX_OPEN_HANDLES = 256;
    private final LinkedHashMap<String, Handle> handles =
        new LinkedHashMap<>(64, 0.75f, true);

    public Table(String name, boolean splitFileByUuid, boolean permanent, int index,
                 File location, int shardChars, SqliteSetup.Options sqliteOptions) {
        this.name = name;
        this.splitFileByUuid = splitFileByUuid;
        this.permanent = permanent;
        this.index = index;
        this.location = location;
        this.shardChars = shardChars;
        this.sqliteOptions = sqliteOptions;
    }

    public String name() { return name; }
    public boolean isSplitFileByUuid() { return splitFileByUuid; }
    public boolean isPermanent() { return permanent; }
    public int index() { return index; }

    public void open() throws SQLException {
        synchronized (lock) {
            if (splitFileByUuid) {
                if (!location.exists() && !location.mkdirs()) {
                    throw new SQLException("cannot create table directory: " + location);
                }
            } else {
                File parent = location.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                single = openHandle(location);
            }
        }
    }

    public void close() {
        synchronized (lock) {
            if (single != null) { closeHandle(single); single = null; }
            for (Handle h : handles.values()) closeHandle(h);
            handles.clear();
        }
    }

    public void flush() {
        synchronized (lock) {
            if (single != null) drain(single);
            for (Handle h : handles.values()) drain(h);
        }
    }

    public boolean save(String name, @Nullable String type, byte @Nullable [] value) {
        BukkitItemCodec.Result codec = BukkitItemCodec.intercept(name, type, value);
        synchronized (lock) {
            try {
                Handle h = handleFor(name);
                if (h == null) return false;
                h.pending.put(name, new Pending(codec.type, codec.value));
                writesTotal.incrementAndGet();
                return true;
            } catch (SQLException e) {
                Log.hard("table " + this.name + " save failed for " + name + ": " + e.getMessage());
                return false;
            }
        }
    }

    public long writesTotal() { return writesTotal.get(); }

    public int openHandles() {
        synchronized (lock) {
            return splitFileByUuid ? handles.size() : (single != null ? 1 : 0);
        }
    }

    public long diskBytes() {
        long total = 0;
        if (splitFileByUuid) {
            for (File f : shardFiles()) {
                total += sizeWithSidecars(f);
            }
        } else {
            total += sizeWithSidecars(location);
        }
        return total;
    }

    public long rowCount() {
        synchronized (lock) {
            long total = 0;
            try {
                if (splitFileByUuid) {
                    for (File f : shardFiles()) {
                        try (Connection c = SqliteSetup.open(f.getAbsolutePath(), sqliteOptions)) {
                            total += countRows(c);
                        }
                    }
                } else if (single != null && single.conn != null) {
                    total += countRows(single.conn);
                }
            } catch (SQLException e) {
                return -1;
            }
            return total;
        }
    }

    private static long countRows(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet r = st.executeQuery("SELECT COUNT(*) FROM variables")) {
            return r.next() ? r.getLong(1) : 0;
        }
    }

    private static long sizeWithSidecars(File db) {
        long total = db.exists() ? db.length() : 0;
        File shm = new File(db.getAbsolutePath() + "-shm");
        File wal = new File(db.getAbsolutePath() + "-wal");
        if (shm.exists()) total += shm.length();
        if (wal.exists()) total += wal.length();
        return total;
    }

    public int reloadUuid(String uuid, BiConsumer<String, Object> feed, boolean ignoreInvalid) {
        if (!splitFileByUuid) return -1;
        synchronized (lock) {
            Handle open = handles.remove(uuid);
            if (open != null) closeHandle(open);
            File f = shardPath(uuid);
            if (!f.exists()) return -1;
            int[] count = {0};
            try (Handle h = openHandle(f)) {
                readRows(h, (n, v) -> { feed.accept(n, v); count[0]++; }, ignoreInvalid);
            } catch (SQLException e) {
                Log.soft("flatline reload failed for " + uuid + ": " + e.getMessage());
                return -1;
            }
            return count[0];
        }
    }

    public void loadAll(BiConsumer<String, Object> feed, boolean ignoreInvalid) {
        synchronized (lock) {
            if (splitFileByUuid) {
                List<File> files = shardFiles();
                for (File f : files) {
                    try (Handle h = openHandle(f)) {
                        readRows(h, feed, ignoreInvalid);
                    } catch (SQLException e) {
                        if (!ignoreInvalid) {
                            throw new IllegalStateException("[SkStorage] table " + name +
                                " failed to load shard " + f.getName(), e);
                        }
                        Log.soft("table " + name + " skipped unreadable shard "
                            + f.getName() + ": " + e.getMessage());
                    }
                }
            } else {
                if (single == null) return;
                try {
                    readRows(single, feed, ignoreInvalid);
                } catch (SQLException e) {
                    if (!ignoreInvalid) {
                        throw new IllegalStateException("[SkStorage] table " + name + " failed to load", e);
                    }
                    Log.soft("table " + name + " load error: " + e.getMessage());
                }
            }
        }
    }

    public record RawRow(String name, @Nullable String type, byte @Nullable [] value) {}

    public boolean has(String name) {
        synchronized (lock) {
            try {
                if (splitFileByUuid) {
                    String uuid = UuidExtractor.firstUuid(name);
                    if (uuid == null) return false;
                    File f = shardPath(uuid);
                    if (!f.exists()) return false;
                    try (Connection c = SqliteSetup.open(f.getAbsolutePath(), sqliteOptions)) {
                        return rowExists(c, name);
                    }
                } else if (single != null && single.conn != null) {
                    return rowExists(single.conn, name);
                }
            } catch (SQLException ignored) {}
            return false;
        }
    }

    private static boolean rowExists(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM variables WHERE name = ? LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet r = ps.executeQuery()) {
                return r.next();
            }
        }
    }

    public List<RawRow> readRowsContaining(String needle) {
        synchronized (lock) {
            List<RawRow> out = new ArrayList<>();
            try {
                if (splitFileByUuid) {

                    File f = shardPath(needle);
                    if (f.exists()) {
                        try (Connection c = SqliteSetup.open(f.getAbsolutePath(), sqliteOptions);
                             StringDict td = new StringDict(c, "type_dict")) {
                            readRaw(c, td, needle, out);
                        }
                    }
                } else if (single != null && single.conn != null && single.typeDict != null) {
                    readRaw(single.conn, single.typeDict, needle, out);
                }
            } catch (SQLException e) {
                Log.soft("table " + name + " readRowsContaining failed: " + e.getMessage());
            }
            return out;
        }
    }

    private static void readRaw(Connection c, StringDict td, String needle, List<RawRow> out) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                 "SELECT name, type_id, value FROM variables WHERE name LIKE ?")) {
            ps.setString(1, "%" + needle + "%");
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    String n = r.getString(1);
                    String type = td.get(r.getInt(2));
                    byte[] value = r.getBytes(3);
                    if (n != null) out.add(new RawRow(n, type, value));
                }
            }
        }
    }

    public int purgeUuid(String uuid) {
        synchronized (lock) {
            if (splitFileByUuid) {
                Handle open = handles.remove(uuid);
                if (open != null) closeHandle(open);
                File f = shardPath(uuid);
                int dropped = deleteDbFile(f) ? 1 : 0;
                return dropped;
            }

            return purgeByGlob("*" + uuid + "*");
        }
    }

    public int purgeByGlob(String glob) {
        synchronized (lock) {
            if (splitFileByUuid || single == null) return 0;
            drain(single);
            try (PreparedStatement ps = single.conn.prepareStatement(
                     "DELETE FROM variables WHERE name GLOB ?")) {
                ps.setString(1, glob);
                int n = ps.executeUpdate();
                single.conn.commit();
                return n;
            } catch (SQLException e) {
                Log.hard("table " + name + " purge failed for " + glob + ": " + e.getMessage());
                return 0;
            }
        }
    }

    public void clear() {
        synchronized (lock) {
            if (splitFileByUuid) {
                for (Handle h : handles.values()) closeHandle(h);
                handles.clear();
                for (File f : shardFiles()) deleteDbFile(f);
            } else if (single != null) {
                drain(single);
                try (Statement st = single.conn.createStatement()) {
                    st.execute("DELETE FROM variables");
                    single.conn.commit();
                } catch (SQLException e) {
                    Log.hard("table " + name + " clear failed: " + e.getMessage());
                }
            }
        }
    }

    @Nullable
    private Handle handleFor(String name) throws SQLException {
        if (!splitFileByUuid) return single;
        String uuid = UuidExtractor.firstUuid(name);
        if (uuid == null) {

            Log.hard("table " + this.name + " (split_file_by_uuid) got non-UUID name: " + name);
            return null;
        }
        Handle h = handles.get(uuid);
        if (h != null && h.isOpen()) return h;
        while (handles.size() >= MAX_OPEN_HANDLES) {
            var oldest = handles.entrySet().iterator().next();
            closeHandle(oldest.getValue());
            handles.remove(oldest.getKey());
        }
        h = openHandle(shardPath(uuid));
        handles.put(uuid, h);
        return h;
    }

    private Handle openHandle(File dbFile) throws SQLException {
        Connection conn = SqliteSetup.open(dbFile.getAbsolutePath(), sqliteOptions);
        try (Statement st = conn.createStatement()) {
            st.execute(SCHEMA);
        }
        Handle h = new Handle();
        h.conn = conn;
        h.typeDict = new StringDict(conn, "type_dict");
        h.upsert = conn.prepareStatement(UPSERT);
        h.delete = conn.prepareStatement(DELETE);
        conn.commit();
        return h;
    }

    private void closeHandle(Handle h) {
        drain(h);
        try { if (h.upsert != null) h.upsert.close(); } catch (SQLException ignored) {}
        try { if (h.delete != null) h.delete.close(); } catch (SQLException ignored) {}
        if (h.typeDict != null) h.typeDict.close();
        SqliteSetup.checkpointTruncate(h.conn);
        try { if (h.conn != null) h.conn.close(); } catch (SQLException ignored) {}
    }

    private void drain(Handle h) {
        if (h.conn == null || h.pending.isEmpty()) return;
        try {
            for (Map.Entry<String, Pending> e : h.pending.entrySet()) {
                Pending op = e.getValue();
                if (op.isDelete()) {
                    h.delete.setString(1, e.getKey());
                    h.delete.addBatch();
                } else {
                    int typeId = h.typeDict.getOrInsert(op.type);
                    h.upsert.setString(1, e.getKey());
                    h.upsert.setInt(2, typeId);
                    h.upsert.setBytes(3, op.value);
                    h.upsert.addBatch();
                }
            }
            h.delete.executeBatch();
            h.upsert.executeBatch();
            h.conn.commit();
            h.pending.clear();
        } catch (SQLException e) {
            h.pending.clear();
            Log.hard("table " + name + " flush failed: " + e.getMessage());
        }
    }

    private void readRows(Handle h, BiConsumer<String, Object> feed, boolean ignoreInvalid) throws SQLException {
        List<String> dead = new ArrayList<>();
        try (PreparedStatement ps = h.conn.prepareStatement("SELECT name, type_id, value FROM variables");
             ResultSet r = ps.executeQuery()) {
            while (r.next()) {
                String varName = r.getString(1);
                int typeId = r.getInt(2);
                byte[] value = r.getBytes(3);
                if (varName == null) continue;
                String typeName = h.typeDict.get(typeId);
                if (typeName == null) continue;
                if (value == null) { feed.accept(varName, null); continue; }
                if (BukkitItemCodec.TYPE_MARKER.equals(typeName)) {
                    ItemStack stack = BukkitItemCodec.decode(value);
                    ItemType it = BukkitItemCodec.toItemType(stack);
                    if (it != null) feed.accept(varName, it);
                    continue;
                }
                ClassInfo<?> ci = Classes.getClassInfoNoError(typeName);
                if (ci == null || ci.getSerializer() == null) { dead.add(varName); continue; }
                Object obj;
                try {
                    obj = Classes.deserialize(ci, value);
                } catch (Throwable t) {
                    dead.add(varName);
                    continue;
                }
                if (obj != null) feed.accept(varName, obj);
            }
        }
        if (!dead.isEmpty() && !ignoreInvalid) {

            try (PreparedStatement del = h.conn.prepareStatement(DELETE)) {
                for (String n : dead) { del.setString(1, n); del.addBatch(); }
                del.executeBatch();
                h.conn.commit();
            } catch (SQLException ignored) {}
        }
    }

    private List<File> shardFiles() {
        List<File> out = new ArrayList<>();
        if (!location.exists()) return out;
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(location.toPath())) {
            s.filter(p -> {
                String n = p.toString();
                return n.endsWith(".db") && !n.endsWith("-shm.db") && !n.endsWith("-wal.db");
            }).forEach(p -> out.add(p.toFile()));
        } catch (java.io.IOException e) {
            Log.soft("table " + name + " could not walk shards: " + e.getMessage());
        }
        return out;
    }

    private File shardPath(String uuid) {
        String shard = uuid.substring(0, Math.min(shardChars, uuid.length()));
        File dir = new File(location, shard);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, uuid + ".db");
    }

    private boolean deleteDbFile(File f) {
        boolean deleted = f.delete();
        new File(f.getAbsolutePath() + "-shm").delete();
        new File(f.getAbsolutePath() + "-wal").delete();
        return deleted;
    }

    private static final class Handle implements AutoCloseable {
        @Nullable Connection conn;
        @Nullable StringDict typeDict;
        @Nullable PreparedStatement upsert;
        @Nullable PreparedStatement delete;
        final LinkedHashMap<String, Pending> pending = new LinkedHashMap<>();

        boolean isOpen() {
            try {
                return conn != null && !conn.isClosed();
            } catch (SQLException e) {
                return false;
            }
        }

        @Override
        public void close() {

            try { if (upsert != null) upsert.close(); } catch (SQLException ignored) {}
            try { if (delete != null) delete.close(); } catch (SQLException ignored) {}
            if (typeDict != null) typeDict.close();
            SqliteSetup.checkpointTruncate(conn);
            try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
        }
    }

    private static final class Pending {
        @Nullable final String type;
        final byte @Nullable [] value;

        Pending(@Nullable String type, byte @Nullable [] value) {
            this.type = type;
            this.value = value;
        }

        boolean isDelete() { return type == null || value == null; }
    }
}
