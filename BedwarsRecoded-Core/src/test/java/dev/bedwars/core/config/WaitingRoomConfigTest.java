package dev.bedwars.core.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The waiting room and minimum players in YAML — including the key the original plugin used
 * ({@code map-lobby-spawn}), so an operator porting their Glacier config does not have to
 * rename anything.
 */
class WaitingRoomConfigTest {

    private static ArenaDefinition load(String groupYaml) {
        String yaml = """
                group:
                """ + groupYaml + """
                teams:
                  - id: red
                    bed: { x: 1, y: 64, z: 1 }
                    spawn: { x: 1, y: 66, z: 1 }
                generators: []
                shop: { id: default, display-name: Shop, categories: [] }
                """;
        return new ArenaConfigLoader().load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void lobbySpawnAndMinPlayersAreRead() {
        ArenaDefinition arena = load("""
                  id: solo
                  team-count: 2
                  players-per-team: 2
                  lobby-spawn: { x: 0.0, y: 118.05, z: 0.0 }
                  min-players: 2
                """);
        assertThat(arena.group().hasWaitingRoom()).isTrue();
        assertThat(arena.group().waitingRoom().orElseThrow().y()).isEqualTo(118.05);
        assertThat(arena.group().effectiveMinPlayers()).isEqualTo(2);
    }

    @Test
    void theOriginalWildcardKeyNameIsAcceptedToo() {
        ArenaDefinition arena = load("""
                  id: solo
                  map-lobby-spawn: { x: 12.0, y: 90.0, z: -3.0 }
                """);
        assertThat(arena.group().waitingRoom().orElseThrow().x()).isEqualTo(12.0);
        assertThat(arena.group().waitingRoom().orElseThrow().z()).isEqualTo(-3.0);
    }

    @Test
    void anArenaWithoutAWaitingRoomSaysSoRatherThanInventingOne() {
        ArenaDefinition arena = load("""
                  id: solo
                """);
        assertThat(arena.group().hasWaitingRoom()).isFalse();
    }

    @Test
    void anIncompleteCoordinateIsNotAWatingRoom() {
        ArenaDefinition arena = load("""
                  id: solo
                  lobby-spawn: { x: 0.0, y: 118.05 }
                """);
        assertThat(arena.group().hasWaitingRoom()).isFalse();
    }
}
