package dev.bedwars.core.ranking;

/**
 * Team-aware ELO. Documented formula so it can be reviewed and tuned:
 *
 * <pre>
 *   teamRating   = mean(rating of every player on the team)
 *   expected     = 1 / (1 + 10^((opponentRating - teamRating) / 400))
 *   result       = 1 (win) | 0.5 (draw) | 0 (loss)
 *   delta        = round(K * (result - expected))
 *   newRating    = clamp(rating + delta, MIN, MAX)
 * </pre>
 *
 * <p>{@code K} is configurable (default 32). Ratings are floored at
 * {@link #MIN_RATING} so a losing streak never goes negative.
 */
public final class EloCalculator {

    public static final int MIN_RATING = 0;
    public static final int MAX_RATING = 5000;

    private final int k;

    public EloCalculator(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("K must be positive");
        }
        this.k = k;
    }

    /** Win probability of {@code teamRating} against {@code opponentRating}, in [0,1]. */
    public double expected(double teamRating, double opponentRating) {
        return 1.0 / (1.0 + Math.pow(10.0, (opponentRating - teamRating) / 400.0));
    }

    /**
     * Rating change for a team.
     *
     * @param teamRating     mean rating of the team
     * @param opponentRating mean rating of the opposing team
     * @param result         1.0 win, 0.5 draw, 0.0 loss
     */
    public int delta(double teamRating, double opponentRating, double result) {
        return (int) Math.round(k * (result - expected(teamRating, opponentRating)));
    }

    public int apply(int currentRating, int delta) {
        return Math.clamp(currentRating + delta, MIN_RATING, MAX_RATING);
    }
}