package dev.bedwars.core.shop;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player quick-buy preferences. Kept in sync with the lobby through the
 * controller when persistence is wired; in a pod it lives for the match.
 */
public final class QuickBuyStore {

    private static final int MAX_SLOTS = 21;

    private final java.util.Map<UUID, Set<String>> favourites = new ConcurrentHashMap<>();

    public List<String> items(UUID player) {
        return List.copyOf(favourites.getOrDefault(player, Set.of()));
    }

    /** Toggles an item in the quick-buy bar; returns the resulting list. */
    public List<String> toggle(UUID player, String itemId) {
        Set<String> set = favourites.computeIfAbsent(player, key -> new LinkedHashSet<>());
        if (!set.remove(itemId)) {
            if (set.size() >= MAX_SLOTS) {
                return List.copyOf(set);
            }
            set.add(itemId);
        }
        return List.copyOf(set);
    }

    public boolean contains(UUID player, String itemId) {
        return favourites.getOrDefault(player, Set.of()).contains(itemId);
    }

    /** Replaces a player's layout wholesale (used when loading from persistence). */
    public void setAll(UUID player, List<String> items) {
        Set<String> set = java.util.Collections.synchronizedSet(new LinkedHashSet<>(items));
        favourites.put(player, set);
    }

    public void clear(UUID player) {
        favourites.remove(player);
    }
}