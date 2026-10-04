package dev.bedwars.core.upgrade;

/** Turns a team's purchased upgrade levels into a concrete {@link EffectSet}. */
public final class TeamEffectCalculator {

    private TeamEffectCalculator() {
    }

    public static EffectSet forTeam(TeamUpgrades upgrades) {
        int sharpness = upgrades.levelOf(UpgradeType.SHARPNESS);
        int protection = upgrades.levelOf(UpgradeType.PROTECTION);
        int minerLevel = upgrades.levelOf(UpgradeType.MANIAC_MINER);
        int healPool = upgrades.levelOf(UpgradeType.HEAL_POOL);
        int dragons = upgrades.levelOf(UpgradeType.DRAGON_BUFF);

        return new EffectSet(
                sharpness,
                protection,
                minerLevel > 0 ? minerLevel - 1 : -1,
                healPool > 0 ? healPool - 1 : -1,
                dragons);
    }
}