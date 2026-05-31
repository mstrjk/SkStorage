package dev.teacommontea.skstorage.scope;

import ch.njol.skript.Skript;
import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.config.SectionNode;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.aliases.ItemType;
import dev.teacommontea.skstorage.SkStoragePlugin;
import dev.teacommontea.skstorage.util.BukkitItemCodec;
import dev.teacommontea.skstorage.util.SqliteSetup;
import dev.teacommontea.skstorage.util.StringDict;
import dev.teacommontea.skstorage.util.UuidExtractor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class PlayerScope extends SkStorageBase implements Listener {

    private static final Pattern UUID_NAME_PATTERN = Pattern.compile(
        ".*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*");

    private static final String UPSERT =
        "INSERT INTO variables (name, type_id, value) VALUES (?, ?, ?) " +
        "ON CONFLICT(name) DO UPDATE SET type_id=excluded.type_id, value=excluded.value";
    private static final String DELETE = "DELETE FROM variables WHERE name = ?";

    private File rootDir;
    private int shardChars = 2;

    private static final class FileHandle {
        Connection conn;
        StringDict typeDict;
        PreparedStatement upsert;
        PreparedStatement delete;
        final java.util.LinkedHashMap<String, PendingOp> pending = new java.util.LinkedHashMap<>();
    }

    private static final int MAX_OPEN_HANDLES = 256;

    private final java.util.LinkedHashMap<String, FileHandle> handles =
        new java.util.LinkedHashMap<>(64, 0.75f, true);

    public PlayerScope(String type) {
        super(type);
    }

    @Override
    protected Pattern scopePattern() {
        return UUID_NAME_PATTERN;
    }

    @Override
    protected boolean requiresFile() { return false; }

    @Override
    protected File getFile(String fileName) { return new File(fileName); }

    @Override
    protected boolean load_i(SectionNode node) {
        String dir = node.getValue("directory");
        if (dir == null) {
            Skript.error("[SkStorage] skstorage-player storage requires 'directory' in databases config");
            return false;
        }
        rootDir = new File(dir).getAbsoluteFile();
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            Skript.error("[SkStorage] Cannot create player scope directory: " + rootDir);
            return false;
        }
        shardChars = SkStoragePlugin.get().getShardChars();
        try {
            installScopePattern();
            SkStoragePlugin.get().registerScope(this);
            SkStoragePlugin.get().getServer().getPluginManager().registerEvents(this, SkStoragePlugin.get());
            if (SkStoragePlugin.get().isPreloadPlayers()) {
                synchronousPreloadAll();
            } else {
                scheduleBackgroundLoad();
            }
            return true;
        } catch (Exception e) {
            Skript.error("[SkStorage] player load failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void synchronousPreloadAll() throws java.io.IOException {
        if (!rootDir.exists()) return;

        java.util.List<File> queue = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(rootDir.toPath())) {
            stream.filter(p -> p.toString().endsWith(".db"))
                  .filter(p -> !p.toString().endsWith("-shm.db") && !p.toString().endsWith("-wal.db"))
                  .forEach(p -> queue.add(p.toFile()));
        }
        if (queue.isEmpty()) {
            SkStoragePlugin.get().getLogger().info(
                "Synchronous player preload: no files to load.");
            return;
        }

        SkStoragePlugin.get().getLogger().info(
            "Synchronous player preload: loading " + queue.size() +
            " files during Skript load phase (server boot is BLOCKED until complete)...");
        long t0 = System.currentTimeMillis();
        int filesLoaded = 0, filesSkipped = 0, errors = 0, varsLoaded = 0;

        for (File f : queue) {
            if (tryDiscardIfSmall(f)) { filesSkipped++; continue; }
            try (Connection conn = SqliteSetup.open(f.getAbsolutePath());
                 StringDict typeDict = new StringDict(conn, "type_dict");
                 PreparedStatement ps = conn.prepareStatement("SELECT name, type_id, value FROM variables");
                 ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    String name = r.getString(1);
                    int typeId = r.getInt(2);
                    byte[] value = r.getBytes(3);
                    if (name == null) continue;
                    String typeName = typeDict.get(typeId);
                    if (typeName == null) continue;
                    if (value == null) { feedLoaded(name, null); varsLoaded++; continue; }
                    if (BukkitItemCodec.TYPE_MARKER.equals(typeName)) {
                        ItemStack stack = BukkitItemCodec.decode(value);
                        ItemType it = BukkitItemCodec.toItemType(stack);
                        if (it != null) { feedLoaded(name, it); varsLoaded++; }
                        continue;
                    }
                    ClassInfo<?> ci = Classes.getClassInfoNoError(typeName);
                    if (ci == null || ci.getSerializer() == null) continue;
                    Object obj;
                    try {
                        obj = Classes.deserialize(ci, value);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (obj != null) { feedLoaded(name, obj); varsLoaded++; }
                }
                SqliteSetup.checkpointTruncate(conn);
                filesLoaded++;
            } catch (SQLException e) {
                errors++;
                if (errors <= 3) {
                    Skript.warning("[SkStorage] preload failed for " + f.getName() + ": " + e.getMessage());
                }
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        SkStoragePlugin.get().getLogger().info(String.format(
            "Synchronous player preload complete: %d files, %d vars, %d skipped, %d errors, %dms",
            filesLoaded, varsLoaded, filesSkipped, errors, elapsed));
    }

    private void scheduleBackgroundLoad() throws java.io.IOException {
        int rate = SkStoragePlugin.get().getBackgroundLoadFilesPerTick();
        if (rate <= 0) {
            SkStoragePlugin.get().getLogger().info(
                "Background player-load disabled (background_load_files_per_tick=0). Offline-player vars will only load on join.");
            return;
        }
        if (!rootDir.exists()) return;

        java.util.List<File> queue = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(rootDir.toPath())) {
            stream.filter(p -> p.toString().endsWith(".db"))
                  .filter(p -> !p.toString().endsWith("-shm.db") && !p.toString().endsWith("-wal.db"))
                  .forEach(p -> queue.add(p.toFile()));
        }
        if (queue.isEmpty()) return;

        queue.sort((a, b) -> Long.compare(b.length(), a.length()));

        SkStoragePlugin.get().getLogger().info(
            "Background player-load (lite mode): queued " + queue.size() + " files at " + rate + "/tick (~" +
            (queue.size() / Math.max(1, rate * 20)) + "s estimated). " +
            "Loaded vars are direct-lookup only; set player.preload_players=true for full integration.");

        final java.util.Iterator<File> iter = queue.iterator();
        new org.bukkit.scheduler.BukkitRunnable() {
            int loaded = 0;
            int errors = 0;
            @Override
            public void run() {
                for (int i = 0; i < rate; i++) {
                    if (!iter.hasNext()) {
                        SkStoragePlugin.get().getLogger().info(
                            "Background player-load complete: " + loaded + " files loaded, " + errors + " errors");
                        cancel();
                        return;
                    }
                    File f = iter.next();
                    try {
                        backgroundLoadOne(f);
                        loaded++;
                    } catch (Throwable t) {
                        errors++;
                        if (errors <= 3) {
                            SkStoragePlugin.get().getLogger().warning(
                                "Background load failed for " + f.getName() + ": " + t.getMessage());
                        }
                    }
                }
            }
        }.runTaskTimerAsynchronously(SkStoragePlugin.get(), 40L, 1L);
    }

    private void backgroundLoadOne(File dbFile) throws SQLException {
        String fileName = dbFile.getName();
        if (!fileName.endsWith(".db")) return;
        String uuid = fileName.substring(0, fileName.length() - 3);

        synchronized (connectionLock) {
            if (handles.containsKey(uuid)) return;
        }

        if (tryDiscardIfSmall(dbFile)) {
            return;
        }

        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        try (Connection conn = SqliteSetup.open(dbFile.getAbsolutePath());
             StringDict typeDict = new StringDict(conn, "type_dict");
             PreparedStatement ps = conn.prepareStatement("SELECT name, type_id, value FROM variables");
             ResultSet r = ps.executeQuery()) {
            while (r.next()) {
                String name = r.getString(1);
                int typeId = r.getInt(2);
                byte[] value = r.getBytes(3);
                if (name == null) continue;
                String typeName = typeDict.get(typeId);
                if (typeName == null || value == null) continue;
                if (BukkitItemCodec.TYPE_MARKER.equals(typeName)) {
                    ItemStack stack = BukkitItemCodec.decode(value);
                    ItemType it = BukkitItemCodec.toItemType(stack);
                    if (it != null) rows.add(new Object[] { name, it });
                    continue;
                }
                ClassInfo<?> ci = Classes.getClassInfoNoError(typeName);
                if (ci == null || ci.getSerializer() == null) continue;
                Object obj;
                try {
                    obj = Classes.deserialize(ci, value);
                } catch (Throwable t) {
                    continue;
                }
                if (obj != null) rows.add(new Object[] { name, obj });
            }
            SqliteSetup.checkpointTruncate(conn);
        }

        if (rows.isEmpty()) return;

        SkStoragePlugin.get().getServer().getScheduler().runTask(SkStoragePlugin.get(), () -> {
            for (Object[] row : rows) feedLoaded((String) row[0], row[1]);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        long t0 = System.currentTimeMillis();
        synchronized (connectionLock) {
            try {
                FileHandle h = openHandle(uuid.toString(), pathFor(uuid.toString()));
                int loaded = loadVariables(h);
                long elapsed = System.currentTimeMillis() - t0;
                SkStoragePlugin.get().getLogger().info(String.format(
                    "Loaded %d var%s for %s (%s) in %dms",
                    loaded, loaded == 1 ? "" : "s", e.getPlayer().getName(), uuid, elapsed));
            } catch (SQLException ex) {
                Skript.error("[SkStorage] Failed to open file for joining player " +
                    e.getPlayer().getName() + ": " + ex.getMessage());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        String uuid = e.getPlayer().getUniqueId().toString();
        File dbFile;
        synchronized (connectionLock) {
            FileHandle h = handles.remove(uuid);
            if (h != null) closeHandle(h);
            dbFile = pathFor(uuid);
        }
        tryDiscardIfSmall(dbFile);
    }

    private boolean tryDiscardIfSmall(File dbFile) {
        double thresholdKb = SkStoragePlugin.get().getAutoDiscardKb();
        if (thresholdKb <= 0 || !dbFile.exists()) return false;
        long sizeBytes = dbFile.length();
        long thresholdBytes = (long) (thresholdKb * 1024);
        if (sizeBytes > thresholdBytes) return false;
        File shm = new File(dbFile.getAbsolutePath() + "-shm");
        File wal = new File(dbFile.getAbsolutePath() + "-wal");
        if (dbFile.delete()) {
            if (shm.exists()) shm.delete();
            if (wal.exists()) wal.delete();
            SkStoragePlugin.get().getLogger().info(String.format(
                "Auto-discarded %s (%d bytes <= %.1f KB threshold)",
                dbFile.getName(), sizeBytes, thresholdKb));
            return true;
        }
        return false;
    }

    public int cleanupSmallFiles() {
        if (rootDir == null || !rootDir.exists()) return 0;
        int deleted = 0;
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(rootDir.toPath())) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) stream::iterator) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".db") || name.endsWith("-shm.db") || name.endsWith("-wal.db")) continue;
                String uuid = name.substring(0, name.length() - 3);
                synchronized (connectionLock) {
                    if (handles.containsKey(uuid)) continue;
                }
                if (tryDiscardIfSmall(p.toFile())) deleted++;
            }
        } catch (java.io.IOException ignored) {}
        return deleted;
    }

    private int loadVariables(FileHandle h) throws SQLException {
        int count = 0, bad = 0;
        java.util.List<String> deadNames = new java.util.ArrayList<>();
        try (PreparedStatement ps = h.conn.prepareStatement(
                 "SELECT name, type_id, value FROM variables");
             ResultSet r = ps.executeQuery()) {
            while (r.next()) {
                String name = r.getString(1);
                int typeId = r.getInt(2);
                byte[] value = r.getBytes(3);
                String typeName = h.typeDict.get(typeId);
                if (name == null || typeName == null) continue;
                if (value == null) { feedLoaded(name, null); count++; continue; }
                if (BukkitItemCodec.TYPE_MARKER.equals(typeName)) {
                    ItemStack stack = BukkitItemCodec.decode(value);
                    ItemType it = BukkitItemCodec.toItemType(stack);
                    if (it != null) { feedLoaded(name, it); count++; }
                    continue;
                }
                ClassInfo<?> ci = Classes.getClassInfoNoError(typeName);
                if (ci == null || ci.getSerializer() == null) {
                    bad++; deadNames.add(name); continue;
                }
                Object obj;
                try {
                    obj = Classes.deserialize(ci, value);
                } catch (Throwable t) {
                    bad++;
                    deadNames.add(name);
                    if (bad <= 3) {
                        Skript.warning("[SkStorage] could not deserialize " + name +
                            " (type=" + typeName + "): " + t.getClass().getSimpleName() + " - " + t.getMessage());
                    }
                    continue;
                }
                if (obj != null) { feedLoaded(name, obj); count++; }
            }
        }
        if (!deadNames.isEmpty()) {
            try (PreparedStatement del = h.conn.prepareStatement(DELETE)) {
                for (String n : deadNames) { del.setString(1, n); del.addBatch(); }
                del.executeBatch();
                h.conn.commit();
            } catch (SQLException e) {
                Skript.warning("[SkStorage] player file: failed to purge " + deadNames.size()
                    + " unreadable rows: " + e.getMessage());
            }
        }
        return count;
    }

    private FileHandle openHandle(String uuid, File dbFile) throws SQLException {
        FileHandle h = handles.get(uuid);
        if (h != null && h.conn != null && !h.conn.isClosed()) return h;

        while (handles.size() >= MAX_OPEN_HANDLES) {
            var oldest = handles.entrySet().iterator().next();
            FileHandle evicted = oldest.getValue();
            handles.remove(oldest.getKey());
            closeHandle(evicted);
        }

        h = new FileHandle();
        h.conn = SqliteSetup.open(dbFile.getAbsolutePath());
        ensureSchema(h.conn);
        h.typeDict = new StringDict(h.conn, "type_dict");
        h.upsert = h.conn.prepareStatement(UPSERT);
        h.delete = h.conn.prepareStatement(DELETE);
        h.conn.commit();
        handles.put(uuid, h);
        return h;
    }

    private void closeHandle(FileHandle h) {
        drainHandle(h);
        try { if (h.upsert != null) h.upsert.close(); } catch (SQLException ignored) {}
        try { if (h.delete != null) h.delete.close(); } catch (SQLException ignored) {}
        if (h.typeDict != null) h.typeDict.close();
        SqliteSetup.checkpointTruncate(h.conn);
        try { if (h.conn != null) h.conn.close(); } catch (SQLException ignored) {}
    }

    private void ensureSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS variables (" +
                "  name    TEXT PRIMARY KEY," +
                "  type_id INTEGER NOT NULL," +
                "  value   BLOB" +
                ") WITHOUT ROWID"
            );
        }
    }

    private File pathFor(String uuid) {
        String shard = uuid.substring(0, Math.min(shardChars, uuid.length()));
        File shardDir = new File(rootDir, shard);
        if (!shardDir.exists()) shardDir.mkdirs();
        return new File(shardDir, uuid + ".db");
    }

    @Override
    protected void allLoaded() {}

    @Override
    protected boolean connect() { return true; }

    @Override
    protected void disconnect() {
        synchronized (connectionLock) {
            for (FileHandle h : handles.values()) closeHandle(h);
            handles.clear();
        }
    }

    @Override
    public void close() {
        super.close();
        disconnect();
    }

    @Override
    public void flush() {
        synchronized (connectionLock) {
            for (FileHandle h : handles.values()) drainHandle(h);
        }
    }

    private void drainHandle(FileHandle h) {
        if (h.pending.isEmpty()) return;
        try {
            for (Map.Entry<String, PendingOp> e : h.pending.entrySet()) {
                PendingOp op = e.getValue();
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
            writesFailed.addAndGet(h.pending.size());
            h.pending.clear();
            Skript.error("[SkStorage] player flush failed: " + e.getMessage());
        }
    }

    @Override
    protected boolean save(String name, @Nullable String type, @Nullable byte[] value) {
        String uuid = UuidExtractor.firstUuid(name);
        if (uuid == null) {
            Skript.error("[SkStorage] skstorage-player received non-UUID variable: " + name);
            writesFailed.incrementAndGet();
            return false;
        }
        BukkitItemCodec.Result codec = BukkitItemCodec.intercept(name, type, value);
        synchronized (connectionLock) {
            writesTotal.incrementAndGet();
            try {
                FileHandle h = openHandle(uuid, pathFor(uuid));
                h.pending.put(name, new PendingOp(codec.type, codec.value));
                return true;
            } catch (SQLException e) {
                writesFailed.incrementAndGet();
                Skript.error("[SkStorage] player save failed for " + name + ": " + e.getMessage());
                return false;
            }
        }
    }

    public int openFileCount() {
        synchronized (connectionLock) {
            return handles.size();
        }
    }

    public int flatline(UUID uuid) throws SQLException {
        synchronized (connectionLock) {
            FileHandle existing = handles.remove(uuid.toString());
            if (existing != null) closeHandle(existing);

            File f = pathFor(uuid.toString());
            if (!f.exists()) return -1;

            FileHandle fresh = openHandle(uuid.toString(), f);
            return loadVariables(fresh);
        }
    }
}
