package dev.bedwars.core.logging;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Scoped-value correlation context: bound inside the scope, restored outside it. */
class CorrelationContextTest {

    @Test
    void idsAreNotBoundOutsideAScope() {
        assertThat(CorrelationContext.GAME_ID.isBound()).isFalse();
        assertThat(CorrelationContext.fields()).isEmpty();
    }

    @Test
    void runBindsTheIdsForTheDynamicExtentOnly() {
        AtomicReference<String> inside = new AtomicReference<>();

        CorrelationContext.run("game-1", "pod-1", () -> inside.set(
                CorrelationContext.fields().toString()));

        assertThat(inside.get()).contains("game_id=game-1").contains("pod_id=pod-1");
        assertThat(CorrelationContext.GAME_ID.isBound()).isFalse();
    }

    @Test
    void callReturnsTheValueAndRestoresTheBinding() throws Exception {
        String result = CorrelationContext.with("game-2", "pod-2", () -> CorrelationContext.GAME_ID.get());

        assertThat(result).isEqualTo("game-2");
        assertThat(CorrelationContext.GAME_ID.isBound()).isFalse();
    }

    @Test
    void fieldsMapIsShapedForStructuredLogging() {
        CorrelationContext.run("g", "p", () -> assertThat(CorrelationContext.fields())
                .containsEntry("game_id", "g")
                .containsEntry("pod_id", "p"));
    }
}