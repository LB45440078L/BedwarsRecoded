package dev.bedwars.core.reporting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A game pod must never be behind a whitelist (nobody would be on it, so everyone
 * would be kicked); a standalone server's whitelist must never be touched behind the
 * operator's back.
 */
class WhitelistEnforcementTest {

    @Test
    void parsesValuesCaseInsensitively() {
        assertThat(WhitelistEnforcement.parse("auto")).isEqualTo(WhitelistEnforcement.AUTO);
        assertThat(WhitelistEnforcement.parse(" OFF ")).isEqualTo(WhitelistEnforcement.OFF);
        assertThat(WhitelistEnforcement.parse("true")).isEqualTo(WhitelistEnforcement.OFF);
        assertThat(WhitelistEnforcement.parse("LEAVE")).isEqualTo(WhitelistEnforcement.LEAVE);
        assertThat(WhitelistEnforcement.parse("false")).isEqualTo(WhitelistEnforcement.LEAVE);
    }

    @Test
    void defaultsToAuto() {
        assertThat(WhitelistEnforcement.parse(null)).isEqualTo(WhitelistEnforcement.AUTO);
        assertThat(WhitelistEnforcement.parse("")).isEqualTo(WhitelistEnforcement.AUTO);
        assertThat(WhitelistEnforcement.parse("nonsense")).isEqualTo(WhitelistEnforcement.AUTO);
    }

    @Test
    void autoDisablesOnAPodAndLeavesAStandaloneServerAlone() {
        assertThat(WhitelistEnforcement.shouldDisable(WhitelistEnforcement.AUTO, DeploymentMode.POD)).isTrue();
        assertThat(WhitelistEnforcement.shouldDisable(WhitelistEnforcement.AUTO, DeploymentMode.STANDALONE))
                .isFalse();
    }

    @Test
    void explicitValuesWinOverTheMode() {
        assertThat(WhitelistEnforcement.shouldDisable(WhitelistEnforcement.OFF, DeploymentMode.STANDALONE)).isTrue();
        assertThat(WhitelistEnforcement.shouldDisable(WhitelistEnforcement.LEAVE, DeploymentMode.POD)).isFalse();
    }

    @Test
    void autoNeverDisablesOnAnUnresolvedMode() {
        // AUTO is resolved before use; if it ever leaked through, do not touch anything.
        assertThat(WhitelistEnforcement.shouldDisable(WhitelistEnforcement.AUTO, DeploymentMode.AUTO)).isFalse();
    }
}