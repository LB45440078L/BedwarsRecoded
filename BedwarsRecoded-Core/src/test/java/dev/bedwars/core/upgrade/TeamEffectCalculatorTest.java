package dev.bedwars.core.upgrade;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TeamEffectCalculatorTest {

    @Test
    void noUpgradesGiveNoEffects() {
        EffectSet effects = TeamEffectCalculator.forTeam(new TeamUpgrades());
        assertThat(effects).isEqualTo(EffectSet.NONE);
        assertThat(effects.hasHaste()).isFalse();
        assertThat(effects.hasRegen()).isFalse();
    }

    @Test
    void levelsMapToEffectAmplitudes() {
        TeamUpgrades upgrades = new TeamUpgrades();
        upgrades.setLevel(UpgradeType.SHARPNESS, 2);
        upgrades.setLevel(UpgradeType.PROTECTION, 3);
        upgrades.setLevel(UpgradeType.MANIAC_MINER, 2);
        upgrades.setLevel(UpgradeType.HEAL_POOL, 1);
        upgrades.setLevel(UpgradeType.DRAGON_BUFF, 1);

        EffectSet effects = TeamEffectCalculator.forTeam(upgrades);
        assertThat(effects.sharpness()).isEqualTo(2);
        assertThat(effects.protection()).isEqualTo(3);
        assertThat(effects.hasteAmplifier()).isEqualTo(1);   // Haste II
        assertThat(effects.regenAmplifier()).isEqualTo(0);   // Regeneration I
        assertThat(effects.dragonBuffs()).isEqualTo(1);
    }

    @Test
    void trapEffectsAreDefinedForEveryTrap() {
        for (TrapType trap : TrapType.values()) {
            assertThat(trap.effects()).isNotNull();
        }
        assertThat(TrapType.BLINDNESS_POISON.effects().poisonIntruder()).isTrue();
        assertThat(TrapType.MINER_FATIGUE.effects().miningFatigueIntruder()).isTrue();
        assertThat(TrapType.COUNTER_OFFENSIVE.effects().buffDefenders()).isTrue();
        assertThat(TrapType.ALARM.effects().alertTeam()).isTrue();
    }
}