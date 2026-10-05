package dev.bedwars.core.logging;

import java.util.Map;

/**
 * Request-scoped correlation ids (game id, pod id) propagated with
 * <b>Scoped Values</b> — finalised in JDK 25 (JEP 506), so no preview flag is needed.
 *
 * <p>Scoped values are the right tool here: the ids are immutable for the duration
 * of a unit of work, must not be mutated by callees, and must not leak between
 * matches. Unlike a {@code ThreadLocal} they are inherited by the structured
 * subtasks a unit of work starts, and they are automatically restored afterwards.
 *
 * <pre>{@code
 *   CorrelationContext.run(gameId, podId, () ->
 *       log.info(StructuredLog.json("game_ended", CorrelationContext.fields())));
 * }</pre>
 */
public final class CorrelationContext {

    public static final ScopedValue<String> GAME_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> POD_ID = ScopedValue.newInstance();

    private CorrelationContext() {
    }

    /**
     * Runs {@code body} with the correlation ids bound for its whole dynamic extent.
     * Uses {@link ScopedValue.CallableOp} because that is what the finalized JDK 25
     * {@code Carrier} accepts.
     */
    public static <T> T with(String gameId, String podId, ScopedValue.CallableOp<T, Exception> body)
            throws Exception {
        return ScopedValue.where(GAME_ID, gameId).where(POD_ID, podId).call(body);
    }

    /** Runs a {@link Runnable} body with the correlation ids bound. */
    public static void run(String gameId, String podId, Runnable body) {
        ScopedValue.where(GAME_ID, gameId).where(POD_ID, podId).run(body);
    }

    /** The currently bound ids, for structured log fields. Empty outside a scope. */
    public static Map<String, Object> fields() {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        if (GAME_ID.isBound()) {
            fields.put("game_id", GAME_ID.get());
        }
        if (POD_ID.isBound()) {
            fields.put("pod_id", POD_ID.get());
        }
        return fields;
    }
}