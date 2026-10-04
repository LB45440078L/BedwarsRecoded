package dev.bedwars.core.i18n;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player language preference. Kept off the {@code Game} aggregate because a
 * player's language outlives any single match (it is a lobby/persistent concern),
 * but scoped to this pod for the duration of a match.
 */
public final class LanguageService {

    private final MessageCatalog catalog;
    private final Map<UUID, String> playerLanguages = new ConcurrentHashMap<>();

    public LanguageService(MessageCatalog catalog) {
        this.catalog = catalog;
    }

    public MessageCatalog catalog() {
        return catalog;
    }

    public String languageOf(UUID player) {
        return playerLanguages.getOrDefault(player, catalog.fallback().code());
    }

    /** Sets the player's language; returns false if the code is unknown. */
    public boolean setLanguage(UUID player, String code) {
        if (catalog.byCode(code).isEmpty()) {
            return false;
        }
        playerLanguages.put(player, code);
        return true;
    }

    public String render(UUID player, MessageKey key, Object... args) {
        return catalog.format(languageOf(player), key, args);
    }
}