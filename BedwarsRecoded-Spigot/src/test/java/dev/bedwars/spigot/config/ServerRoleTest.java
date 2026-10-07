package dev.bedwars.spigot.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The role decides whether a process registers with the controller as a match host.
 * Getting this wrong is not a cosmetic bug: a lobby that reports capacity is handed
 * players as if it could host a game, so the fallback for anything unrecognised must
 * be GAME — the historical, always-safe behaviour.
 */
class ServerRoleTest {

    @Test
    void parsesTheTwoRealRoles() {
        assertThat(ServerRole.parse("GAME")).isEqualTo(ServerRole.GAME);
        assertThat(ServerRole.parse("LOBBY")).isEqualTo(ServerRole.LOBBY);
    }

    @Test
    void isCaseAndWhitespaceInsensitive() {
        assertThat(ServerRole.parse("  lobby ")).isEqualTo(ServerRole.LOBBY);
        assertThat(ServerRole.parse("Game")).isEqualTo(ServerRole.GAME);
    }

    @Test
    void recognisesCommonSynonymsForALobby() {
        assertThat(ServerRole.parse("HUB")).isEqualTo(ServerRole.LOBBY);
        assertThat(ServerRole.parse("proxy_lobby")).isEqualTo(ServerRole.LOBBY);
    }

    @Test
    void anythingUnknownIsAGameServer() {
        assertThat(ServerRole.parse(null)).isEqualTo(ServerRole.GAME);
        assertThat(ServerRole.parse("")).isEqualTo(ServerRole.GAME);
        assertThat(ServerRole.parse("   ")).isEqualTo(ServerRole.GAME);
        assertThat(ServerRole.parse("POD")).isEqualTo(ServerRole.GAME);
        assertThat(ServerRole.parse("nonsense")).isEqualTo(ServerRole.GAME);
    }

    @Test
    void isLobbyMatchesTheEnum() {
        assertThat(ServerRole.LOBBY.isLobby()).isTrue();
        assertThat(ServerRole.GAME.isLobby()).isFalse();
    }
}
