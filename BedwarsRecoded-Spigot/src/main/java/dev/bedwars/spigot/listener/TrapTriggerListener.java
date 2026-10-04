package dev.bedwars.spigot.listener;

import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.Team;
import dev.bedwars.core.domain.Vec3;
import dev.bedwars.core.manager.GameManager;
import dev.bedwars.core.upgrade.TrapEffect;
import dev.bedwars.core.upgrade.TrapTriggerService;
import dev.bedwars.core.upgrade.TrapType;
import dev.bedwars.spigot.effects.TrapApplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fires a team's armed trap when an enemy enters its base. Trigger conditions and
 * cooldown live in {@link TrapTriggerService}; this listener only bridges Bukkit
 * events to that logic and applies the resulting effects.
 */
public final class TrapTriggerListener implements Listener {

    private final GameManager gameManager;
    private final TrapTriggerService triggers;

    public TrapTriggerListener(GameManager gameManager, TrapTriggerService triggers) {
        this.gameManager = gameManager;
        this.triggers = triggers;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ())) {
            return; // only re-evaluate when the block position changes
        }
        Player intruder = event.getPlayer();
        Game game = gameManager.byPlayer(intruder.getUniqueId()).orElse(null);
        if (game == null) {
            return;
        }
        String intruderTeam = game.teamOf(intruder.getUniqueId()).orElse(null);
        if (intruderTeam == null) {
            return;
        }
        Vec3 position = new Vec3(to.getX(), to.getY(), to.getZ());
        for (Team team : game.teams()) {
            if (team.id().equals(intruderTeam)) {
                continue; // never trigger your own team's trap
            }
            Optional<TrapType> fired = triggers.checkTrigger(team, position, System.currentTimeMillis());
            fired.ifPresent(trap -> fire(game, team, trap, intruder));
        }
    }

    private void fire(Game game, Team defendingTeam, TrapType trap, Player intruder) {
        TrapEffect effect = trap.effects();
        TrapApplier.applyToIntruder(intruder, effect);
        List<Player> defenders = new ArrayList<>();
        for (java.util.UUID member : defendingTeam.members()) {
            Player player = Bukkit.getPlayer(member);
            if (player != null && player.isOnline()) {
                defenders.add(player);
            }
        }
        TrapApplier.applyToDefenders(defenders, effect);
        if (effect.alertTeam()) {
            TrapApplier.alert(defenders, trap.displayName());
        }
    }
}