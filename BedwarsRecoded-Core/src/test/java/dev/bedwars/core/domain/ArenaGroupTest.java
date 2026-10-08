package dev.bedwars.core.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The waiting room and the minimum-player rule the countdown obeys. */
class ArenaGroupTest {

    private static ArenaGroup group(int teamCount, int playersPerTeam, Optional<Vec3> waitingRoom, int minPlayers) {
        return new ArenaGroup("solo", teamCount, playersPerTeam, 15, 300, 0.0, 30.0, 3.0,
                List.of(GeneratorType.IRON), waitingRoom, minPlayers, true);
    }

    @Test
    void theLegacyShapeStillWorksAndHasNoWaitingRoom() {
        ArenaGroup legacy = new ArenaGroup("solo", 2, 2, 15, 300, 0.0, 30.0, 3.0,
                List.of(GeneratorType.IRON));
        assertThat(legacy.hasWaitingRoom()).isFalse();
        assertThat(legacy.waitingRoom()).isEmpty();
    }

    @Test
    void theWaitingRoomIsCarriedWhenConfigured() {
        Vec3 room = new Vec3(0.0, 118.05, 0.0);
        ArenaGroup group = group(2, 2, Optional.of(room), 2);
        assertThat(group.hasWaitingRoom()).isTrue();
        assertThat(group.waitingRoom()).contains(room);
    }

    @Test
    void theCountdownNeedsOnePlayerPerTeamByDefault() {
        assertThat(group(4, 2, Optional.empty(), 0).effectiveMinPlayers()).isEqualTo(4);
    }

    @Test
    void anExplicitMinimumWinsAndIsClampedToCapacity() {
        assertThat(group(2, 2, Optional.empty(), 3).effectiveMinPlayers()).isEqualTo(3);
        assertThat(group(2, 2, Optional.empty(), 99).effectiveMinPlayers()).isEqualTo(4);
        assertThat(group(2, 2, Optional.empty(), -1).effectiveMinPlayers()).isEqualTo(2);
    }
}
