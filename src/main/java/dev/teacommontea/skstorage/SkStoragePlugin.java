package dev.teacommontea.skstorage;

import ch.njol.skript.Skript;
import ch.njol.skript.variables.Variables;
import dev.teacommontea.skstorage.scope.SkStorageBase;
import dev.teacommontea.skstorage.util.ConfigUpdater;
import org.bukkit.scheduler.BukkitTask;
import dev.teacommontea.skstorage.command.SkStorageCommand;
import dev.teacommontea.skstorage.playerdata.ExprPlayerData;
import dev.teacommontea.skstorage.playerdata.PlayerDataListener;
import dev.teacommontea.skstorage.playerdata.PlayerDataStore;
import dev.teacommontea.skstorage.scope.PersistentScope;
import dev.teacommontea.skstorage.scope.PlayerScope;
import dev.teacommontea.skstorage.scope.ServerScope;
import dev.teacommontea.skstorage.util.IpHasher;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class SkStoragePlugin extends JavaPlugin {

    private static SkStoragePlugin instance;

    private List<Pattern> persistentPatterns = List.of();

    private Pattern persistentCombined = Pattern.compile("(?!)");
    private int shardChars = 2;
    private boolean playerdataEnabled = true;
    private boolean trackIpHash = false;
    private boolean serverLegacyFallthrough = false;
    private double autoDiscardKb = 0;
    private int backgroundLoadFilesPerTick = 1;
    private boolean preloadPlayers = false;

    private PlayerDataStore playerDataStore;
    private IpHasher ipHasher;

    private final java.util.List<SkStorageBase> registeredScopes = new java.util.concurrent.CopyOnWriteArrayList<>();
    @org.jetbrains.annotations.Nullable private BukkitTask flushTask;

    public void registerScope(SkStorageBase scope) {
        registeredScopes.add(scope);
    }

    public java.util.List<SkStorageBase> getRegisteredScopes() {
        return registeredScopes;
    }

    @Override
    public void onLoad() {
        instance = this;
        saveDefaultConfig();

        java.util.List<String> addedKeys = ConfigUpdater.sync(this);
        if (!addedKeys.isEmpty()) {
            reloadConfig();
            getLogger().info("Config updater added " + addedKeys.size() +
                " missing key(s): " + String.join(", ", addedKeys));
        }
        loadConfigPatterns();

        Variables.registerStorage(PersistentScope.class, "skstorage-persistent");
        Variables.registerStorage(PlayerScope.class,     "skstorage-player");
        Variables.registerStorage(ServerScope.class,     "skstorage-server");

        getLogger().info("Registered Skript storage types: skstorage-persistent, skstorage-player, skstorage-server");
    }

    @Override
    public void onEnable() {
        if (playerdataEnabled) {
            try {
                ipHasher = new IpHasher(getDataFolder().toPath().resolve("secret.key"), trackIpHash);
                playerDataStore = new PlayerDataStore(
                    getDataFolder().toPath().resolve("playerdata.db").toString());
                getServer().getPluginManager().registerEvents(
                    new PlayerDataListener(playerDataStore, ipHasher), this);
                ExprPlayerData.setStore(playerDataStore);

                if (Skript.isAcceptRegistrations()) {
                    ExprPlayerData.register();
                    getLogger().info("Registered `playerdata` Skript expression");
                } else {
                    getLogger().warning("Skript stopped accepting registrations early; `playerdata` expression unavailable. " +
                        "Auto-tracking and /skstorage who still work.");
                }

                getLogger().info("Player data tracking enabled (track_last_ip_hash=" + trackIpHash + ")");
            } catch (Exception e) {
                getLogger().severe("Failed to initialize player data: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            getLogger().info("Player data tracking disabled in config.");
        }

        try {
            Skript.registerAddon(this);
            dev.teacommontea.skstorage.bignum.BigNumModule.register();
            getLogger().info("Registered BigNum type (signed int128, exact undecillion-range arithmetic)");
        } catch (Throwable t) {
            getLogger().severe("Failed to register BigNum: " + t);
            t.printStackTrace();
        }

        SkStorageCommand cmd = new SkStorageCommand(this);
        getCommand("skstorage").setExecutor(cmd);
        getCommand("skstorage").setTabCompleter(cmd);

        try {
            dev.teacommontea.skstorage.plotsquared.PlotSquaredHook.registerIfEnabled();
        } catch (NoClassDefFoundError ignored) {

        }

        flushTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (SkStorageBase s : registeredScopes) {
                try { s.flush(); } catch (Throwable t) {
                    getLogger().warning("Flush failed for " + s + ": " + t.getMessage());
                }
            }
        }, 4L, 4L);

        getLogger().info("SkStorage enabled. Persistent allowlist patterns: " + persistentPatterns.size());
    }

    @Override
    public void onDisable() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }

        for (SkStorageBase s : registeredScopes) {
            try { s.flush(); } catch (Throwable ignored) {}
        }
        if (playerDataStore != null) {
            playerDataStore.close();
        }
    }

    public void reloadConfigPatterns() {
        loadConfigPatterns();
    }

    private void loadConfigPatterns() {
        FileConfiguration cfg = getConfig();
        List<String> globs = cfg.getStringList("persistent.patterns");
        List<Pattern> compiled = new ArrayList<>(globs.size());
        for (String g : globs) {
            compiled.add(Pattern.compile("^" + globToRegex(g) + "$"));
        }
        persistentPatterns = List.copyOf(compiled);

        if (globs.isEmpty()) {
            persistentCombined = Pattern.compile("(?!)");
        } else {
            StringBuilder sb = new StringBuilder("^(");
            for (int i = 0; i < globs.size(); i++) {
                if (i > 0) sb.append('|');
                sb.append(globToRegex(globs.get(i)));
            }
            sb.append(")$");
            persistentCombined = Pattern.compile(sb.toString());
        }
        shardChars = cfg.getInt("player.shard-chars", 2);
        playerdataEnabled = cfg.getBoolean("playerdata.enabled", true);
        trackIpHash = cfg.getBoolean("playerdata.track_last_ip_hash", false);
        serverLegacyFallthrough = cfg.getBoolean("server.legacy_fallthrough", false);
        autoDiscardKb = cfg.getDouble("player.auto_discard_on_leave_kb", 0);
        backgroundLoadFilesPerTick = cfg.getInt("player.background_load_files_per_tick", 1);
        preloadPlayers = cfg.getBoolean("player.preload_players", false);
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() * 2);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static SkStoragePlugin get() { return instance; }
    public List<Pattern> getPersistentPatterns() { return persistentPatterns; }
    public Pattern getPersistentCombinedPattern() { return persistentCombined; }
    public int getShardChars() { return shardChars; }
    public PlayerDataStore getPlayerDataStore() { return playerDataStore; }
    public boolean isServerLegacyFallthrough() { return serverLegacyFallthrough; }
    public double getAutoDiscardKb() { return autoDiscardKb; }
    public int getBackgroundLoadFilesPerTick() { return backgroundLoadFilesPerTick; }
    public boolean isPreloadPlayers() { return preloadPlayers; }
}
