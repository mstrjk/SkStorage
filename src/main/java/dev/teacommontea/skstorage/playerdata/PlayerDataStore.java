package dev.teacommontea.skstorage.playerdata;

import dev.teacommontea.skstorage.util.SqliteSetup;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

public final class PlayerDataStore {

    private final Connection conn;
    private final PreparedStatement upsertPlayer;
    private final PreparedStatement updateQuit;
    private final PreparedStatement insertNameHistory;
    private final PreparedStatement selectPlayer;
    private final PreparedStatement selectByName;

    public PlayerDataStore(String dbPath) throws SQLException {
        this.conn = SqliteSetup.open(dbPath);
        ensureSchema();
        upsertPlayer = conn.prepareStatement(
            "INSERT INTO players (uuid, name, first_join, last_join, total_sessions, last_ip_hash) " +
            "VALUES (?, ?, ?, ?, 1, ?) " +
            "ON CONFLICT(uuid) DO UPDATE SET " +
            "  name = excluded.name, " +
            "  last_join = excluded.last_join, " +
            "  total_sessions = total_sessions + 1, " +
            "  last_ip_hash = COALESCE(excluded.last_ip_hash, last_ip_hash)"
        );
        updateQuit = conn.prepareStatement(
            "UPDATE players SET last_quit = ?, total_playtime = total_playtime + ?, last_world = ? WHERE uuid = ?"
        );
        insertNameHistory = conn.prepareStatement(
            "INSERT OR IGNORE INTO name_history (uuid, name, seen_at) VALUES (?, ?, ?)"
        );
        selectPlayer = conn.prepareStatement(
            "SELECT name, first_join, last_join, last_quit, total_playtime, total_sessions, last_world, last_ip_hash " +
            "FROM players WHERE uuid = ?"
        );
        selectByName = conn.prepareStatement(
            "SELECT uuid FROM players WHERE name = ? COLLATE NOCASE LIMIT 1"
        );
    }

    private void ensureSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS players (" +
                "  uuid           TEXT PRIMARY KEY," +
                "  name           TEXT NOT NULL," +
                "  first_join     INTEGER NOT NULL," +
                "  last_join      INTEGER NOT NULL," +
                "  last_quit      INTEGER," +
                "  total_playtime INTEGER NOT NULL DEFAULT 0," +
                "  total_sessions INTEGER NOT NULL DEFAULT 0," +
                "  last_world     TEXT," +
                "  last_ip_hash   TEXT," +
                "  head_textures  TEXT," +
                "  head_signature TEXT," +
                "  head_cached_at INTEGER" +
                ") WITHOUT ROWID"
            );
            st.execute(
                "CREATE TABLE IF NOT EXISTS name_history (" +
                "  uuid     TEXT NOT NULL," +
                "  name     TEXT NOT NULL," +
                "  seen_at  INTEGER NOT NULL," +
                "  PRIMARY KEY (uuid, name)" +
                ") WITHOUT ROWID"
            );
            st.execute("CREATE INDEX IF NOT EXISTS idx_players_name ON players(name)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_history_name ON name_history(name)");

            tryAddColumn(st, "ALTER TABLE players ADD COLUMN head_textures TEXT");
            tryAddColumn(st, "ALTER TABLE players ADD COLUMN head_signature TEXT");
            tryAddColumn(st, "ALTER TABLE players ADD COLUMN head_cached_at INTEGER");
        }
    }

    private static void tryAddColumn(Statement st, String sql) {
        try {
            st.execute(sql);
        } catch (SQLException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("duplicate column")) return;

        }
    }

    public synchronized void recordJoin(UUID uuid, String name, long joinMs, @Nullable String ipHash) {
        try {
            upsertPlayer.setString(1, uuid.toString());
            upsertPlayer.setString(2, name);
            upsertPlayer.setLong(3, joinMs);
            upsertPlayer.setLong(4, joinMs);
            if (ipHash == null) upsertPlayer.setNull(5, java.sql.Types.VARCHAR);
            else upsertPlayer.setString(5, ipHash);
            upsertPlayer.executeUpdate();

            insertNameHistory.setString(1, uuid.toString());
            insertNameHistory.setString(2, name);
            insertNameHistory.setLong(3, joinMs);
            insertNameHistory.executeUpdate();
        } catch (SQLException ignored) {

        }
    }

    public synchronized void recordQuit(UUID uuid, long quitMs, long sessionDurationMs, @Nullable String world) {
        try {
            updateQuit.setLong(1, quitMs);
            updateQuit.setLong(2, sessionDurationMs);
            if (world == null) updateQuit.setNull(3, java.sql.Types.VARCHAR);
            else updateQuit.setString(3, world);
            updateQuit.setString(4, uuid.toString());
            updateQuit.executeUpdate();
        } catch (SQLException ignored) {}
    }

    @Nullable
    public synchronized PlayerRecord get(UUID uuid) {
        try {
            selectPlayer.setString(1, uuid.toString());
            try (ResultSet rs = selectPlayer.executeQuery()) {
                if (!rs.next()) return null;
                Long lastQuit = rs.getLong("last_quit");
                if (rs.wasNull()) lastQuit = null;
                return new PlayerRecord(
                    uuid,
                    rs.getString("name"),
                    rs.getLong("first_join"),
                    rs.getLong("last_join"),
                    lastQuit,
                    rs.getLong("total_playtime"),
                    rs.getInt("total_sessions"),
                    rs.getString("last_world"),
                    rs.getString("last_ip_hash")
                );
            }
        } catch (SQLException e) {
            return null;
        }
    }

    public synchronized void cacheHeadTextures(UUID uuid, String texturesValue, @Nullable String signature) {
        try (PreparedStatement ps = conn.prepareStatement(
                 "UPDATE players SET head_textures = ?, head_signature = ?, head_cached_at = ? WHERE uuid = ?")) {
            ps.setString(1, texturesValue);
            if (signature == null) ps.setNull(2, java.sql.Types.VARCHAR);
            else ps.setString(2, signature);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    @Nullable
    public synchronized HeadTextures getHeadTextures(UUID uuid) {
        try (PreparedStatement ps = conn.prepareStatement(
                 "SELECT head_textures, head_signature, head_cached_at FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String tx = rs.getString(1);
                if (tx == null) return null;
                return new HeadTextures(tx, rs.getString(2), rs.getLong(3));
            }
        } catch (SQLException e) {
            return null;
        }
    }

    public record HeadTextures(String value, @Nullable String signature, long cachedAt) {}

    @Nullable
    public synchronized UUID lookupUuid(String name) {
        try {
            selectByName.setString(1, name);
            try (ResultSet rs = selectByName.executeQuery()) {
                if (!rs.next()) return null;
                return UUID.fromString(rs.getString(1));
            }
        } catch (SQLException e) {
            return null;
        }
    }

    public synchronized java.util.List<NameMatch> searchByPartialName(String prefix, int limit) {
        java.util.List<NameMatch> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT name, uuid FROM name_history WHERE name LIKE ? COLLATE NOCASE LIMIT ?")) {
            ps.setString(1, prefix + "%");
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new NameMatch(rs.getString(1), UUID.fromString(rs.getString(2))));
                }
            }
        } catch (SQLException ignored) {}
        return out;
    }

    public record NameMatch(String name, UUID uuid) {}

    public void close() {
        try { upsertPlayer.close(); } catch (SQLException ignored) {}
        try { updateQuit.close(); } catch (SQLException ignored) {}
        try { insertNameHistory.close(); } catch (SQLException ignored) {}
        try { selectPlayer.close(); } catch (SQLException ignored) {}
        try { selectByName.close(); } catch (SQLException ignored) {}
        dev.teacommontea.skstorage.util.SqliteSetup.checkpointTruncate(conn);
        try { conn.close(); } catch (SQLException ignored) {}
    }

    public record PlayerRecord(
        UUID uuid,
        String name,
        long firstJoin,
        long lastJoin,
        @Nullable Long lastQuit,
        long totalPlaytime,
        int totalSessions,
        @Nullable String lastWorld,
        @Nullable String lastIpHash
    ) {}
}
