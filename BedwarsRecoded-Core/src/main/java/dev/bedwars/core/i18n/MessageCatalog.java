package dev.bedwars.core.i18n;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Holds the available languages and formats messages. English is always present
 * as the fallback; additional locales are registered from config/resources.
 */
public final class MessageCatalog {

    private final Map<String, Language> languages = new LinkedHashMap<>();
    private final Language fallback;

    public MessageCatalog() {
        this.fallback = english();
        languages.put(fallback.code(), fallback);
    }

    public void register(Language language) {
        languages.put(language.code(), language);
    }

    public Optional<Language> byCode(String code) {
        return Optional.ofNullable(languages.get(code));
    }

    public Language fallback() {
        return fallback;
    }

    public java.util.Set<String> codes() {
        return languages.keySet();
    }

    /** Formats {@code key} in {@code code} (or English if unknown), filling placeholders. */
    public String format(String code, MessageKey key, Object... args) {
        Language language = languages.getOrDefault(code, fallback);
        String template = language.messages().containsKey(key)
                ? language.message(key)
                : fallback.message(key);
        return fill(template, args);
    }

    static String fill(String template, Object... args) {
        String result = template;
        for (int i = 0; i < args.length; i++) {
            result = result.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return result;
    }

    private static Language english() {
        Map<MessageKey, String> m = new EnumMap<>(MessageKey.class);
        m.put(MessageKey.GAME_COUNTDOWN, "Game starting in {0} seconds!");
        m.put(MessageKey.GAME_STARTED, "The game has started. Protect your bed!");
        m.put(MessageKey.GAME_ENDED, "Game over. Winner: {0}");
        m.put(MessageKey.TEAM_ELIMINATED, "Team {0} has been eliminated!");
        m.put(MessageKey.BED_DESTROYED, "Your bed was destroyed by {0}!");
        m.put(MessageKey.BED_DESTROYED_BY, "{0}'s bed was destroyed by {1}.");
        m.put(MessageKey.PLAYER_KILLED, "{0} was killed by {1}.");
        m.put(MessageKey.PLAYER_FINAL_KILLED, "{0} was final-killed by {1}.");
        m.put(MessageKey.PLAYER_RESPAWN, "You will respawn in {0} seconds.");
        m.put(MessageKey.YOU_ELIMINATED, "You have been eliminated. You are now a spectator.");
        m.put(MessageKey.SUDDEN_DEATH, "Sudden death! All beds have been destroyed.");
        m.put(MessageKey.TRAP_TRIGGERED, "Your team trap {0} was triggered!");
        m.put(MessageKey.UPGRADE_PURCHASED, "Purchased upgrade {0} (level {1}).");
        m.put(MessageKey.TRAP_PURCHASED, "Purchased trap {0}.");
        m.put(MessageKey.SHOP_INSUFFICIENT_FUNDS, "You cannot afford that.");
        m.put(MessageKey.SHOP_ALREADY_OWNED, "You already own that.");
        m.put(MessageKey.SHOP_PURCHASED, "Purchased {0}.");
        m.put(MessageKey.QUICK_BUY_UPDATED, "Quick buy updated.");
        m.put(MessageKey.LANGUAGE_CHANGED, "Language set to {0}.");
        m.put(MessageKey.SPECTATOR_JOINED, "{0} is now spectating.");
        m.put(MessageKey.VOID_KILL, "{0} fell into the void.");
        m.put(MessageKey.CANNOT_PLACE_NEAR_BED, "You cannot place blocks that close to a bed.");
        m.put(MessageKey.NOT_ENOUGH_PLAYERS, "Not enough players to start.");
        return new Language("en", "English", m);
    }
}