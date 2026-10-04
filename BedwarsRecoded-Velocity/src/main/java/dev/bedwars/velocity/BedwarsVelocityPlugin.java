package dev.bedwars.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.bedwars.api.service.DispatchResult;
import dev.bedwars.velocity.client.ControllerClient;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Persistent Velocity proxy. Single entry point for players; it asks the
 * controller which ready pod to route them to and never scales anything itself.
 */
@Plugin(id = "bedwarsrecoded", name = "BedwarsRecoded", version = "1.0.0-SNAPSHOT",
        description = "Routes players to ephemeral Bedwars game pods.", authors = {"BedwarsRecoded"})
public final class BedwarsVelocityPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private ControllerClient controller;

    @Inject
    public BedwarsVelocityPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        String baseUrl = System.getenv().getOrDefault("CONTROLLER_URL", "http://bedwars-controller:8080");
        this.controller = new ControllerClient(baseUrl);
        logger.info("BedwarsRecoded proxy initialised; controller at {}", baseUrl);
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        if (controller == null) {
            return;
        }
        var player = event.getPlayer();
        controller.requestSlot(player.getUniqueId(), player.getUsername(), Optional.empty())
                .thenAccept(result -> route(player, result));
    }

    private void route(com.velocitypowered.api.proxy.Player player, DispatchResult result) {
        if (!result.successful()) {
            logger.debug("No slot for {}; retry in {}ms", player.getUsername(), result.retryAfterMillis());
            return;
        }
        proxy.getServer(result.podAddress())
                .or(() -> proxy.getAllServers().stream()
                        .filter(server -> server.getServerInfo().getAddress().getHostString().equals(result.podAddress()))
                        .findFirst())
                .ifPresentOrElse(
                        server -> player.createConnectionRequest(server).fireAndForget(),
                        () -> logger.warn("Controller returned unknown pod {} for {}",
                                result.podAddress(), player.getUsername()));
    }
}