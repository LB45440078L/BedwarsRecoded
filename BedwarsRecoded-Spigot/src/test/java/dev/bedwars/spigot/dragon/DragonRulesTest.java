package dev.bedwars.spigot.dragon;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A dragon must hurt the map without deciding the match. These are the blocks it may and
 * may not remove: air is pointless to "break", and beds/barriers/bedrock must survive or
 * a dragon could end a game by destroying a bed the players were defending.
 */
class DragonRulesTest {

    @Test
    void airIsNeverBroken() {
        assertThat(DragonService.isDragonBreakable(Material.AIR)).isFalse();
        assertThat(DragonService.isDragonBreakable(Material.CAVE_AIR)).isFalse();
        assertThat(DragonService.isDragonBreakable(Material.VOID_AIR)).isFalse();
    }

    @Test
    void indestructibleBlocksAreLeftAlone() {
        assertThat(DragonService.isDragonBreakable(Material.BEDROCK)).isFalse();
        assertThat(DragonService.isDragonBreakable(Material.BARRIER)).isFalse();
        assertThat(DragonService.isDragonBreakable(Material.OBSIDIAN)).isFalse();
        assertThat(DragonService.isDragonBreakable(Material.END_STONE)).isFalse();
        assertThat(DragonService.isDragonBreakable(Material.END_PORTAL_FRAME)).isFalse();
    }

    @Test
    void bedsSurviveSoADragonCannotWinTheMatch() {
        assertThat(DragonService.isDragonBreakable(Material.RED_BED)).isFalse();
        assertThat(DragonService.isDragonBreakable(Material.WHITE_BED)).isFalse();
        assertThat(DragonService.isDragonBreakable(Material.BLUE_BED)).isFalse();
    }

    @Test
    void ordinaryTerrainIsTornUp() {
        assertThat(DragonService.isDragonBreakable(Material.GRASS_BLOCK)).isTrue();
        assertThat(DragonService.isDragonBreakable(Material.DIRT)).isTrue();
        assertThat(DragonService.isDragonBreakable(Material.STONE)).isTrue();
        assertThat(DragonService.isDragonBreakable(Material.OAK_PLANKS)).isTrue();
        assertThat(DragonService.isDragonBreakable(Material.GLASS)).isTrue();
        assertThat(DragonService.isDragonBreakable(Material.GLOWSTONE)).isTrue();
    }
}
