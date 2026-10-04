package dev.bedwars.api.dto;

/**
 * Lifecycle phase of a game pod, mirroring the OpenKruise GameServerSet state.
 * The controller drives this; pods report transitions via webhook/annotation.
 */
public enum PodPhase {
    PENDING,
    READY,
    ALLOCATED,
    DRAINING,
    TERMINATING,
    DESTROYED
}