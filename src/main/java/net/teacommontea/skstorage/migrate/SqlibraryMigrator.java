package net.teacommontea.skstorage.migrate;
import net.teacommontea.skstorage.util.Log;

import net.teacommontea.skstorage.SkStoragePlugin;
import net.teacommontea.skstorage.command.SkStorageCommand;
import net.teacommontea.skstorage.route.SkStorageRouter;
import net.teacommontea.skstorage.route.Table;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SqlibraryMigrator {

    private static final String PFX  = SkStorageCommand.PFX;
    private static final String MSG  = SkStorageCommand.MSG;
    private static final String FEAT = SkStorageCommand.FEAT;
    private static final String ERR  = SkStorageCommand.ERR;
    private static final String ACT  = SkStorageCommand.ACT;
    private static final String INFO = SkStorageCommand.INFO;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private final SkStoragePlugin plugin;

    public SqlibraryMigrator(SkStoragePlugin plugin) {
        this.plugin = plugin;
    }

    public void migrate(File source, CommandSender sender, boolean dryRun) throws SQLException {
        sender.sendMessage(PFX + FEAT + "SQLibrary migration " +
            (dryRun ? INFO + "(dry-run)" : ERR + "(LIVE, async)"));
        sender.sendMessage(PFX + FEAT + "Source: " + INFO + source.getAbsolutePath());

        SkStorageRouter router = plugin.getRouter();
        if (router == null) {
            sender.sendMessage(PFX + ERR + "Router not loaded; is the 'skstorage' database registered in Skript?");
            return;
        }

        if (dryRun) {
            runDryRun(source, sender, router);
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            sender.sendMessage(PFX + ERR + "A migration is already running.");
            return;
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    runLive(source, sender, router);
                } catch (Exception e) {
                    tell(sender, PFX + ERR + "Migration failed: " + INFO + e.getMessage());
                    Log.hard("Migration crashed: " + e);
                } finally {
                    RUNNING.set(false);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void runDryRun(File source, CommandSender sender, SkStorageRouter router) throws SQLException {
        Map<String, Long> counts = new HashMap<>();
        long total = 0;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath());
             PreparedStatement ps = conn.prepareStatement("SELECT name, type, value FROM variables21");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                total++;
                String name = rs.getString(1);
                if (name == null) continue;
                Table t = router.route(name.toLowerCase(Locale.ENGLISH));
                counts.merge(t != null ? t.name() : "UNROUTED", 1L, Long::sum);
            }
        }
        sender.sendMessage(PFX + FEAT + "Total rows scanned: " + INFO + total);
        for (var e : counts.entrySet()) {
            sender.sendMessage(PFX + FEAT + "-> " + e.getKey() + ": " + INFO + e.getValue() + " rows");
        }
    }

    private void runLive(File source, CommandSender sender, SkStorageRouter router) throws SQLException {
        long total = 0, written = 0, skipped = 0;
        long t0 = System.currentTimeMillis();

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath());
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT name, type, value FROM variables21 ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                total++;
                String name = rs.getString(1);
                String type = rs.getString(2);
                byte[] value = rs.getBytes(3);
                if (name == null) { skipped++; continue; }
                String lower = name.toLowerCase(Locale.ENGLISH);
                Table t = router.route(lower);
                if (t == null || !t.save(lower, type, value)) { skipped++; continue; }
                written++;
                if (written % 10000 == 0) {
                    long rate = rate(written, t0);
                    tell(sender, PFX + ACT + "Migrated " + INFO + written + MSG + " rows (" + rate + " rows/sec)");
                }
            }
        }

        SkStorageRouter r = router;
        plugin.getServer().getScheduler().runTask(plugin, r::flushAll);

        long elapsed = System.currentTimeMillis() - t0;
        tell(sender, PFX + FEAT + "Migration complete. Written: §a" + written +
            MSG + ", skipped: " + ERR + skipped + MSG + ", " + (elapsed / 1000L) + "s");
        tell(sender, PFX + MSG + "Recommended: restart the server so file handles close cleanly.");
    }

    private static long rate(long written, long t0) {
        long elapsed = System.currentTimeMillis() - t0;
        return elapsed > 0 ? (written * 1000L / elapsed) : 0;
    }

    private void tell(CommandSender sender, String msg) {
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(msg));
    }
}
