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

public class ServerScope extends SkStorageBase {

    private static final Pattern SF_PATTERN = Pattern.compile("^sf::.*");

    private static final Pattern LEGACY_PATTERN = Pattern.compile(".*");

    private static final String UPSERT =
        "INSERT INTO variables (name, type_id, value) VALUES (?, ?, ?) " +
        "ON CONFLICT(name) DO UPDATE SET type_id=excluded.type_id, value=excluded.value";
    private static final String DELETE = "DELETE FROM variables WHERE name = ?";

    @Nullable private Connection connection;
    @Nullable private StringDict typeDict;
    @Nullable private PreparedStatement upsertStmt;
    @Nullable private PreparedStatement deleteStmt;
    private final LinkedHashMap<String, PendingOp> pending = new LinkedHashMap<>();

    public ServerScope(String type) {
        super(type);
    }

    @Override
    protected Pattern scopePattern() {
        return SkStoragePlugin.get().isServerLegacyFallthrough() ? LEGACY_PATTERN : SF_PATTERN;
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
            typeDict = new StringDict(connection, "type_dict");
            upsertStmt = connection.prepareStatement(UPSERT);
            deleteStmt = connection.prepareStatement(DELETE);
            connection.commit();
            installScopePattern();
            loadAllRows();
            SkStoragePlugin.get().registerScope(this);
            return true;
        } catch (Exception e) {
            Skript.error("[SkStorage] server load failed for " + file + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void ensureSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS variables (" +
                "  name    TEXT PRIMARY KEY," +
                "  type_id INTEGER NOT NULL," +
                "  value   BLOB" +
                ") WITHOUT ROWID"
            );
        }
    }

    private void loadAllRows() throws SQLException {
        long ok = 0, badDeserialize = 0;
        List<String> deadNames = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                 "SELECT name, type_id, value FROM variables");
             ResultSet r = ps.executeQuery()) {
            while (r.next()) {
                String name = r.getString(1);
                int typeId = r.getInt(2);
                byte[] value = r.getBytes(3);
                String typeName = typeDict.get(typeId);
                if (name == null || typeName == null) continue;
                if (value == null) { feedLoaded(name, null); continue; }
                if (BukkitItemCodec.TYPE_MARKER.equals(typeName)) {
                    ItemStack stack = BukkitItemCodec.decode(value);
                    ItemType it = BukkitItemCodec.toItemType(stack);
                    if (it != null) { feedLoaded(name, it); ok++; }
                    continue;
                }
                ClassInfo<?> ci = Classes.getClassInfoNoError(typeName);
                if (ci == null || ci.getSerializer() == null) {
                    badDeserialize++;
                    deadNames.add(name);
                    continue;
                }
                Object obj;
                try {
                    obj = Classes.deserialize(ci, value);
                } catch (Throwable t) {
                    badDeserialize++;
                    deadNames.add(name);
                    if (badDeserialize <= 5) {
                        Skript.warning("[SkStorage] could not deserialize " + name +
                            " (type=" + typeName + "): " + t.getClass().getSimpleName() + " - " + t.getMessage());
                    }
                    continue;
                }
                if (obj != null) {
                    feedLoaded(name, obj);
                    ok++;
                }
            }
        }
        if (!deadNames.isEmpty()) {
            try (PreparedStatement del = connection.prepareStatement(DELETE)) {
                for (String n : deadNames) { del.setString(1, n); del.addBatch(); }
                del.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                Skript.warning("[SkStorage] server.db: failed to purge " + deadNames.size()
                    + " unreadable rows: " + e.getMessage());
            }
        }
        if (badDeserialize > 0) {
            Skript.warning("[SkStorage] server.db: loaded " + ok + " variables, " +
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
            if (typeDict != null) typeDict.close();
            SqliteSetup.checkpointTruncate(connection);
            try { if (connection != null) connection.close(); } catch (SQLException ignored) {}
            upsertStmt = null;
            deleteStmt = null;
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
                PendingOp op = e.getValue();
                if (op.isDelete()) {
                    deleteStmt.setString(1, e.getKey());
                    deleteStmt.addBatch();
                } else {
                    int typeId = typeDict.getOrInsert(op.type);
                    upsertStmt.setString(1, e.getKey());
                    upsertStmt.setInt(2, typeId);
                    upsertStmt.setBytes(3, op.value);
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
            Skript.error("[SkStorage] server flush failed: " + e.getMessage());
        }
    }

    public int purgeByGlob(String glob) {
        synchronized (connectionLock) {
            if (connection == null && !connect()) return 0;
            drainAndCommit();
            try (PreparedStatement ps = connection.prepareStatement(
                     "DELETE FROM variables WHERE name GLOB ?")) {
                ps.setString(1, glob);
                int n = ps.executeUpdate();
                connection.commit();
                return n;
            } catch (SQLException e) {
                Skript.error("[SkStorage] purge failed for " + glob + ": " + e.getMessage());
                return 0;
            }
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
