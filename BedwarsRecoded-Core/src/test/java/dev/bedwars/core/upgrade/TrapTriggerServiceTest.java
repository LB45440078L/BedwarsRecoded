package dev.bedwars.core.upgrade;

import dev.bedwars.core.domain.Bed;
import dev.bedwars.core.domain.Team;
import dev.bedwars.core.domain.TeamColor;
import dev.bedwars.core.domain.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TrapTriggerServiceTest {

    private Team teamWithTrap(TrapType trap) {
        Team team = new Team("red", TeamColor.RED, new Bed("red", new Vec3(0, 64, 0), 3.0), 2);
        team.traps().add(trap);
        return team;
    }

    @Test
    void firesWhenEnemyEntersBase() {
        Team team = teamWithTrap(TrapType.ALARM);
        TrapTriggerService service = new TrapTriggerService(10.0, 5_000L);
        Optional<TrapType> fired = service.checkTrigger(team, new Vec3(2, 64, 2), 1_000L);
        assertThat(fired).contains(TrapType.ALARM);
        assertThat(team.traps().size()).isZero();
    }

    @Test
    void doesNotFireOutsideBase() {
        Team team = teamWithTrap(TrapType.ALARM);
        TrapTriggerService service = new TrapTriggerService(10.0, 5_000L);
        assertThat(service.checkTrigger(team, new Vec3(100, 64, 100), 1_000L)).isEmpty();
        assertThat(team.traps().size()).isEqualTo(1);
    }

    @Test
    void cooldownPreventsImmediateRefire() {
        Team team = teamWithTrap(TrapType.ALARM);
        team.traps().add(TrapType.MINER_FATIGUE);
        TrapTriggerService service = new TrapTriggerService(10.0, 5_000L);
        assertThat(service.checkTrigger(team, new Vec3(1, 64, 1), 1_000L)).contains(TrapType.ALARM);
        // within cooldown: nothing fires
        assertThat(service.checkTrigger(team, new Vec3(1, 64, 1), 2_000L)).isEmpty();
        // after cooldown: the next trap fires
        assertThat(service.checkTrigger(team, new Vec3(1, 64, 1), 7_000L)).contains(TrapType.MINER_FATIGUE);
    }

    @Test
    void emptyQueueNeverFires() {
        Team team = new Team("red", TeamColor.RED, new Bed("red", new Vec3(0, 64, 0), 3.0), 2);
        TrapTriggerService service = new TrapTriggerService(10.0, 5_000L);
        assertThat(service.checkTrigger(team, new Vec3(0, 64, 0), 1_000L)).isEmpty();
    }
}