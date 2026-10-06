package dev.bedwars.core.reporting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A game server must never sit behind a whitelist: it accepts the players the controller
 * routes to it, and an empty whitelist would kick every one of them. An operator who
 * really wants a whitelist can still ask to keep one.
 */
class WhitelistEnforcementTest {

    @Test
    void parsesValuesCaseInsensitively() {
        assertThat(WhitelistEnforcement.parse(" OFF ")).isEqualTo(WhitelistEnforcement.OFF);
        assertThat(WhitelistEnforcement.parse("true")).isEqualTo(WhitelistEnforcement.OFF);
        assertThat(WhitelistEnforcement.parse("LEAVE")).isEqualTo(WhitelistEnforcement.LEAVE);
        assertThat(WhitelistEnforcement.parse("false")).isEqualTo(WhitelistEnforcement.LEAVE);
    }

    @Test
    void defaultsToOffSoAQueueManagedServerNeverRejectsItsPlayers() {
        assertThat(WhitelistEnforcement.parse(null)).isEqualTo(WhitelistEnforcement.OFF);
        assertThat(WhitelistEnforcement.parse("")).isEqualTo(WhitelistEnforcement.OFF);
        assertThat(WhitelistEnforcement.parse("nonsense")).isEqualTo(WhitelistEnforcement.OFF);
    }

    /** The legacy spelling from earlier configs must keep working, not start rejecting players. */
    @Test
    void legacyAutoSpellingMeansOff() {
        assertThat(WhitelistEnforcement.parse("auto")).isEqualTo(WhitelistEnforcement.OFF);
        assertThat(WhitelistEnforcement.parse("AUTO")).isEqualTo(WhitelistEnforcement.OFF);
    }

    @Test
    void offDisablesAndLeaveKeepsTheOperatorsWhitelist() {
        assertThat(WhitelistEnforcement.shouldDisable(WhitelistEnforcement.OFF)).isTrue();
        assertThat(WhitelistEnforcement.shouldDisable(WhitelistEnforcement.LEAVE)).isFalse();
    }
}
