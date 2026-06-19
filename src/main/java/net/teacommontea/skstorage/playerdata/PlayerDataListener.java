package net.teacommontea.skstorage.playerdata;

import net.teacommontea.skstorage.util.IpHasher;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerDataListener implements Listener {

    private final PlayerDataStore store;
    private final IpHasher ipHasher;
    private final Map<UUID, Long> sessionStarts = new HashMap<>();

    public PlayerDataListener(PlayerDataStore store, IpHasher ipHasher) {
        this.store = store;
        this.ipHasher = ipHasher;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        sessionStarts.put(p.getUniqueId(), now);

        String ipHash = null;
        if (ipHasher.isEnabled() && p.getAddress() != null) {
            ipHash = ipHasher.hash(p.getAddress().getAddress().getHostAddress());
        }
        store.recordJoin(p.getUniqueId(), p.getName(), now, ipHash);

        try {
            com.destroystokyo.paper.profile.PlayerProfile profile = p.getPlayerProfile();
            for (com.destroystokyo.paper.profile.ProfileProperty prop : profile.getProperties()) {
                if ("textures".equalsIgnoreCase(prop.getName())) {
                    store.cacheHeadTextures(p.getUniqueId(), prop.getValue(), prop.getSignature());
                    break;
                }
            }
        } catch (Throwable ignored) {

        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long start = sessionStarts.remove(p.getUniqueId());
        long sessionMs = (start != null) ? Math.max(0, now - start) : 0;
        String world = (p.getWorld() != null) ? p.getWorld().getName() : null;
        store.recordQuit(p.getUniqueId(), now, sessionMs, world);
    }
}
