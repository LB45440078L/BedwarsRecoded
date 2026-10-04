package dev.bedwars.spigot.listener;

import dev.bedwars.core.persistence.QuickBuyRepository;
import dev.bedwars.core.shop.QuickBuyStore;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Syncs a player's quick-buy layout with persistence: loaded on join, saved on
 * quit. The store is a concurrent map, so the async callbacks never touch Bukkit
 * state and are safe to run off the main thread.
 */
public final class QuickBuySyncListener implements Listener {

    private final QuickBuyRepository repository;
    private final QuickBuyStore store;

    public QuickBuySyncListener(QuickBuyRepository repository, QuickBuyStore store) {
        this.repository = repository;
        this.store = store;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        repository.load(event.getPlayer().getUniqueId())
                .thenAccept(items -> store.setAll(event.getPlayer().getUniqueId(), items));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        repository.save(event.getPlayer().getUniqueId(), store.items(event.getPlayer().getUniqueId()));
    }
}