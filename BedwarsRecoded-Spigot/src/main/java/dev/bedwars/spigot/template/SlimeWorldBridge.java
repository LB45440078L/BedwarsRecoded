package dev.bedwars.spigot.template;

/**
 * The three AdvancedSlimePaper operations the world loader needs, typed opaquely.
 *
 * <p>Why a seam: the ASP API's {@code SlimeWorld} exposes NBT return types whose
 * artifact is not resolvable in every build environment. Routing the ASP calls
 * through this interface lets {@link AspSlimeWorldProvider} — the part with the
 * actual load sequence and its guarantees (read-only template, per-match clone) —
 * be unit-tested without the ASP runtime on the classpath, while
 * {@link AspSlimeWorldBridge} remains the thin, compile-checked ASP glue.
 */
public interface SlimeWorldBridge {

    /** True when the ASP API and its loader are both present. */
    boolean isAvailable();

    /**
     * Reads the template from its data source.
     *
     * @param readOnly must be {@code true} for a template: the pod may never write
     *                 back into the shared template
     * @return the Slime world handle (opaque), or {@code null} when absent
     */
    Object readTemplate(String templateName, boolean readOnly) throws Exception;

    /** Clones the template to a fresh per-match instance name. */
    Object cloneInstance(Object template, String instanceName);

    /** Loads the clone into the server as a playable world. */
    boolean loadWorld(Object world);

    /** Description of the backing data source, for logs and the ready report. */
    String describe();
}