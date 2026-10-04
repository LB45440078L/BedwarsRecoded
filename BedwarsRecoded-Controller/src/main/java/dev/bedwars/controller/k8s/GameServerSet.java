package dev.bedwars.controller.k8s;

import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Version;
import io.fabric8.kubernetes.api.model.Namespaced;

/**
 * Minimal model for OpenKruise's {@code GameServerSet} (game.kruise.io/v1alpha1).
 * Only {@code spec.replicas} is modelled explicitly; every other spec field is
 * preserved by {@link GameServerSetSpec}'s catch-all map so an update never wipes
 * the pod template.
 */
@Group("game.kruise.io")
@Version("v1alpha1")
@Kind("GameServerSet")
@Plural("gameserversets")
public class GameServerSet extends CustomResource<GameServerSetSpec, GameServerSetStatus> implements Namespaced {
}