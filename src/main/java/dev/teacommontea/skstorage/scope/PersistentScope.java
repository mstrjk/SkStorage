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
import java.util.regex.Pattern;

public class PersistentScope extends SkStorageBase {

    private static final char UUID_SENTINEL = '';
    private static final String UUID_SENTINEL_STR = String.valueOf(UUID_SENTINEL);

    private static final String UPSERT =
        "INSERT INTO variables (uuid_id, key_id, type_id, value) VALUES (?, ?, ?, ?) " +
        "ON CONFLICT(uuid_id, key_id) DO UPDATE SET type_id=excluded.type_id, value=excluded.value";
    private static final String DELETE =
        "DELETE FROM variables WHERE uuid_id = ? AND key_id = ?";

    @Nullable private Connection connection;
    @Nullable private StringDict uuidDict;
    @Nullable private StringDict keyDict;
    @Nullable private StringDict typeDict;
    @Nullable private PreparedStatement upsertStmt;
    @Nullable private PreparedStatement deleteStmt;
    private final LinkedHashMap<String, PendingOp> pending = new LinkedHashMap<>();

    public PersistentScope(String type) {
        super(type);
    }

    @Override
    protected Pattern scopePattern() {
        return SkStoragePlugin.get().getPersistentCombinedPattern();
    }

    @Override
    protected boolean requiresFile() { return true; }

    @Override
    protected File getFile(String fileName) { return new File(fileName); }

    @Override
    protected boolean load_i(SectionNode node) {
        if (file == null) return false;
        try {
            connection = SqliteSetup.open(file.getAbsolutePath());
            ensureSchema();
            uuidDict = new StringDict(connection, "uuid_dict");
            keyDict  = new StringDict(connection, "key_dict");
            typeDict = new StringDict(connection, "type_dict");
            upsertStmt = connection.prepareStatement(UPSERT);
            deleteStmt = connection.prepareStatement(DELETE);
            connection.commit();
            installScopePattern();
            loadAllRows();
            SkStoragePlugin.get().registerScope(this);
            return true;
        } catch (Exception e) {
            Skript.error("[SkStorage] persistent load failed for " + file + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void ensureSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS variables (" +
                "  uuid_id INTEGER NOT NULL DEFAULT 0," +
                "  key_id  INTEGER NOT NULL," +
                "  type_id INTEGER NOT NULL," +
                "  value   BLOB," +
                "  PRIMARY KEY (uuid_id, key_id)" +
                ") WITHOUT ROWID"
            );
        }
    }

    private void loadAllRows() throws SQLException {
        long ok = 0, badDeserialize = 0;
        List<int[]> deadRows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                 "SELECT uuid_id, key_id, type_id, value FROM variables");
             ResultSet r = ps.executeQuery()) {
            while (r.next()) {
                int uuidId = r.getInt(1);
                int keyId  = r.getInt(2);
                int typeId = r.getInt(3);
                byte[] value = r.getBytes(4);

                String storedKey = keyDict.get(keyId);
                String typeName  = typeDict.get(typeId);
                if (storedKey == null || typeName == null) continue;

                String fullName;
                if (uuidId == 0) {
                    fullName = storedKey;
                } else {
                    String uuid = uuidDict.get(uuidId);
                    if (uuid == null) continue;
                    fullName = storedKey.replace(UUID_SENTINEL_STR, uuid);
                }

                if (value == null) { feedLoaded(fullName, null); continue; }
                if (BukkitItemCodec.TYPE_MARKER.equals(typeName)) {
                    ItemStack stack = BukkitItemCodec.decode(value);
                    ItemType it = BukkitItemCodec.toItemType(stack);
                    if (it != null) { feedLoaded(fullName, it); ok++; }
                    continue;
                }
                ClassInfo<?> ci = Classes.getClassInfoNoError(typeName);
                if (ci == null || ci.getSerializer() == null) {
                    badDeserialize++;
                    deadRows.add(new int[]{uuidId, keyId});
                    continue;
                }
                Object obj;
                try {
                    obj = Classes.deserialize(ci, value);
                } catch (Throwable t) {
                    badDeserialize++;
                    deadRows.add(new int[]{uuidId, keyId});
                    if (badDeserialize <= 5) {
                        Skript.warning("[SkStorage] could not deserialize " + fullName +
                            " (type=" + typeName + "): " + t.getClass().getSimpleName() + " - " + t.getMessage());
                    }
                    continue;
                }
                if (obj != null) {
                    feedLoaded(fullName, obj);
                    ok++;
                }
            }
        }
        if (!deadRows.isEmpty()) {
            try (PreparedStatement del = connection.prepareStatement(DELETE)) {
                for (int[] ids : deadRows) {
                    del.setInt(1, ids[0]);
                    del.setInt(2, ids[1]);
                    del.addBatch();
                }
                del.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                Skript.warning("[SkStorage] persistent.db: failed to purge " + deadRows.size()
                    + " unreadable rows: " + e.getMessage());
            }
        }
        if (badDeserialize > 0) {
            Skript.warning("[SkStorage] persistent.db: loaded " + ok + " variables, " +
                badDeserialize + " unreadable rows purged");
        }
    }

    @Override
    protected void allLoaded() {}

    @Override
    protected boolean connect() {
        try {
            if (connection == null || connection.isClosed()) {
                if (file == null) return false;
                connection = SqliteSetup.open(file.getAbsolutePath());
                ensureSchema();
                uuidDict = new StringDict(connection, "uuid_dict");
                keyDict  = new StringDict(connection, "key_dict");
                typeDict = new StringDict(connection, "type_dict");
                upsertStmt = connection.prepareStatement(UPSERT);
                deleteStmt = connection.prepareStatement(DELETE);
                connection.commit();
            }
            return true;
        } catch (SQLException e) {
            Skript.error("[SkStorage] Reconnect failed for " + file + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    protected void disconnect() {
        synchronized (connectionLock) {
            drainAndCommit();
            try { if (upsertStmt != null) upsertStmt.close(); } catch (SQLException ignored) {}
            try { if (deleteStmt != null) deleteStmt.close(); } catch (SQLException ignored) {}
            if (uuidDict != null) uuidDict.close();
            if (keyDict  != null) keyDict.close();
            if (typeDict != null) typeDict.close();
            SqliteSetup.checkpointTruncate(connection);
            try { if (connection != null) connection.close(); } catch (SQLException ignored) {}
            upsertStmt = null;
            deleteStmt = null;
            uuidDict = null;
            keyDict = null;
            typeDict = null;
            connection = null;
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
            drainAndCommit();
        }
    }

    private void drainAndCommit() {
        if (connection == null || pending.isEmpty()) return;
        try {
            for (Map.Entry<String, PendingOp> e : pending.entrySet()) {
                String name = e.getKey();
                PendingOp op = e.getValue();
                String uuid = UuidExtractor.firstUuid(name);
                int uuidId;
                String storedKey;
                if (uuid == null) {
                    uuidId = 0;
                    storedKey = name;
                } else {
                    uuidId = uuidDict.getOrInsert(uuid);
                    int idx = name.indexOf(uuid);
                    storedKey = name.substring(0, idx) + UUID_SENTINEL + name.substring(idx + uuid.length());
                }
                int keyId = keyDict.getOrInsert(storedKey);
                if (op.isDelete()) {
                    deleteStmt.setInt(1, uuidId);
                    deleteStmt.setInt(2, keyId);
                    deleteStmt.addBatch();
                } else {
                    int typeId = typeDict.getOrInsert(op.type);
                    upsertStmt.setInt(1, uuidId);
                    upsertStmt.setInt(2, keyId);
                    upsertStmt.setInt(3, typeId);
                    upsertStmt.setBytes(4, op.value);
                    upsertStmt.addBatch();
                }
            }
            deleteStmt.executeBatch();
            upsertStmt.executeBatch();
            connection.commit();
            pending.clear();
        } catch (SQLException e) {
            writesFailed.addAndGet(pending.size());
            pending.clear();
            Skript.error("[SkStorage] persistent flush failed: " + e.getMessage());
        }
    }

    @Override
    protected boolean save(String name, @Nullable String type, @Nullable byte[] value) {
        BukkitItemCodec.Result codec = BukkitItemCodec.intercept(name, type, value);
        synchronized (connectionLock) {
            writesTotal.incrementAndGet();
            pending.put(name, new PendingOp(codec.type, codec.value));
            return true;
        }
    }
}
