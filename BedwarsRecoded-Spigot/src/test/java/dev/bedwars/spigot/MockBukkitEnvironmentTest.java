package dev.bedwars.spigot;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MockBukkit harness for Paper/Spigot 26.2 — the version the project actually targets.
 * This proves the Bukkit-interaction layer can be unit-tested without a running server.
 */
class MockBukkitEnvironmentTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void bootsAMockedServerForTheTargetApi() {
        assertThat(server).isNotNull();
        assertThat(server.getName()).isNotBlank();
        assertThat(server.getVersion()).isNotBlank();
    }

    @Test
    void providesRealPlayerMocksForListeners() {
        PlayerMock player = server.addPlayer("Tester");
        assertThat(player.getUniqueId()).isNotNull();
        assertThat(player.getName()).isEqualTo("Tester");
        // A mocked player can be teleported and damaged, which is what the thin
        // listeners (void-kill, protection, spectator) act on.
        player.teleport(player.getLocation());
        player.setHealth(0.0);
        assertThat(player.isDead()).isTrue();
    }

    @Test
    void mockedPlayersSupportTheBukkitInteractionsOurListenersUse() {
        PlayerMock player = server.addPlayer("Tester");
        var world = player.getWorld();

        // The interactions the thin listeners act on: teleport (join/spectate),
        // damage/death (void kill, elimination), health and gamemode.
        player.teleport(world.getSpawnLocation());
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        player.setHealth(0.0);

        assertThat(player.getLocation().getWorld()).isEqualTo(world);
        assertThat(player.getGameMode()).isEqualTo(org.bukkit.GameMode.SURVIVAL);
        assertThat(player.isDead()).isTrue();
    }

    // NOTE: loading the whole plugin (MockBukkit.load(BedwarsRecodedPlugin.class))
    // is deliberately not done here. MockBukkit's plugin classloader requires the
    // plugin JAR on the classpath, and surefire runs against target/classes, so it
    // fails with "No jar file selected". Plugin bootstrap is covered by the full
    // jars built in `mvn package` and by the cluster runs instead.
}