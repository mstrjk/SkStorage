package net.teacommontea.skstorage;
import net.teacommontea.skstorage.util.Log;

import ch.njol.skript.Skript;
import ch.njol.skript.variables.Variables;
import net.teacommontea.skstorage.bignum.BigNumModule;
import net.teacommontea.skstorage.command.SkStorageCommand;
import net.teacommontea.skstorage.playerdata.ExprPlayerData;
import net.teacommontea.skstorage.playerdata.PlayerDataListener;
import net.teacommontea.skstorage.playerdata.PlayerDataStore;
import net.teacommontea.skstorage.route.SkStorageRouter;
import net.teacommontea.skstorage.util.IpHasher;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

public final class SkStoragePlugin extends JavaPlugin {

    private static SkStoragePlugin instance;

    @Nullable private SkStorageRouter router;
    @Nullable private PlayerDataStore playerDataStore;
    @Nullable private IpHasher ipHasher;
    @Nullable private BukkitTask flushTask;

    public static SkStoragePlugin get() {
        return instance;
    }

    @Override
    public void onLoad() {
        instance = this;
        saveDefaultConfig();

        java.util.List<String> added = net.teacommontea.skstorage.util.ConfigUpdater.sync(this);
        if (!added.isEmpty()) {
            reloadConfig();
            getLogger().info("Config updater appended " + added.size() +
                " missing block(s): " + String.join(", ", added));
        }

        Variables.registerStorage(SkStorageRouter.class, "skstorage");
        getLogger().info("Registered Skript storage type: skstorage");
    }

    @Override
    public void onEnable() {
        boolean playerdataEnabled = getConfig().getBoolean("playerdata.enabled", true);
        if (playerdataEnabled) {
            try {
                boolean trackIpHash = getConfig().getBoolean("playerdata.track_last_ip_hash", false);
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
                    Log.soft("Skript stopped accepting registrations early; " +
                        "`playerdata` expression unavailable. Auto-tracking and /skstorage who still work.");
                }
                getLogger().info("Player data tracking enabled (track_last_ip_hash=" + trackIpHash + ")");
            } catch (Exception e) {
                Log.hard("Failed to initialize player data", e);
            }
        } else {
            getLogger().info("Player data tracking disabled in config.");
        }

        try {
            Skript.registerAddon(this);
            BigNumModule.register();
            getLogger().info("Registered BigNum type (signed int128)");
        } catch (Throwable th) {
            Log.hard("Failed to register BigNum", th);
        }

        boolean useBigDecimal = getConfig().getBoolean("experimental.use_bigdecimal", false);
        boolean skipMath = getConfig().getBoolean("skript.skip_math", false);
        if (useBigDecimal && !skipMath) {
            Log.hard("experimental.use_bigdecimal is true but skript.skip_math is false. " +
                "BigDecimal needs skip_math to avoid silent double demotion. NOT installing BigDecimal math.");
        } else if (useBigDecimal) {
            net.teacommontea.skstorage.bignum.SkipMath.install(getLogger());
        }

        SkStorageCommand cmd = new SkStorageCommand(this);
        getCommand("skstorage").setExecutor(cmd);
        getCommand("skstorage").setTabCompleter(cmd);

        try {
            net.teacommontea.skstorage.plotsquared.PlotSquaredHook.registerIfEnabled();
        } catch (NoClassDefFoundError ignored) {

        }
        try {
            net.teacommontea.skstorage.litebans.LiteBansHook.registerIfEnabled();
        } catch (NoClassDefFoundError ignored) {

        }

        flushTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            SkStorageRouter r = router;
            if (r != null) {
                try {
                    r.flushAll();
                } catch (Throwable th) {
                    Log.soft("Flush failed: " + th.getMessage());
                }
            }
        }, 4L, 4L);

        if (getConfig().getBoolean("convert_csv.enabled", false)) {
            new org.bukkit.scheduler.BukkitRunnable() {
                int waited = 0;
                @Override
                public void run() {
                    if (router != null) {
                        net.teacommontea.skstorage.migrate.CsvConverter.runIfEnabled(SkStoragePlugin.this);
                        cancel();
                    } else if (++waited > 200) {
                        Log.soft("convert_csv: router never came up; skipping conversion.");
                        cancel();
                    }
                }
            }.runTaskTimer(this, 1L, 1L);
        }

        if (getConfig().getBoolean("experimental.threaded_file_io", false)) {

            getLogger().info("experimental.threaded_file_io: I/O is already off-thread " +
                "(Skript write thread + async flush); no extra I/O thread added.");
        }

        getLogger().info("SkStorage enabled.");
    }

    @Override
    public void onDisable() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        if (router != null) {
            router.flushAll();
        }
        if (playerDataStore != null) {
            playerDataStore.close();
        }
    }

    public void setRouter(SkStorageRouter router) {
        this.router = router;
    }

    @Nullable
    public SkStorageRouter getRouter() {
        return router;
    }

    @Nullable
    public PlayerDataStore getPlayerDataStore() {
        return playerDataStore;
    }
}
