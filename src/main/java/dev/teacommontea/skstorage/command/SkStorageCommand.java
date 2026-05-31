package dev.teacommontea.skstorage.command;

import dev.teacommontea.skstorage.SkStoragePlugin;
import dev.teacommontea.skstorage.scope.SkStorageBase;
import dev.teacommontea.skstorage.migrate.CsvMigrator;
import dev.teacommontea.skstorage.migrate.SqlibraryMigrator;
import dev.teacommontea.skstorage.playerdata.PlayerDataStore;
import dev.teacommontea.skstorage.scope.PlayerScope;
import dev.teacommontea.skstorage.util.SkriptReflect;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public final class SkStorageCommand implements CommandExecutor, TabCompleter {

    public static final String PFX = "§2[§aSkStorage§2] ";
    public static final String MSG = "§f";
    public static final String USR = "§b";
    public static final String FEAT = "§e";
    public static final String ERR = "§c";
    public static final String ACT = "§b";
    public static final String INFO = "§7§o";

    private static final List<String> SUBCOMMANDS = List.of(
        "stats", "who", "reload", "migrate", "reset-season", "debug", "flatline", "cleanup", "maths"
    );
    private static final List<String> MIGRATE_TYPES = List.of("sqlibrary", "csv");
    private static final List<String> MATHS_OPS = List.of("add", "subtract", "multiply", "divide", "mod", "pow", "parse", "compare");

    private final SkStoragePlugin plugin;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private static volatile String pendingResetToken;
    private static volatile long pendingResetExpiresAt;

    public SkStorageCommand(SkStoragePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(PFX + MSG + "Usage: " + INFO + "/skstorage <" + String.join("|", SUBCOMMANDS) + ">");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reset-season" -> doResetSeason(sender, args);
            case "migrate" -> doMigrate(sender, args);
            case "who" -> doWho(sender, args);
            case "stats" -> doStats(sender);
            case "reload" -> doReload(sender);
            case "debug" -> doDebug(sender, args);
            case "flatline" -> doFlatline(sender, args);
            case "cleanup" -> doCleanup(sender);
            case "maths" -> doMaths(sender, args);
            default -> sender.sendMessage(PFX + ERR + "Unknown subcommand " + INFO + args[0]);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String label, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (String sub : SUBCOMMANDS) if (sub.startsWith(prefix)) out.add(sub);
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("migrate")) {
            String prefix = args[1].toLowerCase();
            for (String t : MIGRATE_TYPES) if (t.startsWith(prefix)) out.add(t);
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("who")) {
            String prefix = args[1].toLowerCase();
            Bukkit.getOnlinePlayers().forEach(p -> {
                if (p.getName().toLowerCase().startsWith(prefix)) out.add(p.getName());
            });
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset-season")) {
            String prefix = args[1].toLowerCase();
            for (String f : List.of("--dry-run", "--confirm")) if (f.startsWith(prefix)) out.add(f);
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            String prefix = args[1].toLowerCase();
            for (String f : List.of("me", "player")) if (f.startsWith(prefix)) out.add(f);
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("debug") && args[1].equalsIgnoreCase("player")) {
            String prefix = args[2].toLowerCase();
            Bukkit.getOnlinePlayers().forEach(p -> {
                if (p.getName().toLowerCase().startsWith(prefix)) out.add(p.getName());
            });
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("maths")) {
            String prefix = args[1].toLowerCase();
            for (String f : MATHS_OPS) if (f.startsWith(prefix)) out.add(f);
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("flatline")) {
            String prefix = args[1].toLowerCase();
            for (String f : List.of("me", "player")) if (f.startsWith(prefix)) out.add(f);
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("flatline") && args[1].equalsIgnoreCase("player")) {
            String prefix = args[2].toLowerCase();
            Bukkit.getOnlinePlayers().forEach(p -> {
                if (p.getName().toLowerCase().startsWith(prefix)) out.add(p.getName());
            });
            return out;
        }
        return out;
    }

    private void doStats(CommandSender sender) {
        sender.sendMessage(PFX + FEAT + "Stats");
        sender.sendMessage(PFX + MSG + "Persistent allowlist patterns: " + INFO + plugin.getPersistentPatterns().size());
        sender.sendMessage(PFX + MSG + "Shard chars: " + INFO + plugin.getShardChars());
        sender.sendMessage(PFX + MSG + "Player data: " + INFO + (plugin.getPlayerDataStore() != null ? "enabled" : "disabled"));

        File pluginDir = plugin.getDataFolder();
        sender.sendMessage(PFX + FEAT + "Storage on disk");
        emitFileStat(sender, "persistent.db", new File(pluginDir, "persistent.db"));
        emitFileStat(sender, "server.db", new File(pluginDir, "server.db"));
        emitFileStat(sender, "playerdata.db", new File(pluginDir, "playerdata.db"));

        File playerDir = new File(pluginDir, "player");
        long[] s = countDir(playerDir);
        sender.sendMessage(PFX + FEAT + "player/ " + INFO + s[0] + " files, " + humanBytes(s[1]));

        sender.sendMessage(PFX + FEAT + "Live metrics");
        long total = 0, failed = 0;
        for (SkStorageBase scope : plugin.getRegisteredScopes()) {
            String n = scope.getClass().getSimpleName().replace("Scope", "").toLowerCase();
            sender.sendMessage(PFX + FEAT + n + MSG + " writes: " + INFO + scope.writesTotal()
                + MSG + " (failed: " + ERR + scope.writesFailed() + MSG + ")");
            total += scope.writesTotal();
            failed += scope.writesFailed();
            if (scope instanceof PlayerScope ps) {
                sender.sendMessage(PFX + MSG + "Open file handles: " + INFO + ps.openFileCount());
            }
        }
        if (total > 0) {
            sender.sendMessage(PFX + MSG + "Total: " + INFO + total + MSG + " writes ("
                + ERR + String.format("%.2f%%", 100.0 * failed / total) + MSG + " failed)");
        }
    }

    private void emitFileStat(CommandSender sender, String label, File f) {
        if (!f.exists()) {
            sender.sendMessage(PFX + FEAT + label + " " + INFO + "(absent)");
            return;
        }
        sender.sendMessage(PFX + FEAT + label + " " + INFO + humanBytes(f.length()));
    }

    private long[] countDir(File dir) {
        if (!dir.exists()) return new long[]{0, 0};
        long count = 0, bytes = 0;
        try (Stream<Path> stream = Files.walk(dir.toPath())) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(p) && p.toString().endsWith(".db")) {
                    count++;
                    bytes += Files.size(p);
                }
            }
        } catch (IOException ignored) {}
        return new long[]{count, bytes};
    }

    private void doWho(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PFX + MSG + "Usage: " + INFO + "/skstorage who <player>");
            return;
        }
        PlayerDataStore store = plugin.getPlayerDataStore();
        if (store == null) {
            sender.sendMessage(PFX + ERR + "Player data tracking is disabled.");
            return;
        }
        String name = args[1];

        UUID uuid = store.lookupUuid(name);

        if (uuid == null) {
            List<PlayerDataStore.NameMatch> partials = store.searchByPartialName(name, 6);
            if (partials.isEmpty()) {
                try {
                    uuid = UUID.fromString(name);
                } catch (IllegalArgumentException ignored) {
                    sender.sendMessage(PFX + MSG + "No record matching " + INFO + name);
                    return;
                }
            } else if (partials.size() == 1) {
                uuid = partials.get(0).uuid();
                sender.sendMessage(PFX + MSG + "Resolved partial " + INFO + name + MSG + " to " + USR + partials.get(0).name());
            } else {
                sender.sendMessage(PFX + MSG + "Multiple matches for " + INFO + name);
                for (PlayerDataStore.NameMatch m : partials) {
                    sender.sendMessage(PFX + USR + m.name() + " " + INFO + "(" + m.uuid() + ")");
                }
                return;
            }
        }

        PlayerDataStore.PlayerRecord rec = store.get(uuid);
        if (rec == null) {
            sender.sendMessage(PFX + MSG + "No record for uuid " + INFO + uuid);
            return;
        }
        long now = System.currentTimeMillis();
        sender.sendMessage(PFX + MSG + "Player " + USR + rec.name());
        sender.sendMessage(PFX + FEAT + "uuid: " + INFO + rec.uuid());
        sender.sendMessage(PFX + FEAT + "first join: " + INFO + fmt.format(new Date(rec.firstJoin())) + " (" + ago(now - rec.firstJoin()) + " ago)");
        sender.sendMessage(PFX + FEAT + "last join: " + INFO + fmt.format(new Date(rec.lastJoin())) + " (" + ago(now - rec.lastJoin()) + " ago)");
        if (rec.lastQuit() != null) {
            sender.sendMessage(PFX + FEAT + "last quit: " + INFO + fmt.format(new Date(rec.lastQuit())) + " (" + ago(now - rec.lastQuit()) + " ago)");
        }
        sender.sendMessage(PFX + FEAT + "sessions: " + INFO + rec.totalSessions());
        sender.sendMessage(PFX + FEAT + "playtime: " + INFO + ago(rec.totalPlaytime()));
        if (rec.lastWorld() != null) {
            sender.sendMessage(PFX + FEAT + "last world: " + INFO + rec.lastWorld());
        }
        if (rec.lastIpHash() != null) {
            sender.sendMessage(PFX + FEAT + "last ip hash: " + INFO + rec.lastIpHash().substring(0, 16) + "...");
        }
    }

    private void doDebug(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PFX + MSG + "Usage: " + INFO + "/skstorage debug <me|player <name|uuid>>");
            return;
        }
        UUID uuid;
        String label;
        if (args[1].equalsIgnoreCase("me")) {
            if (!(sender instanceof org.bukkit.entity.Player p)) {
                sender.sendMessage(PFX + ERR + "debug me requires running as a player.");
                return;
            }
            uuid = p.getUniqueId();
            label = p.getName();
        } else if (args[1].equalsIgnoreCase("player") && args.length >= 3) {
            String target = args[2];
            try {
                uuid = UUID.fromString(target);
            } catch (IllegalArgumentException ex) {
                PlayerDataStore store = plugin.getPlayerDataStore();
                uuid = (store != null) ? store.lookupUuid(target) : null;
                if (uuid == null) {
                    sender.sendMessage(PFX + ERR + "No UUID found for " + INFO + target);
                    return;
                }
            }
            label = target;
        } else {
            sender.sendMessage(PFX + MSG + "Usage: " + INFO + "/skstorage debug <me|player <name|uuid>>");
            return;
        }

        File pluginDir = plugin.getDataFolder();
        String shard = uuid.toString().substring(0, Math.min(2, uuid.toString().length()));
        File dbFile = new File(pluginDir, "player/" + shard + "/" + uuid + ".db");

        sender.sendMessage(PFX + FEAT + "debug for " + USR + label + " " + INFO + "(" + uuid + ")");
        if (!dbFile.exists()) {
            sender.sendMessage(PFX + FEAT + "on-disk file: " + ERR + "absent " + INFO + "(" + dbFile.getAbsolutePath() + ")");
            sender.sendMessage(PFX + MSG + "No per-player data stored for this UUID.");
            return;
        }
        sender.sendMessage(PFX + FEAT + "on-disk file: " + INFO + humanBytes(dbFile.length()) + " (" + dbFile.getName() + ")");

        java.util.List<String> diskNames = new java.util.ArrayList<>();
        java.util.Map<String, String> diskTypes = new java.util.HashMap<>();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             java.sql.PreparedStatement ps = conn.prepareStatement(
                 "SELECT v.name, t.value FROM variables v LEFT JOIN type_dict t ON t.id=v.type_id");
             java.sql.ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                diskNames.add(rs.getString(1));
                diskTypes.put(rs.getString(1), rs.getString(2));
            }
        } catch (java.sql.SQLException ex) {
            sender.sendMessage(PFX + ERR + "Failed to read disk file: " + INFO + ex.getMessage());
            return;
        }
        sender.sendMessage(PFX + FEAT + "on-disk rows: " + INFO + diskNames.size());

        int hitCount = 0;
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String name : diskNames) {
            Object ramValue = SkriptReflect.getVariable(name);
            if (ramValue != null) hitCount++;
            else missing.add(name);
        }
        sender.sendMessage(PFX + FEAT + "in Skript RAM: " + INFO + hitCount + " / " + diskNames.size());

        if (hitCount == diskNames.size() && diskNames.size() > 0) {
            sender.sendMessage(PFX + "§a" + "All variables loaded into RAM (lazy-load fired).");
        } else if (hitCount == 0 && diskNames.size() > 0) {
            sender.sendMessage(PFX + ERR + "No variables in RAM. Player file not loaded yet.");
            sender.sendMessage(PFX + MSG + "Likely causes: player not online, or join event missed.");
        } else if (!missing.isEmpty()) {
            sender.sendMessage(PFX + FEAT + "Partial load. Missing in RAM:");
            for (String m : missing.subList(0, Math.min(5, missing.size()))) {
                sender.sendMessage(PFX + INFO + "- " + m);
            }
            if (missing.size() > 5) sender.sendMessage(PFX + INFO + "... and " + (missing.size() - 5) + " more");
        }

        if (!diskNames.isEmpty()) {
            sender.sendMessage(PFX + FEAT + "Sample (first 5)");
            for (int i = 0; i < Math.min(5, diskNames.size()); i++) {
                String n = diskNames.get(i);
                Object v = SkriptReflect.getVariable(n);
                String inRam = v == null ? ERR + "not in RAM" : "§a" + String.valueOf(v).substring(0, Math.min(40, String.valueOf(v).length()));
                sender.sendMessage(PFX + INFO + n.substring(0, Math.min(60, n.length()))
                    + " [" + diskTypes.get(n) + "] -> " + inRam);
            }
        }
    }

    private void doCleanup(CommandSender sender) {
        double thresholdKb = plugin.getAutoDiscardKb();
        if (thresholdKb <= 0) {
            sender.sendMessage(PFX + ERR + "auto_discard_on_leave_kb " + MSG + "is " + INFO + "0 " + MSG + "(disabled). Set a threshold in " + INFO + "config.yml " + MSG + "first.");
            return;
        }
        PlayerScope playerScope = null;
        for (var s : plugin.getRegisteredScopes()) {
            if (s instanceof PlayerScope ps) { playerScope = ps; break; }
        }
        if (playerScope == null) {
            sender.sendMessage(PFX + ERR + "PlayerScope is not registered.");
            return;
        }
        sender.sendMessage(PFX + ACT + "Sweeping " + MSG + "player files <= " + INFO + thresholdKb + " KB...");
        long t0 = System.currentTimeMillis();
        int deleted = playerScope.cleanupSmallFiles();
        long elapsed = System.currentTimeMillis() - t0;
        sender.sendMessage(PFX + ACT + "Deleted " + INFO + deleted + MSG + " file(s) in " + INFO + elapsed + "ms");
    }

    private void doFlatline(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PFX + MSG + "Usage: " + INFO + "/skstorage flatline <me|player <name|uuid>>");
            sender.sendMessage(PFX + MSG + "Closes the player's file handle and re-opens it. Tests the quit/rejoin lifecycle without disconnecting.");
            return;
        }
        UUID uuid;
        String label;
        if (args[1].equalsIgnoreCase("me")) {
            if (!(sender instanceof org.bukkit.entity.Player p)) {
                sender.sendMessage(PFX + ERR + "flatline me requires running as a player.");
                return;
            }
            uuid = p.getUniqueId();
            label = p.getName();
        } else if (args[1].equalsIgnoreCase("player") && args.length >= 3) {
            String target = args[2];
            try { uuid = UUID.fromString(target); }
            catch (IllegalArgumentException ex) {
                PlayerDataStore store = plugin.getPlayerDataStore();
                uuid = (store != null) ? store.lookupUuid(target) : null;
                if (uuid == null) {
                    sender.sendMessage(PFX + ERR + "No UUID found for " + INFO + target);
                    return;
                }
            }
            label = target;
        } else {
            sender.sendMessage(PFX + MSG + "Usage: " + INFO + "/skstorage flatline <me|player <name|uuid>>");
            return;
        }

        PlayerScope playerScope = null;
        for (var s : plugin.getRegisteredScopes()) {
            if (s instanceof PlayerScope ps) { playerScope = ps; break; }
        }
        if (playerScope == null) {
            sender.sendMessage(PFX + ERR + "PlayerScope is not registered (Skript databases config wrong?).");
            return;
        }

        sender.sendMessage(PFX + FEAT + "flatline cycle for " + USR + label);
        sender.sendMessage(PFX + ACT + "Closing " + MSG + "handle (commit + close)...");
        long t0 = System.currentTimeMillis();
        int loaded;
        try {
            loaded = playerScope.flatline(uuid);
        } catch (java.sql.SQLException ex) {
            sender.sendMessage(PFX + ERR + "flatline failed: " + INFO + ex.getMessage());
            return;
        }
        long elapsed = System.currentTimeMillis() - t0;
        if (loaded < 0) {
            sender.sendMessage(PFX + MSG + "No on-disk file for this UUID, nothing to reload.");
            return;
        }
        sender.sendMessage(PFX + ACT + "Reopened " + MSG + "and reloaded " + INFO + loaded + MSG + " variable(s) in " + INFO + elapsed + "ms");
        doDebug(sender, args);
    }

    private void doReload(CommandSender sender) {
        plugin.reloadConfig();
        plugin.reloadConfigPatterns();
        sender.sendMessage(PFX + ACT + "Reloaded " + INFO + "config.yml");
        sender.sendMessage(PFX + FEAT + "Persistent allowlist patterns: " + INFO + plugin.getPersistentPatterns().size());
        sender.sendMessage(PFX + MSG + "Note: Skript's " + INFO + "databases: " + MSG + "section is NOT reloaded (storage paths and types require a full server restart).");
    }

    private void doMigrate(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PFX + MSG + "Usage: " + INFO + "/skstorage migrate <sqlibrary|csv> <path> [--dry-run] [--i-have-backed-up]");
            return;
        }
        boolean dryRun = false;
        boolean haveBackup = false;
        StringBuilder pathBuilder = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--dry-run")) { dryRun = true; continue; }
            if (args[i].equalsIgnoreCase("--i-have-backed-up")) { haveBackup = true; continue; }
            if (pathBuilder.length() > 0) pathBuilder.append(' ');
            pathBuilder.append(args[i]);
        }
        File source = new File(pathBuilder.toString());
        if (!source.exists()) {
            sender.sendMessage(PFX + ERR + "Source not found: " + INFO + source);
            return;
        }
        if (!dryRun && !haveBackup) {
            sender.sendMessage(PFX + ERR + "LIVE migration refused " + MSG + "without " + INFO + "--i-have-backed-up " + MSG + "flag.");
            sender.sendMessage(PFX + MSG + "Backup the source file (and " + INFO + "plugins/SkStorage/" + MSG + ") before running. Re-run with both flags or use " + INFO + "--dry-run " + MSG + "to preview without writing.");
            return;
        }
        if (!dryRun) {
            int othersOnline = Bukkit.getOnlinePlayers().size();
            if (sender instanceof org.bukkit.entity.Player self
                    && Bukkit.getOnlinePlayers().contains(self)) {
                othersOnline--;
            }
            if (othersOnline > 0) {
                sender.sendMessage(PFX + ERR + "LIVE migration refused " + MSG + "with other players online.");
                sender.sendMessage(PFX + MSG + "Kick everyone except yourself before migrating to avoid data races.");
                sender.sendMessage(PFX + MSG + "Other players online: " + INFO + othersOnline);
                return;
            }
        }
        try {
            switch (args[1].toLowerCase()) {
                case "sqlibrary" -> new SqlibraryMigrator(plugin).migrate(source, sender, dryRun);
                case "csv" -> new CsvMigrator(plugin).migrate(source, sender, dryRun);
                default -> sender.sendMessage(PFX + ERR + "Unknown source type: " + INFO + args[1]);
            }
        } catch (Exception e) {
            sender.sendMessage(PFX + ERR + "Migration failed: " + INFO + e.getMessage());
            plugin.getLogger().severe("Migration failed: " + e);
            e.printStackTrace();
        }
    }

    private void doResetSeason(CommandSender sender, String[] args) {
        boolean dryRun = args.length > 1 && args[1].equalsIgnoreCase("--dry-run");
        boolean confirm = args.length > 1 && args[1].equalsIgnoreCase("--confirm");
        String suppliedToken = args.length > 2 ? args[2] : null;

        File pluginDir = plugin.getDataFolder();
        File serverDb = new File(pluginDir, "server.db");
        File playerDir = new File(pluginDir, "player");

        long serverBytes = serverDb.exists() ? serverDb.length() : 0;
        long[] playerStats = countDir(playerDir);
        long playerFiles = playerStats[0];
        long playerBytes = playerStats[1];

        String phase = dryRun ? INFO + "(dry-run)" : confirm ? ERR + "(LIVE, confirming)" : FEAT + "(prepare)";
        sender.sendMessage(PFX + FEAT + "Reset-season " + phase);
        sender.sendMessage(PFX + FEAT + "server.db: " + INFO + humanBytes(serverBytes));
        sender.sendMessage(PFX + FEAT + "player files: " + INFO + playerFiles + " files, " + humanBytes(playerBytes));
        sender.sendMessage(PFX + FEAT + "persistent.db / playerdata.db: " + "§a" + "kept " + INFO + "(lifetime data)");

        if (dryRun) {
            sender.sendMessage(PFX + INFO + "(dry-run; no files modified)");
            return;
        }

        if (!confirm) {
            String token = Long.toHexString(System.nanoTime() ^ ((long) (Math.random() * Long.MAX_VALUE))).substring(0, 8);
            pendingResetToken = token;
            pendingResetExpiresAt = System.currentTimeMillis() + 60_000;
            sender.sendMessage(PFX + ERR + "This will permanently delete the seasonal data above.");
            sender.sendMessage(PFX + MSG + "Persistent variables (allowlist) and " + INFO + "playerdata.db " + MSG + "are NOT touched.");
            sender.sendMessage(PFX + MSG + "To confirm, run within 60 seconds: " + INFO + "/skstorage reset-season --confirm " + token);
            return;
        }

        if (suppliedToken == null || pendingResetToken == null ||
            !pendingResetToken.equals(suppliedToken) ||
            System.currentTimeMillis() > pendingResetExpiresAt) {
            sender.sendMessage(PFX + ERR + "Token invalid or expired. " + MSG + "Re-run " + INFO + "/skstorage reset-season " + MSG + "to get a fresh one.");
            pendingResetToken = null;
            return;
        }
        pendingResetToken = null;

        sender.sendMessage(PFX + ACT + "Executing " + MSG + "season reset...");
        int playerDeleted = deletePlayerFiles(playerDir);
        boolean serverDeleted = deleteFile(serverDb);
        deleteFile(new File(pluginDir, "server.db-shm"));
        deleteFile(new File(pluginDir, "server.db-wal"));

        sender.sendMessage(PFX + ACT + "Deleted " + INFO + playerDeleted + MSG + " per-player file(s).");
        sender.sendMessage(PFX + ACT + "Deleted " + INFO + "server.db" + (serverDeleted ? "" : MSG + " (was already absent)"));
        sender.sendMessage(PFX + MSG + "Restart the server to recreate empty seasonal databases.");
        plugin.getLogger().info("Season reset executed by " + sender.getName() +
            ": " + playerDeleted + " player files + server.db deleted.");
    }

    private int deletePlayerFiles(File playerDir) {
        if (!playerDir.exists()) return 0;
        int count = 0;
        try (Stream<Path> stream = Files.walk(playerDir.toPath())) {
            for (Path p : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) {
                if (Files.isRegularFile(p)) {
                    try { Files.delete(p); count++; } catch (IOException ignored) {}
                } else if (Files.isDirectory(p) && !p.equals(playerDir.toPath())) {
                    try { Files.delete(p); } catch (IOException ignored) {}
                }
            }
        } catch (IOException ignored) {}
        return count;
    }

    private boolean deleteFile(File f) {
        return f.exists() && f.delete();
    }

    private String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String ago(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h";
        long days = hours / 24;
        return days + "d";
    }

    private void doMaths(CommandSender sender, String[] args) {
        if (args.length < 4 && !(args.length == 3 && args[1].equalsIgnoreCase("parse"))) {
            sender.sendMessage(PFX + MSG + "Usage: " + INFO + "/skstorage maths <" + String.join("|", MATHS_OPS) + "> <a> [<b>]");
            sender.sendMessage(PFX + MSG + "Operates on BigNum (signed int128, range ~1.7e38).");
            return;
        }
        String op = args[1].toLowerCase();
        dev.teacommontea.skstorage.bignum.BigNum a;
        dev.teacommontea.skstorage.bignum.BigNum b = null;
        try {
            a = dev.teacommontea.skstorage.bignum.BigNum.parse(args[2]);
            if (!op.equals("parse")) {
                b = dev.teacommontea.skstorage.bignum.BigNum.parse(args[3]);
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(PFX + ERR + "Bad input: " + INFO + e.getMessage());
            return;
        }
        long t0 = System.nanoTime();
        String result;
        try {
            result = switch (op) {
                case "add"      -> a.add(b).toString();
                case "subtract" -> a.subtract(b).toString();
                case "multiply" -> a.multiply(b).toString();
                case "divide"   -> a.divide(b).toString();
                case "mod"      -> a.mod(b).toString();
                case "pow"      -> a.pow((int) Math.min(Integer.MAX_VALUE,
                                          Math.max(0L, b.fitsInLong() ? b.toLongExact() : Integer.MAX_VALUE))).toString();
                case "compare"  -> {
                    int c = a.compareTo(b);
                    yield (c < 0 ? "less" : c > 0 ? "greater" : "equal") + " (" + c + ")";
                }
                case "parse"    -> a.toString();
                default -> {
                    sender.sendMessage(PFX + ERR + "Unknown op: " + INFO + op);
                    yield null;
                }
            };
        } catch (ArithmeticException e) {
            sender.sendMessage(PFX + ERR + "Arithmetic error: " + INFO + e.getMessage());
            return;
        }
        if (result == null) return;
        long elapsed = System.nanoTime() - t0;
        sender.sendMessage(PFX + "§a" + "= " + INFO + result);
        sender.sendMessage(PFX + INFO + "(" + elapsed + " ns)");
    }
}
