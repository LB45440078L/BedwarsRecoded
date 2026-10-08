package dev.bedwars.controller.provision;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link DockerProvisioner} through a fake {@link CommandRunner}, so the
 * exact Docker commands and the scaling decisions are verified without a daemon.
 * A separate opt-in integration test drives a real daemon.
 */
class DockerProvisionerTest {

    /** Records every command and keeps a tiny in-memory model of the container set. */
    static final class FakeDocker implements CommandRunner {
        final List<List<String>> commands = new ArrayList<>();
        final Set<String> existing = new LinkedHashSet<>();
        final Set<String> running = new LinkedHashSet<>();
        boolean daemonUp = true;

        @Override
        public Result run(List<String> command) {
            commands.add(List.copyOf(command));
            String action = command.size() >= 2 ? command.get(1) : "";
            switch (action) {
                case "version" -> {
                    return daemonUp ? new Result(0, "29.0.0", "") : Result.failure("cannot connect");
                }
                case "ps" -> {
                    Set<String> source = command.contains("-a") ? existing : running;
                    return new Result(0, String.join("\n", source), "");
                }
                case "run" -> {
                    String name = valueAfter(command, "--name");
                    existing.add(name);
                    running.add(name);
                    return new Result(0, name + "\n", "");
                }
                case "start" -> {
                    String name = command.getLast();
                    running.add(name);
                    return new Result(0, name, "");
                }
                case "stop" -> {
                    running.remove(command.getLast());
                    return new Result(0, "", "");
                }
                case "rm" -> {
                    existing.remove(command.getLast());
                    running.remove(command.getLast());
                    return new Result(0, "", "");
                }
                case "inspect" -> {
                    String name = command.getLast();
                    return new Result(0, running.contains(name) ? "true" : "false", "");
                }
                default -> {
                    return new Result(0, "", "");
                }
            }
        }

        List<List<String>> commandsStartingWith(String action) {
            return commands.stream().filter(c -> c.size() >= 2 && c.get(1).equals(action)).toList();
        }

        private static String valueAfter(List<String> command, String flag) {
            int index = command.indexOf(flag);
            return index >= 0 && index + 1 < command.size() ? command.get(index + 1) : "";
        }
    }

    private DockerProvisioner provisioner(FakeDocker docker, int min, int max) {
        return provisioner(docker, min, max, "test-token");
    }

    private DockerProvisioner provisioner(FakeDocker docker, int min, int max, String token) {
        return new DockerProvisioner(docker, "docker", "bedwars-game", "bedwars-recoded-game:1.0.0",
                "1024m", 25, min, max, "solo", "http://controller:8080", token, "bedwars_default", List.of(),
                LoggerFactory.getLogger("test"));
    }

    @Test
    void scaleUpStartsAContainerWithAnArgumentListCommand() {
        FakeDocker docker = new FakeDocker();
        DockerProvisioner provisioner = provisioner(docker, 0, 10);

        assertThat(provisioner.scaleUpOne()).isEqualTo(1);

        List<String> run = docker.commandsStartingWith("run").getFirst();
        assertThat(run.get(0)).isEqualTo("docker");
        assertThat(run).contains("--name", "bedwars-game-1");
        assertThat(run).contains("-e", "BEDWARS_SERVER_ID=bedwars-game-1", "BEDWARS_ARENA_GROUP=solo");
        assertThat(run).contains("--memory", "1024m");
        assertThat(run).contains("--label", "bedwars.provisioned=true");
        // The pod must join the controller's network or it cannot resolve it, and the
        // plugin only reads BEDWARS_CONTROLLER_URL (a bare CONTROLLER_URL is ignored).
        assertThat(run).contains("--network", "bedwars_default");
        assertThat(run).contains("-e", "BEDWARS_CONTROLLER_URL=http://controller:8080");
        // The pod must present the controller's shared secret or every report is 401.
        assertThat(run).contains("-e", "BEDWARS_API_TOKEN=test-token");
        assertThat(run.getLast()).isEqualTo("bedwars-recoded-game:1.0.0");
        // No shell is ever involved: the command is an argument list.
        assertThat(run).doesNotContain("-c", "sh", "bash");
    }

    @Test
    void noTokenIsPassedWhenTheControllerRunsOpen() {
        FakeDocker docker = new FakeDocker();
        DockerProvisioner provisioner = provisioner(docker, 0, 10, "");

        assertThat(provisioner.scaleUpOne()).isEqualTo(1);

        List<String> run = docker.commandsStartingWith("run").getFirst();
        // An empty variable would read as a presented credential, so it must be absent
        // entirely rather than present-and-blank.
        assertThat(run).noneMatch(arg -> arg.startsWith("BEDWARS_API_TOKEN="));
    }

    @Test
    void scaleUpNeverExceedsTheMaximum() {
        FakeDocker docker = new FakeDocker();
        DockerProvisioner provisioner = provisioner(docker, 0, 2);

        assertThat(provisioner.scaleUpOne()).isEqualTo(1);
        assertThat(provisioner.scaleUpOne()).isEqualTo(2);
        assertThat(provisioner.scaleUpOne()).isEqualTo(2); // capped

        assertThat(docker.commandsStartingWith("run")).hasSize(2);
    }

    @Test
    void scaleToStopsHighestIndexFirstAndRemovesThem() {
        FakeDocker docker = new FakeDocker();
        docker.existing.addAll(List.of("bedwars-game-1", "bedwars-game-2", "bedwars-game-3"));
        docker.running.addAll(docker.existing);
        DockerProvisioner provisioner = provisioner(docker, 1, 10);

        provisioner.scaleTo(1);

        assertThat(docker.running).containsExactly("bedwars-game-1");
        // The two reclaimed containers must be stopped AND removed (no orphans).
        List<String> stopped = docker.commandsStartingWith("stop").stream().map(c -> c.getLast()).toList();
        assertThat(stopped).containsExactly("bedwars-game-3", "bedwars-game-2");
        assertThat(docker.commandsStartingWith("rm")).extracting(c -> c.getLast())
                .containsExactlyInAnyOrder("bedwars-game-3", "bedwars-game-2");
    }

    @Test
    void scaleToNeverGoesBelowMinimum() {
        FakeDocker docker = new FakeDocker();
        docker.existing.addAll(List.of("bedwars-game-1", "bedwars-game-2"));
        docker.running.addAll(docker.existing);
        DockerProvisioner provisioner = provisioner(docker, 2, 10);

        provisioner.scaleTo(0);

        assertThat(docker.running).containsExactlyInAnyOrder("bedwars-game-1", "bedwars-game-2");
        assertThat(docker.commandsStartingWith("stop")).isEmpty();
    }

    @Test
    void restartsStoppedContainersBeforeCreatingNewOnes() {
        FakeDocker docker = new FakeDocker();
        docker.existing.addAll(List.of("bedwars-game-1", "bedwars-game-2")); // 2 exists, stopped
        docker.running.add("bedwars-game-1");
        DockerProvisioner provisioner = provisioner(docker, 0, 10);

        provisioner.scaleTo(2);

        assertThat(docker.running).containsExactlyInAnyOrder("bedwars-game-1", "bedwars-game-2");
        assertThat(docker.commandsStartingWith("start")).hasSize(1);
        assertThat(docker.commandsStartingWith("run")).isEmpty(); // reused, did not create
    }

    @Test
    void foreignContainersAreIgnored() {
        FakeDocker docker = new FakeDocker();
        docker.existing.addAll(List.of("bedwars-game-1", "postgres", "bedwars-game-2"));
        docker.running.addAll(List.of("bedwars-game-1", "postgres", "bedwars-game-2"));
        DockerProvisioner provisioner = provisioner(docker, 0, 10);

        assertThat(provisioner.currentServers()).isEqualTo(2);
        assertThat(provisioner.allServers()).containsExactly("bedwars-game-1", "bedwars-game-2");
    }

    @Test
    void unavailableWhenTheDaemonIsDown() {
        FakeDocker docker = new FakeDocker();
        docker.daemonUp = false;
        DockerProvisioner provisioner = provisioner(docker, 0, 10);

        assertThat(provisioner.available()).isFalse();
        assertThat(provisioner.currentServers()).isZero();
    }

    @Test
    void isHealthyReflectsTheContainerState() {
        FakeDocker docker = new FakeDocker();
        docker.existing.add("bedwars-game-1");
        docker.running.add("bedwars-game-1");
        DockerProvisioner provisioner = provisioner(docker, 0, 10);

        assertThat(provisioner.isHealthy("bedwars-game-1")).isTrue();
        assertThat(provisioner.isHealthy("bedwars-game-9")).isFalse();
    }

    @Test
    void reportsGameCapacityPerServer() {
        FakeDocker docker = new FakeDocker();
        docker.existing.add("bedwars-game-1");
        docker.running.add("bedwars-game-1");
        DockerProvisioner provisioner = provisioner(docker, 0, 10);

        assertThat(provisioner.gamesPerServer()).isEqualTo(25);
        assertThat(provisioner.totalGameCapacity()).isEqualTo(25);
        assertThat(provisioner.describe()).contains("docker").contains("gamesPerServer=25");
    }
}
