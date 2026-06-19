package net.teacommontea.skstorage.litebans;
import net.teacommontea.skstorage.util.Log;

import litebans.api.Database;
import litebans.api.Entry;
import litebans.api.Events;
import net.teacommontea.skstorage.SkStoragePlugin;
import net.teacommontea.skstorage.route.SkStorageRouter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class LiteBansHook {

    private LiteBansHook() {}

    public static void registerIfEnabled() {
        SkStoragePlugin plugin = SkStoragePlugin.get();
        if (!plugin.getConfig().getBoolean("litebans.forget_on_ban", false)) return;
        if (plugin.getServer().getPluginManager().getPlugin("LiteBans") == null) {
            plugin.getLogger().info("litebans.forget_on_ban is true but LiteBans is not loaded; skipping hook.");
            return;
        }
        try {
            Events.get().register(new Listener());
            plugin.getLogger().info("LiteBans hook installed (forget_on_ban=true).");
        } catch (Throwable t) {
            Log.soft("Failed to register LiteBans hook: " +
                t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static final class Listener extends Events.Listener {
        @Override
        public void entryAdded(Entry entry) {
            if (entry == null) return;
            if (!"ban".equals(entry.getType())) return;
            if (!entry.isPermanent()) return;

            List<String> uuids = new ArrayList<>();
            if (entry.isIpban()) {

                try {
                    Collection<String> users = Database.get().getUsersByIP(entry.getIp());
                    if (users != null) uuids.addAll(users);
                } catch (Throwable t) {
                    Log.soft(
                        "LiteBans: could not resolve IP ban users: " + t.getMessage());
                }
            }
            String single = entry.getUuid();
            if (single != null && !single.isEmpty() && !uuids.contains(single)) {
                uuids.add(single);
            }
            if (uuids.isEmpty()) return;

            SkStoragePlugin plugin = SkStoragePlugin.get();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                SkStorageRouter router = plugin.getRouter();
                if (router == null) return;
                int total = 0;
                for (String uuid : uuids) {
                    total += router.purgeUuidEverywhere(uuid);
                }
                if (total > 0) {
                    plugin.getLogger().info("forget_on_ban: purged data for " +
                        uuids.size() + " account(s) (" + total + " unit(s) removed).");
                }
            });
        }
    }
}
