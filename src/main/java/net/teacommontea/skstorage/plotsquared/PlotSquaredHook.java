package net.teacommontea.skstorage.plotsquared;
import net.teacommontea.skstorage.util.Log;

import com.google.common.eventbus.Subscribe;
import com.plotsquared.core.PlotSquared;
import com.plotsquared.core.events.post.PostPlotDeleteEvent;
import com.plotsquared.core.plot.Plot;
import com.plotsquared.core.plot.PlotId;
import net.teacommontea.skstorage.SkStoragePlugin;
import net.teacommontea.skstorage.route.SkStorageRouter;

public final class PlotSquaredHook {

    public static void registerIfEnabled() {
        SkStoragePlugin plugin = SkStoragePlugin.get();
        if (!plugin.getConfig().getBoolean("plotsquared.cleanup_on_delete", false)) return;
        if (plugin.getServer().getPluginManager().getPlugin("PlotSquared") == null) {
            plugin.getLogger().info("plotsquared.cleanup_on_delete is true but PlotSquared is not loaded; skipping hook.");
            return;
        }
        try {
            PlotSquared.get().getEventDispatcher().registerListener(new PlotSquaredHook());
            plugin.getLogger().info("PlotSquared hook installed (cleanup_on_delete=true).");
        } catch (Throwable t) {
            Log.soft("Failed to register PlotSquared hook: " +
                t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @Subscribe
    public void onPostPlotDelete(PostPlotDeleteEvent event) {
        Plot plot = event.getPlot();
        if (plot == null) return;
        String world = plot.getWorldName();
        PlotId id = plot.getId();
        String plotIdStr = (id != null) ? id.toString() : null;

        SkStoragePlugin plugin = SkStoragePlugin.get();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            SkStorageRouter router = plugin.getRouter();
            if (router == null) return;
            int deleted = 0;
            if (world != null && !world.isEmpty()) {
                deleted += router.purgeByGlobEverywhere("*" + world + "*");
            }
            if (plotIdStr != null && !plotIdStr.isEmpty()) {
                deleted += router.purgeByGlobEverywhere("*" + plotIdStr + "*");
            }
            if (deleted > 0) {
                plugin.getLogger().info(String.format(
                    "PlotSquared cleanup: deleted %d row(s) for plot %s in world %s",
                    deleted, plotIdStr, world));
            }
        });
    }
}
