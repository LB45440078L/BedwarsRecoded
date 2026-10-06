package dev.bedwars.controller.provision;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provisioner that starts and stops plain Docker containers through the Docker CLI.
 *
 * <p>It is the single-host alternative to Kubernetes and shows the abstraction works:
 * the queue/matchmaking code is identical in both cases. Containers are named
 * {@code <prefix>-<n>}, carry the label {@code bedwars.provisioned=true} (so the
 * provisioner only ever touches containers it created) and a memory cap. Every
 * command is passed as an argument list, never a shell string.
 *
 * <p>Commands go through {@link CommandRunner}, so the whole class is unit-testable
 * with a fake runner, and the same code path is exercised against a real daemon by an
 * opt-in integration test.
 */
public final class DockerProvisioner implements ServerProvisioner {

    static final String LABEL_SELECTOR = "label=bedwars.provisioned=true";

    private final CommandRunner runner;
    private final String binary;
    private final String prefix;
    private final String image;
    private final String memory;
    private final int gamesPerServer;
    private final int minServers;
    private final int maxServers;
    private final String arenaGroup;
    private final String controllerUrl;
    private final String network;
    private final List<String> containerCommand;
    private final Logger log;

    public DockerProvisioner(CommandRunner runner,
                             String binary,
                             String prefix,
                             String image,
                             String memory,
                             int gamesPerServer,
                             int minServers,
                             int maxServers,
                             String arenaGroup,
                             String controllerUrl,
                             String network,
                             List<String> containerCommand,
                             Logger log) {
        this.runner = runner;
        this.binary = binary;
        this.prefix = prefix;
        this.image = image;
        this.memory = memory;
        this.gamesPerServer = Math.max(1, gamesPerServer);
        this.minServers = Math.max(0, minServers);
        this.maxServers = Math.max(this.minServers, maxServers);
        this.arenaGroup = arenaGroup == null || arenaGroup.isBlank() ? "solo" : arenaGroup;
        this.controllerUrl = controllerUrl == null ? "" : controllerUrl;
        this.network = network == null ? "" : network;
        this.containerCommand = containerCommand == null ? List.of() : List.copyOf(containerCommand);
        this.log = log;
    }

    @Override
    public ProvisionerKind kind() {
        return ProvisionerKind.DOCKER;
    }

    /** True when the Docker daemon answers. Used at startup to fail safe to NoOp. */
    public boolean available() {
        return runner.run(List.of(binary, "version", "--format", "{{.Server.Version}}")).ok();
    }

    @Override
    public int currentServers() {
        return runningServers().size();
    }

    /** Every provisioned container (running or not), lowest index first. */
    public List<String> allServers() {
        CommandRunner.Result result = runner.run(List.of(binary, "ps", "-a",
                "--filter", LABEL_SELECTOR, "--format", "{{.Names}}"));
        if (!result.ok()) {
            log.warn("docker ps failed: {}", result.stderr());
            return List.of();
        }
        return parseNames(result.output());
    }

    /** Running provisioned containers, lowest index first. */
    public List<String> runningServers() {
        CommandRunner.Result result = runner.run(List.of(binary, "ps",
                "--filter", LABEL_SELECTOR, "--format", "{{.Names}}"));
        if (!result.ok()) {
            log.warn("docker ps failed: {}", result.stderr());
            return List.of();
        }
        return parseNames(result.output());
    }

    @Override
    public int minimumServers() {
        return minServers;
    }

    @Override
    public int maximumServers() {
        return maxServers;
    }

    @Override
    public int gamesPerServer() {
        return gamesPerServer;
    }

    @Override
    public int scaleUpOne() {
        int current = currentServers();
        if (current >= maxServers) {
            log.debug("At maximum servers ({}); not scaling up", maxServers);
            return current;
        }
        String name = nextFreeName();
        return startContainer(name) ? current + 1 : current;
    }

    @Override
    public void scaleTo(int servers) {
        int target = Math.clamp(servers, minServers, maxServers);

        // Bring back existing stopped containers before creating new ones: cheaper and
        // it reuses capacity that is already on the host.
        List<String> stopped = new ArrayList<>(allServers());
        stopped.removeAll(runningServers());
        for (String name : stopped) {
            if (currentServers() >= target) {
                break;
            }
            startExisting(name);
        }

        int guard = 0;
        while (currentServers() < target && guard++ < maxServers) {
            if (!startContainer(nextFreeName())) {
                break;
            }
        }

        List<String> running = runningServers();
        while (running.size() > target && !running.isEmpty()) {
            stopServer(running.getLast()); // highest index first
            running = runningServers();
        }
    }

    /** Starts an already-created container. */
    public boolean startExisting(String name) {
        CommandRunner.Result result = runner.run(List.of(binary, "start", name));
        if (!result.ok()) {
            log.warn("docker start {} failed: {}", name, result.stderr());
            return false;
        }
        log.info("Restarted game server container {}", name);
        return true;
    }

    /** Starts (or restarts) a named container. Returns true when the run command succeeded. */
    public boolean startContainer(String name) {
        List<String> command = new ArrayList<>(List.of(binary, "run", "-d",
                "--name", name,
                "--label", "bedwars.provisioned=true",
                "--label", "bedwars.role=game",
                "--memory", memory,
                "--restart=no"));
        // Join the controller's Docker network so the pod can resolve the controller by
        // name. Without it the container lands on the default bridge and every report
        // fails with ConnectException.
        if (!network.isBlank()) {
            command.add("--network");
            command.add(network);
        }
        command.addAll(List.of(
                "-e", "BEDWARS_SERVER_ID=" + name,
                "-e", "BEDWARS_ARENA_GROUP=" + arenaGroup,
                // The plugin reads BEDWARS_CONTROLLER_URL; a bare CONTROLLER_URL is
                // ignored, which silently left every pod on the baked default.
                "-e", "BEDWARS_CONTROLLER_URL=" + controllerUrl,
                image));
        command.addAll(containerCommand);
        CommandRunner.Result result = runner.run(command);
        if (!result.ok()) {
            log.error("Failed to start container {}: {}", name, result.stderr().isBlank()
                    ? result.output() : result.stderr());
            return false;
        }
        log.info("Started game server container {}", name);
        return true;
    }

    /**
     * Gracefully stops and removes a container. Removal is deliberate: a stopped
     * container that lingers would be an orphaned resource, which the project forbids.
     */
    public void stopServer(String name) {
        CommandRunner.Result stop = runner.run(List.of(binary, "stop", "-t", "20", name));
        if (!stop.ok()) {
            log.warn("docker stop {} failed: {}", name, stop.stderr());
        }
        CommandRunner.Result remove = runner.run(List.of(binary, "rm", "-f", name));
        if (!remove.ok()) {
            log.warn("docker rm {} failed: {}", name, remove.stderr());
        } else {
            log.info("Reclaimed game server container {}", name);
        }
    }

    /** True when the named container is running. Diagnostics only; readiness is self-reported. */
    public boolean isHealthy(String name) {
        CommandRunner.Result result = runner.run(List.of(binary, "inspect", "-f", "{{.State.Running}}", name));
        return result.ok() && "true".equals(result.output());
    }

    @Override
    public String describe() {
        return "docker prefix=" + prefix + " image=" + image + " servers[" + minServers + ".." + maxServers
                + "] gamesPerServer=" + gamesPerServer + " memory=" + memory;
    }

    // ---- helpers ---------------------------------------------------------

    private List<String> parseNames(String output) {
        List<String> names = new ArrayList<>();
        for (String line : output.split("\n")) {
            String name = line.trim();
            if (name.startsWith(prefix) && indexOf(name).isPresent()) {
                names.add(name);
            }
        }
        names.sort(Comparator.comparingInt(n -> indexOf(n).orElse(Integer.MAX_VALUE)));
        return names;
    }

    private String nextFreeName() {
        var used = allServers().stream().map(this::indexOf).flatMap(Optional::stream).toList();
        int index = 1;
        while (used.contains(index)) {
            index++;
        }
        return prefix + "-" + index;
    }

    private Optional<Integer> indexOf(String name) {
        if (!name.startsWith(prefix + "-")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(name.substring(prefix.length() + 1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Exposes the label the provisioner filters on (used by the integration test). */
    public static Map<String, String> labels() {
        return Map.of("bedwars.provisioned", "true", "bedwars.role", "game");
    }

    @Override
    public void close() {
        if (runner instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("Failed to close the command runner", e);
            }
        }
    }
}
