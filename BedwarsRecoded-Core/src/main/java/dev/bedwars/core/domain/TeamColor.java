package dev.bedwars.core.domain;

/**
 * Canonical team colours. {@code rgb} is the integer packed RGB used for
 * scoreboard prefixes and wool/bed blocks; the adapter maps it to platform types.
 */
public enum TeamColor {
    RED(0xFF5555),
    BLUE(0x5555FF),
    GREEN(0x55FF55),
    YELLOW(0xFFFF55),
    AQUA(0x55FFFF),
    WHITE(0xFFFFFF),
    PINK(0xFF55FF),
    GRAY(0xAAAAAA);

    private final int rgb;

    TeamColor(int rgb) {
        this.rgb = rgb;
    }

    public int rgb() {
        return rgb;
    }

    /** Minecraft legacy colour code (used for chat/scoreboard prefixes). */
    public char legacyCode() {
        return switch (this) {
            case RED -> 'c';
            case BLUE -> '9';
            case GREEN -> 'a';
            case YELLOW -> 'e';
            case AQUA -> 'b';
            case WHITE -> 'f';
            case PINK -> 'd';
            case GRAY -> '7';
        };
    }
}