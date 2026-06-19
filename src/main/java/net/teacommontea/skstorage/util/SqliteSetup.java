package net.teacommontea.skstorage.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqliteSetup {

    private SqliteSetup() {}

    public record Options(boolean disableWal, boolean skipFileLocking) {
        public static final Options DEFAULT = new Options(false, false);
    }

    public static void checkpointTruncate(Connection conn) {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.commit();
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                }
            }
        } catch (SQLException ignored) {

        }
    }

    public static Connection open(String filePath) throws SQLException {
        return open(filePath, Options.DEFAULT);
    }

    public static Connection open(String filePath, Options opts) throws SQLException {
        String url = "jdbc:sqlite:" + filePath;
        if (opts.skipFileLocking()) {

            url += "?nolock=1";
        }
        Connection conn = DriverManager.getConnection(url);
        try (Statement st = conn.createStatement()) {
            if (opts.disableWal()) {

                st.execute("PRAGMA journal_mode=DELETE");
                st.execute("PRAGMA synchronous=FULL");
            } else {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA wal_autocheckpoint=1000");
            }
            st.execute("PRAGMA temp_store=MEMORY");
            st.execute("PRAGMA mmap_size=16777216");
            st.execute("PRAGMA cache_size=-2048");
            st.execute("PRAGMA busy_timeout=5000");
        }

        conn.setAutoCommit(false);
        return conn;
    }
}
