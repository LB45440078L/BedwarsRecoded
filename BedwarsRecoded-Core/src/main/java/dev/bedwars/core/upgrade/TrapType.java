package dev.bedwars.core.upgrade;

/** A purchasable team trap. */
public enum TrapType {
    IT_IS_A_TRAP("It's a Trap!", 1),
    COUNTER_OFFENSIVE("Counter-Offensive Trap", 1),
    ALARM("Alarm Trap", 1),
    MINER_FATIGUE("Miner Fatigue Trap", 1),
    BLINDNESS_POISON("Blindness & Poison Trap", 1);

    private final String displayName;
    private final int baseCostDiamonds;

    TrapType(String displayName, int baseCostDiamonds) {
        this.displayName = displayName;
        this.baseCostDiamonds = baseCostDiamonds;
    }

    public String displayName() {
        return displayName;
    }

    /** The in-world effect this trap applies when triggered. */
    public TrapEffect effects() {
        return switch (this) {
            case IT_IS_A_TRAP -> TrapEffect.reveal();
            case COUNTER_OFFENSIVE -> TrapEffect.counterOffensive();
            case ALARM -> TrapEffect.alarm();
            case MINER_FATIGUE -> TrapEffect.minerFatigue();
            case BLINDNESS_POISON -> TrapEffect.blindnessPoison();
        };
    }

    /**
     * Cost scales with how many traps the team already owns (classic Bedwars:
     * 1, 2, 4 diamonds for the 1st, 2nd, 3rd trap).
     */
    public int costDiamonds(int alreadyOwned) {
        return baseCostDiamonds << Math.min(alreadyOwned, 2);
    }
}