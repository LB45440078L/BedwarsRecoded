package dev.bedwars.spigot.effects;

import dev.bedwars.core.upgrade.TrapEffect;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Collection;

/** Applies a triggered trap's {@link TrapEffect} to the intruder and the defenders. */
public final class TrapApplier {

    private TrapApplier() {
    }

    public static void applyToIntruder(Player intruder, TrapEffect effect) {
        int ticks = effect.durationSeconds() * 20;
        if (effect.blindIntruder()) {
            intruder.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, ticks, 0, true, false, false));
        }
        if (effect.slowIntruder()) {
            intruder.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 1, true, false, false));
        }
        if (effect.poisonIntruder()) {
            intruder.addPotionEffect(new PotionEffect(PotionEffectType.POISON, ticks, 0, true, false, false));
        }
        if (effect.miningFatigueIntruder()) {
            intruder.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, ticks, 0, true, false, false));
        }
    }

    public static void applyToDefenders(Collection<Player> defenders, TrapEffect effect) {
        if (!effect.buffDefenders()) {
            return;
        }
        int ticks = effect.durationSeconds() * 20;
        for (Player defender : defenders) {
            defender.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, 1, true, false, false));
            defender.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, ticks, 1, true, false, false));
        }
    }

    public static void alert(Collection<Player> defenders, String trapName) {
        for (Player defender : defenders) {
            defender.sendMessage(ChatColor.RED + "Trap triggered: " + trapName + "!");
        }
    }
}