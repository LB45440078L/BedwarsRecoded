package dev.bedwars.spigot.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped default is {@code server-id: "pod-${HOSTNAME}"}; it must resolve, or
 * every pod reports the literal string to the controller.
 */
class PluginConfigTest {

    @Test
    void plainValuesPassThroughUnchanged() {
        assertThat(PluginConfig.expand("bedwars-solo-0")).isEqualTo("bedwars-solo-0");
        assertThat(PluginConfig.expand("")).isEmpty();
        assertThat(PluginConfig.expand(null)).isNull();
    }

    @Test
    void hostnamePlaceholderNeverStaysLiteral() {
        String id = PluginConfig.expand("pod-${HOSTNAME}");

        assertThat(id).startsWith("pod-").doesNotContain("${").doesNotContain("}");
    }

    @Test
    void expandsAValueThatIsDefinitelyInTheEnvironment() {
        // PATH exists on every platform these tests run on.
        String expanded = PluginConfig.expand("x-${PATH}");

        assertThat(expanded).startsWith("x-").doesNotContain("${");
        assertThat(expanded.length()).isGreaterThan(3);
    }

    @Test
    void unresolvedPlaceholderFallsBackToPodLocal() {
        assertThat(PluginConfig.expand("${BEDWARS_DEFINITELY_NOT_SET_XYZ}")).isEqualTo("pod-local");
    }

    @Test
    void multiplePlaceholdersAreAllExpanded() {
        String expanded = PluginConfig.expand("${PATH}-${PATH}");

        assertThat(expanded).doesNotContain("${").doesNotStartWith("-");
    }
}