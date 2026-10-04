package dev.bedwars.controller.k8s;

/**
 * Scales a GameServerSet's {@code spec.replicas}. Abstracted so the queue logic
 * can be unit-tested with a fake and so a future gRPC backend can slot in.
 */
public interface GameServerSetScaler {

    /** Current desired replicas. */
    int currentReplicas();

    /** Sets desired replicas (clamped by the implementation to min/max). */
    void scaleTo(int replicas);

    /** Scales up by one, returning the new desired replica count. */
    int scaleUpOne();
}