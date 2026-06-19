package net.teacommontea.skstorage.route;

import net.teacommontea.skstorage.SkStoragePlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.logging.Logger;

public final class TableConfig {

    private TableConfig() {}

    private static final java.util.Set<String> RESERVED = java.util.Set.of(
        "player", "litebans", "plotsquared", "playerdata",
        "experimental", "skript", "convert_csv");

    public static boolean build(SkStoragePlugin plugin, SkStorageRouter router) {
        Logger log = plugin.getLogger();
        ConfigurationSection cfg = plugin.getConfig();
        File dataDir = plugin.getDataFolder();
        int shardChars = cfg.getInt("player.shard-chars", 2);

        if (cfg.isSet("database") && !cfg.getBoolean("database", true)) {
            log.warning("Top-level database: false; persistence is disabled.");
            return false;
        }

        net.teacommontea.skstorage.util.SqliteSetup.Options sqliteOptions =
            new net.teacommontea.skstorage.util.SqliteSetup.Options(
                cfg.getBoolean("experimental.disable_wal", false),
                cfg.getBoolean("experimental.skip_file_locking", false));

        if (cfg.getBoolean("simple_mode", false)) {
            Table only = new Table("default", false, true, Integer.MIN_VALUE,
                new File(dataDir, "default.db"), shardChars, sqliteOptions);
            router.setDefaultTable(only);
            log.info("Simple mode ON: all variables route to default.db (tables ignored).");
            return true;
        }

        int declOrder = 0;
        int tableCount = 0;
        for (String key : cfg.getKeys(false)) {
            if (RESERVED.contains(key)) continue;
            ConfigurationSection sec = cfg.getConfigurationSection(key);
            if (sec == null) continue;

            if (sec.getBoolean("example", false)) continue;
            if (!sec.isSet("database")) continue;

            if (!sec.getBoolean("database", false)) {

                continue;
            }

            String rawPattern = sec.getString("patterns");
            if (rawPattern == null || rawPattern.isEmpty()) {
                log.warning("Table '" + key + "' has database: true but no patterns; skipping.");
                continue;
            }

            VariablePattern pattern = VariablePattern.parse(rawPattern);
            if (pattern == null) {
                log.warning("Table '" + key + "' pattern failed to parse: " + rawPattern + "; skipping.");
                continue;
            }

            boolean split = sec.getBoolean("split_file_by_uuid", false);
            boolean permanent = sec.getBoolean("permanent", false);
            int index = sec.getInt("index", 0);

            if (split && !pattern.isUuidKeyed()) {
                log.warning("Table '" + key + "' sets split_file_by_uuid but its pattern has no UUID; " +
                    "files cannot be sharded by UUID. Treating as single-file.");
                split = false;
            }

            File location = split
                ? new File(dataDir, key)
                : new File(dataDir, key + ".db");
            Table table = new Table(key, split, permanent, index, location, shardChars, sqliteOptions);
            router.addRoute(table, pattern, index, declOrder++);
            tableCount++;
            log.info("Registered table '" + key + "' (index=" + index +
                ", split_file_by_uuid=" + split + ", permanent=" + permanent + ")");
        }

        Table def = new Table("default", false, true, Integer.MIN_VALUE,
            new File(dataDir, "default.db"), shardChars, sqliteOptions);
        router.setDefaultTable(def);

        log.info("Routing built: " + tableCount + " table(s) + default.db");
        return true;
    }
}
