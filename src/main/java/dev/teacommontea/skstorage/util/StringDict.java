package dev.teacommontea.skstorage.util;

import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

public final class StringDict implements AutoCloseable {

    private final String tableName;
    private final Map<String, Integer> toId = new HashMap<>();
    private final Map<Integer, String> toString = new HashMap<>();
    private final PreparedStatement insertStmt;
    private final PreparedStatement selectStmt;

    public StringDict(Connection conn, String tableName) throws SQLException {
        this.tableName = tableName;
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + tableName +
                " (id INTEGER PRIMARY KEY AUTOINCREMENT, value TEXT UNIQUE NOT NULL)");
        }
        insertStmt = conn.prepareStatement(
            "INSERT OR IGNORE INTO " + tableName + " (value) VALUES (?)");
        selectStmt = conn.prepareStatement(
            "SELECT id FROM " + tableName + " WHERE value = ?");
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, value FROM " + tableName)) {
            while (rs.next()) {
                int id = rs.getInt(1);
                String s = rs.getString(2);
                toId.put(s, id);
                toString.put(id, s);
            }
        }
    }

    public int getOrInsert(String s) throws SQLException {
        Integer cached = toId.get(s);
        if (cached != null) return cached;

        insertStmt.setString(1, s);
        insertStmt.executeUpdate();

        selectStmt.setString(1, s);
        try (ResultSet rs = selectStmt.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("Failed to read back inserted entry into " + tableName + ": " + s);
            }
            int id = rs.getInt(1);
            toId.put(s, id);
            toString.put(id, s);
            return id;
        }
    }

    @Nullable
    public String get(int id) {
        return toString.get(id);
    }

    public int size() {
        return toId.size();
    }

    public void close() {
        try { insertStmt.close(); } catch (SQLException ignored) {}
        try { selectStmt.close(); } catch (SQLException ignored) {}
    }
}
