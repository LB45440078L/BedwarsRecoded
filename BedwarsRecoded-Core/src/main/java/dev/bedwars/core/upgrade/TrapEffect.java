package dev.bedwars.core.upgrade;

/**
 * The effects a triggered trap applies, as a platform-neutral description the
 * adapter translates into potion effects.
 */
public record TrapEffect(
        boolean blindIntruder,
        boolean slowIntruder,
        boolean poisonIntruder,
        boolean miningFatigueIntruder,
        boolean buffDefenders,
        boolean alertTeam,
        int durationSeconds
) {

    public static TrapEffect reveal() {
        return new TrapEffect(true, true, false, false, false, true, 8);
    }

    public static TrapEffect counterOffensive() {
        return new TrapEffect(false, false, false, false, true, false, 10);
    }

    public static TrapEffect alarm() {
        return new TrapEffect(false, false, false, false, false, true, 0);
    }

    public static TrapEffect minerFatigue() {
        return new TrapEffect(false, false, false, true, false, true, 10);
    }

    public static TrapEffect blindnessPoison() {
        return new TrapEffect(true, false, true, false, false, true, 8);
    }
}