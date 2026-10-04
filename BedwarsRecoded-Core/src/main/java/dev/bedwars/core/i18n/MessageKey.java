package dev.bedwars.core.i18n;

/**
 * Every user-facing string the game emits. Templates may contain {@code {0}},
 * {@code {1}} placeholders filled by {@link MessageCatalog#format}.
 */
public enum MessageKey {
    GAME_COUNTDOWN,
    GAME_STARTED,
    GAME_ENDED,
    TEAM_ELIMINATED,
    BED_DESTROYED,
    BED_DESTROYED_BY,
    PLAYER_KILLED,
    PLAYER_FINAL_KILLED,
    PLAYER_RESPAWN,
    YOU_ELIMINATED,
    SUDDEN_DEATH,
    TRAP_TRIGGERED,
    UPGRADE_PURCHASED,
    TRAP_PURCHASED,
    SHOP_INSUFFICIENT_FUNDS,
    SHOP_ALREADY_OWNED,
    SHOP_PURCHASED,
    QUICK_BUY_UPDATED,
    LANGUAGE_CHANGED,
    SPECTATOR_JOINED,
    VOID_KILL,
    CANNOT_PLACE_NEAR_BED,
    NOT_ENOUGH_PLAYERS
}