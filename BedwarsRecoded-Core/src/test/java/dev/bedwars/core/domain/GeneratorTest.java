package dev.bedwars.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorTest {

    @Test
    void ironTierOneSpawnsEvery1500Millis() {
        Generator gen = new Generator("g", GeneratorType.IRON, GeneratorTier.I, new Vec3(0, 64, 0), 0L);
        assertThat(gen.intervalMillis()).isEqualTo(1500L);
        assertThat(gen.tick(1000L)).isZero();
        assertThat(gen.tick(1500L)).isEqualTo(1);
        assertThat(gen.tick(1500L)).isZero();
        assertThat(gen.tick(3000L)).isEqualTo(1);
    }

    @Test
    void higherTierShortensInterval() {
        Generator gen = new Generator("g", GeneratorType.IRON, GeneratorTier.I, new Vec3(0, 64, 0), 0L);
        gen.setTier(GeneratorTier.MAX, 0L);
        assertThat(gen.intervalMillis()).isEqualTo(375L); // 1500 * 0.25
        assertThat(gen.itemsPerSpawn()).isEqualTo(2);
    }

    @Test
    void longStallCatchUpIsBounded() {
        Generator gen = new Generator("g", GeneratorType.IRON, GeneratorTier.I, new Vec3(0, 64, 0), 0L);
        int cycles = gen.tick(1_000_000L);
        assertThat(cycles).isLessThanOrEqualTo(64);
        assertThat(gen.tick(1_000_001L)).isZero();
    }
}