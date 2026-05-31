package dev.teacommontea.skstorage.migrate;

import dev.teacommontea.skstorage.SkStoragePlugin;
import dev.teacommontea.skstorage.command.SkStorageCommand;
import dev.teacommontea.skstorage.scope.PersistentScope;
import dev.teacommontea.skstorage.scope.PlayerScope;
import dev.teacommontea.skstorage.scope.ServerScope;
import dev.teacommontea.skstorage.scope.SkStorageBase;
import dev.teacommontea.skstorage.util.UuidExtractor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class SqlibraryMigrator {

    private static final String PFX = SkStorageCommand.PFX;
    private static final String MSG = SkStorageCommand.MSG;
    private static final String FEAT = SkStorageCommand.FEAT;
    private static final String ERR = SkStorageCommand.ERR;
    private static final String ACT = SkStorageCommand.ACT;
    private static final String INFO = SkStorageCommand.INFO;

    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private final SkStoragePlugin plugin;

    public SqlibraryMigrator(SkStoragePlugin plugin) {
        this.plugin = plugin;
    }

    public void migrate(File source, CommandSender sender, boolean dryRun) throws SQLException {
        String phase = dryRun ? INFO + "(dry-run)" : ERR + "(LIVE, async)";
        sender.sendMessage(PFX + FEAT + "SQLibrary migration " + phase);
        sender.sendMessage(PFX + FEAT + "Source: " + INFO + source.getAbsolutePath());

        if (dryRun) {
            runDryRun(source, sender);
            return;
        }

        if (!RUNNING.compareAndSet(false, true)) {
            sender.sendMessage(PFX + ERR + "A migration is already running. Wait for it to finish.");
            return;
        }

        PersistentScope persistent = null;
        PlayerScope player = null;
        ServerScope server = null;
        for (SkStorageBase s : plugin.getRegisteredScopes()) {
            if (s instanceof PersistentScope p) persistent = p;
            else if (s instanceof PlayerScope p) player = p;
            else if (s instanceof ServerScope p) server = p;
        }
        if (persistent == null || player == null || server == null) {
            sender.sendMessage(PFX + ERR + "One or more SkStorage scopes are not registered. Skript databases config wrong?");
            return;
        }
        final PersistentScope fp = persistent;
        final PlayerScope fpl = player;
        final ServerScope fs = server;

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    runLive(source, sender, fp, fpl, fs);
                } catch (Exception e) {
                    tellSender(sender, PFX + ERR + "Migration failed: " + INFO + e.getMessage());
                    plugin.getLogger().severe("Migration crashed: " + e);
                    e.printStackTrace();
                } finally {
                    RUNNING.set(false);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void runDryRun(File source, CommandSender sender) throws SQLException {
        Map<String, Long> counts = new HashMap<>();
        Map<String, Long> bytes = new HashMap<>();
        long total = 0;

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath());
             PreparedStatement ps = conn.prepareStatement("SELECT name, type, value FROM variables21");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                total++;
                String name = rs.getString(1);
                byte[] value = rs.getBytes(3);
                if (name == null) continue;
                String scope = routeOf(name);
                counts.merge(scope, 1L, Long::sum);
                if (value != null) bytes.merge(scope, (long) value.length, Long::sum);
            }
        }

        sender.sendMessage(PFX + FEAT + "Migration report");
        sender.sendMessage(PFX + FEAT + "Total rows scanned: " + INFO + total);
        for (var entry : counts.entrySet()) {
            long b = bytes.getOrDefault(entry.getKey(), 0L);
            sender.sendMessage(PFX + FEAT + "-> " + entry.getKey() + ": " + INFO + entry.getValue() + " rows (" + b + " bytes raw)");
        }
    }

    private void runLive(File source, CommandSender sender,
                         PersistentScope persistent, PlayerScope player, ServerScope server) throws SQLException {
        long total = 0, written = 0, skipped = 0;
        long t0 = System.currentTimeMillis();
        String lastSeenName = "";
        boolean sourceCorruption = false;

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath());
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT name, type, value FROM variables21 ORDER BY name")) {

            try (ResultSet rs = ps.executeQuery()) {
                while (true) {
                    try {
                        if (!rs.next()) break;
                    } catch (SQLException ex) {
                        if (isSqliteCorrupt(ex)) {
                            sourceCorruption = true;
                            break;
                        }
                        throw ex;
                    }

                    total++;
                    String name = rs.getString(1);
                    String type = rs.getString(2);
                    byte[] value = rs.getBytes(3);
                    if (name == null) { skipped++; continue; }
                    lastSeenName = name;

                    String lower = name.toLowerCase(java.util.Locale.ENGLISH);
                    String scope = routeOf(lower);
                    SkStorageBase target = switch (scope) {
                        case "persistent" -> persistent;
                        case "player" -> player;
                        case "server", "server (legacy)" -> server;
                        default -> null;
                    };
                    if (target == null) {
                        skipped++;
                        continue;
                    }

                    if (target.migrationSave(lower, type, value)) {
                        written++;
                    } else {
                        skipped++;
                    }

                    if (written % 10000 == 0 && written > 0) {
                        long elapsed = System.currentTimeMillis() - t0;
                        long rate = elapsed > 0 ? (written * 1000L / elapsed) : 0;
                        tellSender(sender, PFX + ACT + "Migrated " + INFO + written + MSG + " rows (" + INFO + rate + MSG + " rows/sec)");
                    }
                }
            } catch (SQLException ex) {
                if (isSqliteCorrupt(ex)) {
                    sourceCorruption = true;
                } else {
                    throw ex;
                }
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            persistent.flush();
            player.flush();
            server.flush();
        });

        if (sourceCorruption) {
            tellSender(sender, PFX + FEAT + "Migration partial " + ERR + "(source DB corruption)");
            tellSender(sender, PFX + FEAT + "Last good row: " + INFO + truncate(lastSeenName, 80));
            tellSender(sender, PFX + FEAT + "Total scanned: " + INFO + total + MSG + " (more rows exist in source but are in unreadable pages)");
        } else {
            tellSender(sender, PFX + FEAT + "Migration complete");
            tellSender(sender, PFX + FEAT + "Total scanned: " + INFO + total);
        }
        tellSender(sender, PFX + FEAT + "Written: " + "§a" + written);
        tellSender(sender, PFX + FEAT + "Skipped: " + ERR + skipped);
        if (sourceCorruption) {
            tellSender(sender, PFX + MSG + "To salvage rows beyond the corrupt zone, run on the host:");
            tellSender(sender, PFX + INFO + "sqlite3 " + source.getName() + " \".recover\" | sqlite3 fixed.db");
            tellSender(sender, PFX + INFO + "/skstorage migrate sqlibrary <path-to-fixed.db> --i-have-backed-up");
        }
        long rate = elapsed > 0 ? (written * 1000L / elapsed) : 0;
        tellSender(sender, PFX + FEAT + "Elapsed: " + INFO + (elapsed / 1000L) + "s (" + rate + " rows/sec)");
        tellSender(sender, PFX + MSG + "Recommended: restart server so any open file handles close cleanly.");
    }

    private static boolean isSqliteCorrupt(SQLException e) {
        String msg = e.getMessage();
        if (msg != null && (msg.contains("SQLITE_CORRUPT") || msg.contains("malformed"))) return true;
        return e.getErrorCode() == 11;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    private void tellSender(CommandSender sender, String msg) {
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(msg));
    }

    private String routeOf(String name) {
        for (Pattern p : plugin.getPersistentPatterns()) {
            if (p.matcher(name).matches()) return "persistent";
        }
        if (UuidExtractor.containsUuid(name)) return "player";
        if (name.startsWith("sf::")) return "server";
        if (plugin.isServerLegacyFallthrough()) return "server (legacy)";
        return "UNROUTED";
    }
}
