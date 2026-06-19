package net.teacommontea.skstorage.command;

import net.teacommontea.skstorage.SkStoragePlugin;
import net.teacommontea.skstorage.playerdata.PlayerDataStore;
import net.teacommontea.skstorage.route.SkStorageRouter;
import net.teacommontea.skstorage.route.Table;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public final class SkStorageCommand implements CommandExecutor, TabCompleter {

    public static final String PFX  = "§2[§aSkStorage§2] ";
    public static final String MSG  = "§f";
    public static final String FEAT = "§e";
    public static final String ERR  = "§c";
    public static final String ACT  = "§b";
    public static final String INFO = "§7§o";

    private static final List<String> SUBS = List.of(
        "stats", "who", "clear", "reload", "migrate",
        "flatline", "kill", "alive", "merge", "route");

    private static final java.util.Set<String> ALWAYS_ON = java.util.Set.of("reload", "alive");

    private final SkStoragePlugin plugin;

    @Nullable private String clearToken;
    @Nullable private String mergeToken;
    @Nullable private String mergePayload;

    public SkStorageCommand(SkStoragePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(PFX + MSG + "Usage: " + INFO + "/skstorage <" + String.join("|", SUBS) + ">");
            return true;
        }
        String sub = args[0].toLowerCase();
        if (!SUBS.contains(sub)) {
            sender.sendMessage(PFX + ERR + "Unknown subcommand: " + INFO + args[0]);
            return true;
        }
        if (!allowed(sender, sub)) return true;

        switch (sub) {
            case "stats"    -> stats(sender);
            case "who"      -> who(sender, args);
            case "clear"    -> clear(sender, args);
            case "reload"   -> reload(sender);
            case "migrate"  -> migrate(sender, args);
            case "flatline" -> flatline(sender, args);
            case "kill"     -> kill(sender);
            case "alive"    -> alive(sender);
            case "merge"    -> merge(sender, args);
            case "route"    -> route(sender, args);
        }
        return true;
    }

    private boolean allowed(CommandSender sender, String sub) {
        String cap = capitalize(sub);
        boolean privileged = sender.hasPermission("SkStorage." + cap + "CommandPrivileged")
            || sender.hasPermission("SkStorage.AdminCommandsPrivileged");
        boolean base = sender.hasPermission("SkStorage." + cap + "Command")
            || sender.hasPermission("SkStorage.AdminCommands");

        if (!privileged && !base) {
            sender.sendMessage(PFX + ERR + "You don't have permission to use " + INFO + sub + ERR + ".");
            return false;
        }

        if (privileged) return true;

        if (ALWAYS_ON.contains(sub)) return true;

        String mode = plugin.getConfig().getString("commands." + sub, "enabled").toLowerCase();
        switch (mode) {
            case "disabled":
                sender.sendMessage(PFX + ERR + "The " + INFO + sub + ERR + " command is disabled in config.yml.");
                return false;
            case "console_only":
                if (!(sender instanceof ConsoleCommandSender)) {
                    sender.sendMessage(PFX + ERR + "The " + INFO + sub + ERR + " command is console-only.");
                    return false;
                }
                return true;
            default:
                return true;
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private SkStorageRouter router(CommandSender sender) {
        SkStorageRouter r = plugin.getRouter();
        if (r == null) {
            sender.sendMessage(PFX + ERR + "Router not loaded. Is the 'skstorage' database registered in Skript?");
        }
        return r;
    }

    private void stats(CommandSender sender) {
        SkStorageRouter r = router(sender);
        if (r == null) return;
        sender.sendMessage(PFX + FEAT + "Storage strain" + (r.isKilled() ? ERR + " (KILLED)" : "") + MSG + ":");
        long totalBytes = 0, totalRows = 0, totalWrites = 0;
        for (Table t : r.allTables()) {
            long bytes = t.diskBytes();
            long rows = t.rowCount();
            long writes = t.writesTotal();
            totalBytes += bytes;
            if (rows > 0) totalRows += rows;
            totalWrites += writes;
            sender.sendMessage(PFX + MSG + " " + INFO + t.name() + MSG +
                ": " + rows + " rows, " + human(bytes) + ", " +
                t.openHandles() + " handle(s), " + writes + " writes");
        }
        sender.sendMessage(PFX + FEAT + "Total" + MSG + ": " + totalRows + " rows, " +
            human(totalBytes) + ", " + totalWrites + " writes this session");
    }

    private void who(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PFX + MSG + "Usage: " + INFO + "/skstorage who <player>");
            return;
        }
        PlayerDataStore store = plugin.getPlayerDataStore();
        if (store == null) {
            sender.sendMessage(PFX + ERR + "Player data tracking is disabled.");
            return;
        }
        String query = args[1];
        UUID uuid = store.lookupUuid(query);
        if (uuid == null) {
            List<PlayerDataStore.NameMatch> matches = store.searchByPartialName(query, 5);
            if (matches.isEmpty()) {
                sender.sendMessage(PFX + ERR + "No tracked player matching " + INFO + query);
                return;
            }
            if (matches.size() > 1) {
                sender.sendMessage(PFX + FEAT + "Multiple matches:");
                for (PlayerDataStore.NameMatch m : matches) {
                    sender.sendMessage(PFX + MSG + " " + INFO + m.name() + MSG + " (" + m.uuid() + ")");
                }
                return;
            }
            uuid = matches.get(0).uuid();
        }
        PlayerDataStore.PlayerRecord rec = store.get(uuid);
        if (rec == null) {
            sender.sendMessage(PFX + ERR + "No record for " + INFO + uuid);
            return;
        }
        sender.sendMessage(PFX + FEAT + "Player: " + INFO + rec.name() + MSG + " (" + rec.uuid() + ")");
        sender.sendMessage(PFX + MSG + " First join: " + INFO + new Date(rec.firstJoin()));
        sender.sendMessage(PFX + MSG + " Last join:  " + INFO + new Date(rec.lastJoin()));
        if (rec.lastQuit() != null) sender.sendMessage(PFX + MSG + " Last quit:  " + INFO + new Date(rec.lastQuit()));
        sender.sendMessage(PFX + MSG + " Playtime:   " + INFO + (rec.totalPlaytime() / 60000) + " min, " +
            rec.totalSessions() + " sessions");
        if (rec.lastWorld() != null) sender.sendMessage(PFX + MSG + " Last world: " + INFO + rec.lastWorld());
        if (rec.lastIpHash() != null) sender.sendMessage(PFX + MSG + " IP hash:    " + INFO + rec.lastIpHash());
    }

    private void clear(CommandSender sender, String[] args) {
        SkStorageRouter r = router(sender);
        if (r == null) return;
        if (args.length >= 2 && args[1].equals(clearToken) && clearToken != null) {
            int wiped = 0;
            for (Table t : r.allTables()) {
                if (!t.isPermanent()) { t.clear(); wiped++; }
            }
            clearToken = null;
            sender.sendMessage(PFX + ACT + "Cleared " + INFO + wiped + MSG + " non-permanent table(s). " +
                "Permanent tables and default.db were spared.");
            return;
        }
        clearToken = shortToken();
        sender.sendMessage(PFX + ERR + "This wipes every non-permanent table. To confirm:");
        sender.sendMessage(PFX + INFO + "/skstorage clear " + clearToken);
    }

    private void reload(CommandSender sender) {
        plugin.reloadConfig();
        sender.sendMessage(PFX + ACT + "Reloaded " + INFO + "config.yml" + MSG +
            ". Table/database changes still require a full restart.");
    }

    private void migrate(CommandSender sender, String[] args) {
        if (args.length < 3 || !"sqlibrary".equalsIgnoreCase(args[1])) {
            sender.sendMessage(PFX + MSG + "Usage: " + INFO +
                "/skstorage migrate sqlibrary <path> [--i-have-backed-up]");
            return;
        }
        java.io.File source = new java.io.File(args[2]);
        if (!source.exists()) {
            sender.sendMessage(PFX + ERR + "Source not found: " + INFO + source.getAbsolutePath());
            return;
        }
        boolean live = args.length >= 4 && "--i-have-backed-up".equalsIgnoreCase(args[3]);
        try {
            new net.teacommontea.skstorage.migrate.SqlibraryMigrator(plugin).migrate(source, sender, !live);
        } catch (java.sql.SQLException e) {
            sender.sendMessage(PFX + ERR + "Migration error: " + INFO + e.getMessage());
        }
    }

    private void flatline(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PFX + MSG + "Usage: " + INFO + "/skstorage flatline <player|uuid>");
            return;
        }
        SkStorageRouter r = router(sender);
        if (r == null) return;
        UUID uuid = resolveUuid(args[1]);
        if (uuid == null) {
            sender.sendMessage(PFX + ERR + "Could not resolve " + INFO + args[1] + ERR + " to a UUID.");
            return;
        }
        int totalReloaded = 0;
        boolean anyShard = false;
        for (Table t : r.allTables()) {
            if (!t.isSplitFileByUuid()) continue;
            int n = t.reloadUuid(uuid.toString(),
                (name, value) -> net.teacommontea.skstorage.util.SkriptReflect.variableLoaded(name, value, r),
                true);
            if (n >= 0) { anyShard = true; totalReloaded += n; }
        }
        if (!anyShard) {
            sender.sendMessage(PFX + ERR + "No sharded table holds data for " + INFO + uuid);
            return;
        }
        sender.sendMessage(PFX + ACT + "Flatline: reloaded " + INFO + totalReloaded +
            MSG + " variable(s) for " + INFO + uuid + MSG + " from disk.");
    }

    private void kill(CommandSender sender) {
        SkStorageRouter r = router(sender);
        if (r == null) return;
        if (r.isKilled()) {
            sender.sendMessage(PFX + ERR + "Database is already killed. Use " + INFO + "/skstorage alive" + ERR + ".");
            return;
        }
        r.kill(plugin.getDataFolder());
        sender.sendMessage(PFX + ACT + "Database killed. Real tables flushed and closed; writes now go to " +
            INFO + "_disabled.db" + MSG + " and are discarded on revival. Safe to reload other plugins.");
    }

    private void alive(CommandSender sender) {
        SkStorageRouter r = router(sender);
        if (r == null) return;
        if (!r.isKilled()) {
            sender.sendMessage(PFX + ERR + "Database is not killed.");
            return;
        }
        boolean del = plugin.getConfig().getBoolean("automatically_delete_disabled_db_on_startup", true);
        r.revive(plugin.getDataFolder(), del);
        sender.sendMessage(PFX + ACT + "Database revived. Real tables reopened" +
            (del ? "; _disabled.db deleted." : "; _disabled.db kept (toggle off)."));
    }

    private void merge(CommandSender sender, String[] args) {
        SkStorageRouter r = router(sender);
        if (r == null) return;

        if (args.length == 2 && args[1].equals(mergeToken) && mergePayload != null) {
            String[] p = mergePayload.split(" ");
            int moved = r.mergeUuid(p[0], p[1]);
            sender.sendMessage(PFX + ACT + "Merged " + INFO + moved + MSG + " variable(s) from " +
                INFO + p[0] + MSG + " into " + INFO + p[1] + MSG + "; source purged.");
            mergeToken = null;
            mergePayload = null;
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(PFX + MSG + "Usage: " + INFO + "/skstorage merge <from> <to>");
            return;
        }
        UUID from = resolveUuid(args[1]);
        UUID to = resolveUuid(args[2]);
        if (from == null || to == null) {
            sender.sendMessage(PFX + ERR + "Could not resolve both players to UUIDs.");
            return;
        }
        if (from.equals(to)) {
            sender.sendMessage(PFX + ERR + "Cannot merge a player into themselves.");
            return;
        }
        mergePayload = from + " " + to;
        mergeToken = shortToken();
        sender.sendMessage(PFX + ERR + "Merge pours " + INFO + from + ERR + " into " + INFO + to + ERR +
            " (target wins collisions), then purges the source. To confirm:");
        sender.sendMessage(PFX + INFO + "/skstorage merge " + mergeToken);
    }

    private void route(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PFX + MSG + "Usage: " + INFO + "/skstorage route <variable name>");
            return;
        }
        SkStorageRouter r = router(sender);
        if (r == null) return;
        String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        SkStorageRouter.Explain ex = r.routeExplain(name);
        sender.sendMessage(PFX + FEAT + "Route for " + INFO + name + MSG + ":");
        sender.sendMessage(PFX + MSG + " -> table " + INFO + ex.table());
        if (ex.pattern() != null) {
            sender.sendMessage(PFX + MSG + " matched pattern " + INFO + ex.pattern() +
                MSG + " (index " + ex.index() + ")");
        }
        if (ex.note() != null) {
            sender.sendMessage(PFX + INFO + " " + ex.note());
        }
    }

    @Nullable
    private UUID resolveUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {

            PlayerDataStore store = plugin.getPlayerDataStore();
            if (store != null) {
                UUID u = store.lookupUuid(s);
                if (u != null) return u;
            }
            org.bukkit.OfflinePlayer op = plugin.getServer().getOfflinePlayerIfCached(s);
            return op != null ? op.getUniqueId() : null;
        }
    }

    private static String shortToken() {

        long n = System.nanoTime();
        return Long.toHexString(n & 0xFFFFFFL);
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    @Nullable
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : SUBS) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("migrate")) {
            return List.of("sqlibrary");
        }
        return List.of();
    }
}
