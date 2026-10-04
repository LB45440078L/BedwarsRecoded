package dev.bedwars.spigot.effects;

import dev.bedwars.core.upgrade.EffectSet;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Applies a team's {@link EffectSet} to a player: Sharpness on swords, Protection
 * on armour, Haste (Maniac Miner) and Regeneration (Heal Pool, in base only).
 * Idempotent — safe to run on a timer.
 */
public final class UpgradeEffectApplier {

    private static final int EFFECT_TICKS = 60;

    private UpgradeEffectApplier() {
    }

    public static void apply(Player player, EffectSet effects, boolean inBase) {
        enchantGear(player, effects);
        if (effects.hasHaste()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,
                    EFFECT_TICKS, effects.hasteAmplifier(), true, false, false));
        }
        if (effects.hasRegen() && inBase) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,
                    EFFECT_TICKS, effects.regenAmplifier(), true, false, false));
        }
    }

    private static void enchantGear(Player player, EffectSet effects) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null) {
                continue;
            }
            String name = stack.getType().name();
            if (effects.sharpness() > 0 && name.endsWith("_SWORD")) {
                enchant(stack, "SHARPNESS", effects.sharpness());
            } else if (effects.protection() > 0 && isArmour(name)) {
                enchant(stack, "PROTECTION", effects.protection());
            }
        }
    }

    private static boolean isArmour(String name) {
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
    }

    private static void enchant(ItemStack stack, String enchantName, int level) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        Enchantment enchantment = Enchantment.getByName(enchantName);
        if (enchantment == null) {
            return;
        }
        if (meta.getEnchantLevel(enchantment) < level) {
            meta.addEnchant(enchantment, level, true);
            stack.setItemMeta(meta);
        }
    }
}