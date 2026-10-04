package dev.bedwars.core.upgrade;

/**
 * The concrete effects a team's upgrades confer on its members. Computed in Core
 * (testable) and applied by the adapter to gear and potion effects.
 *
 * @param sharpness      enchantment level applied to swords (0 = none)
 * @param protection     enchantment level applied to armour (0 = none)
 * @param hasteAmplifier Haste potion amplifier (0 = Haste I, -1 = none)
 * @param regenAmplifier Regeneration amplifier inside the team's base (-1 = none)
 * @param dragonBuffs    number of extra dragons (cosmetic; adapter-dependent)
 */
public record EffectSet(
        int sharpness,
        int protection,
        int hasteAmplifier,
        int regenAmplifier,
        int dragonBuffs
) {
    public static final EffectSet NONE = new EffectSet(0, 0, -1, -1, 0);

    public boolean hasHaste() {
        return hasteAmplifier >= 0;
    }

    public boolean hasRegen() {
        return regenAmplifier >= 0;
    }
}