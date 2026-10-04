package dev.bedwars.core.ranking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EloCalculatorTest {

    private final EloCalculator elo = new EloCalculator(32);

    @Test
    void equalRatingsGiveEvenOdds() {
        assertThat(elo.expected(1000, 1000)).isEqualTo(0.5);
    }

    @Test
    void winAgainstEqualOpponentGainsHalfK() {
        assertThat(elo.delta(1000, 1000, 1.0)).isEqualTo(16);
    }

    @Test
    void lossAgainstEqualOpponentLosesHalfK() {
        assertThat(elo.delta(1000, 1000, 0.0)).isEqualTo(-16);
    }

    @Test
    void underdogGainsMoreThanFavourite() {
        int underdogGain = elo.delta(800, 1600, 1.0);
        int favouriteGain = elo.delta(1600, 800, 1.0);
        assertThat(underdogGain).isGreaterThan(favouriteGain);
    }

    @Test
    void ratingIsClampedAtBounds() {
        assertThat(elo.apply(10, -100)).isEqualTo(EloCalculator.MIN_RATING);
        assertThat(elo.apply(4990, 100)).isEqualTo(EloCalculator.MAX_RATING);
    }
}