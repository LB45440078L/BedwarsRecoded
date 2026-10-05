package dev.bedwars.controller.provision;

/**
 * The infrastructure seam. The controller's matchmaking/queue logic only ever talks
 * to a {@code ServerProvisioner}; it never knows whether game servers are Kubernetes
 * pods, Docker containers, or something else. This is what makes the backend
 * replaceable (constraint: keep infrastructure providers replaceable).
 *
 * <p>The unit of capacity is a <em>server</em> that hosts {@link #gamesPerServer()}
 * concurrent games, so a provisioner expresses both host count and game slots.
 */
public interface ServerProvisioner extends AutoCloseable {

    ProvisionerKind kind();

    /** Servers currently provisioned (running or starting). */
    int currentServers();

    /** Lower bound the provisioner will never scale below. */
    int minimumServers();

    /** Upper bound the provisioner will never scale above. */
    int maximumServers();

    /** How many concurrent BedWars games one dedicated server is expected to host. */
    int gamesPerServer();

    /** Adds one server; returns the resulting server count. */
    int scaleUpOne();

    /** Scales to {@code servers}, clamped to {@code [minimumServers, maximumServers]}. */
    void scaleTo(int servers);

    /** Human-readable summary for startup logs and {@code /metrics} labels. */
    String describe();

    /** Total game slots if every current server were full. */
    default int totalGameCapacity() {
        return currentServers() * gamesPerServer();
    }

    @Override
    default void close() {
    }
}
