package dev.bedwars.spigot.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code dragon:} block decides how much of a match the sudden-death dragons wreck.
 * These tests pin the parsing and, more importantly, the clamps: a nonsensical value
 * must never become a dragon that eats the whole map or one the player cannot reach.
 */
class DragonConfigTest {

    private static PluginConfig.DragonConfig parse(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (Exception e) {
            throw new IllegalStateException("test yaml did not load", e);
        }
        return PluginConfig.dragon(config);
    }

    @Test
    void absentBlockYieldsTheDefaults() {
        PluginConfig.DragonConfig d = parse("");

        assertThat(d).isEqualTo(PluginConfig.DragonConfig.DEFAULTS);
        assertThat(d.enabled()).isTrue();
        assertThat(d.basePerMatch()).isEqualTo(1);
    }

    @Test
    void everyKnobIsReadFromTheDragonBlock() {
        PluginConfig.DragonConfig d = parse("""
                dragon:
                  enabled: false
                  base-per-match: 3
                  health: 120.0
                  spawn-height: 12.0
                  knockback: 4.5
                  knockback-radius: 20.0
                  knockback-interval-ticks: 5
                  destroy-radius: 6
                  destroy-depth: 8
                  destroy-interval-ticks: 7
                  damage: 1.5
                """);

        assertThat(d.enabled()).isFalse();
        assertThat(d.basePerMatch()).isEqualTo(3);
        assertThat(d.health()).isEqualTo(120.0);
        assertThat(d.spawnHeight()).isEqualTo(12.0);
        assertThat(d.knockback()).isEqualTo(4.5);
        assertThat(d.knockbackRadius()).isEqualTo(20.0);
        assertThat(d.knockbackIntervalTicks()).isEqualTo(5);
        assertThat(d.destroyRadius()).isEqualTo(6);
        assertThat(d.destroyDepth()).isEqualTo(8);
        assertThat(d.destroyIntervalTicks()).isEqualTo(7);
        assertThat(d.damage()).isEqualTo(1.5);
    }

    @Test
    void nonsensicalValuesAreClampedRatherThanObeyed() {
        PluginConfig.DragonConfig d = parse("""
                dragon:
                  base-per-match: -5
                  health: 0.0
                  spawn-height: -3.0
                  knockback: -1.0
                  knockback-radius: 0.0
                  knockback-interval-ticks: 0
                  destroy-radius: -2
                  destroy-depth: 0
                  destroy-interval-ticks: 0
                  damage: -1.0
                """);

        assertThat(d.basePerMatch()).isZero();
        assertThat(d.health()).isGreaterThanOrEqualTo(1.0);
        assertThat(d.spawnHeight()).isGreaterThanOrEqualTo(0.0);
        assertThat(d.knockback()).isGreaterThanOrEqualTo(0.0);
        assertThat(d.knockbackRadius()).isGreaterThanOrEqualTo(1.0);
        assertThat(d.knockbackIntervalTicks()).isGreaterThanOrEqualTo(1);
        assertThat(d.destroyRadius()).isGreaterThanOrEqualTo(0);
        assertThat(d.destroyDepth()).isGreaterThanOrEqualTo(1);
        assertThat(d.destroyIntervalTicks()).isGreaterThanOrEqualTo(1);
        assertThat(d.damage()).isGreaterThanOrEqualTo(0.0);
    }
}
