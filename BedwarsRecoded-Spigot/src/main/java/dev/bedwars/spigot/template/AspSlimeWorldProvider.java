package dev.bedwars.spigot.template;

import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Production world loader: reads the staged {@code .slime} through AdvancedSlimePaper
 * and loads it as the pod's world.
 *
 * <p>The template is opened <b>read-only</b> and then cloned to a per-match instance
 * name, so the template is never mutated — the pod-per-match model means the clone is
 * discarded when the pod dies, and that deletion is the reset.
 */
public final class AspSlimeWorldProvider implements SlimeWorldProvider {

    private final SlimeWorldBridge bridge;
    private final Logger log;

    public AspSlimeWorldProvider(SlimeWorldBridge bridge, Logger log) {
        this.bridge = bridge;
        this.log = log;
    }

    @Override
    public String backend() {
        return bridge == null ? "AdvancedSlimePaper(absent)" : bridge.describe();
    }

    @Override
    public boolean available() {
        return bridge != null && bridge.isAvailable();
    }

    @Override
    public boolean load(Path stagedArchive, String templateName, String instanceName) throws Exception {
        if (!available()) {
            return false;
        }
        // Read-only: a match must never write back into the shared template.
        Object template = bridge.readTemplate(templateName, true);
        if (template == null) {
            log.warn("slime_template_missing template={} backend={}", templateName, backend());
            return false;
        }
        Object instance = bridge.cloneInstance(template, instanceName);
        boolean ok = bridge.loadWorld(instance);
        log.info("slime_world_loaded template={} instance={} backend={} ok={}",
                templateName, instanceName, backend(), ok);
        return ok;
    }
}