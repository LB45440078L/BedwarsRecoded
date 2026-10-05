package dev.bedwars.spigot.template;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The load sequence and its guarantees: the template is read read-only, the played
 * world is a clone under the per-match name, and that clone is what gets loaded.
 */
class AspSlimeWorldProviderTest {

    /** Records the calls the provider makes through the bridge. */
    private static final class FakeBridge implements SlimeWorldBridge {
        final List<String> calls = new ArrayList<>();
        boolean readOnlySeen;
        boolean available = true;
        Object template = new Object();
        boolean loadResult = true;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public Object readTemplate(String templateName, boolean readOnly) {
            calls.add("readTemplate:" + templateName);
            readOnlySeen = readOnly;
            return template;
        }

        @Override
        public Object cloneInstance(Object template, String instanceName) {
            calls.add("cloneInstance:" + instanceName);
            return new Object();
        }

        @Override
        public boolean loadWorld(Object world) {
            calls.add("loadWorld");
            return loadResult;
        }

        @Override
        public String describe() {
            return "AdvancedSlimePaper(s3)";
        }
    }

    private static AspSlimeWorldProvider provider(FakeBridge bridge) {
        return new AspSlimeWorldProvider(bridge, LoggerFactory.getLogger("test"));
    }

    @Test
    void readsTheTemplateReadOnlyThenClonesAndLoadsIt() throws Exception {
        FakeBridge bridge = new FakeBridge();

        boolean loaded = provider(bridge).load(Path.of("/staging/Glacier-1.0.0.slime"), "Glacier", "match-42");

        assertThat(loaded).isTrue();
        // Never open the shared template writable — the clone is what gets played.
        assertThat(bridge.readOnlySeen).isTrue();
        assertThat(bridge.calls).containsExactly("readTemplate:Glacier", "cloneInstance:match-42", "loadWorld");
    }

    @Test
    void missingTemplateIsReportedNotLoaded() throws Exception {
        FakeBridge bridge = new FakeBridge();
        bridge.template = null;

        assertThat(provider(bridge).load(Path.of("/staging/x.slime"), "Glacier", "match-1")).isFalse();
        assertThat(bridge.calls).containsExactly("readTemplate:Glacier");
    }

    @Test
    void aFailedLoadIsSurfaced() throws Exception {
        FakeBridge bridge = new FakeBridge();
        bridge.loadResult = false;

        assertThat(provider(bridge).load(Path.of("/staging/x.slime"), "Glacier", "match-1")).isFalse();
    }

    @Test
    void backendNameIdentifiesTheDataSourceAndAbsenceIsHonest() {
        assertThat(provider(new FakeBridge()).backend()).isEqualTo("AdvancedSlimePaper(s3)");
        assertThat(new AspSlimeWorldProvider(null, LoggerFactory.getLogger("t")).backend())
                .isEqualTo("AdvancedSlimePaper(absent)");
        assertThat(new AspSlimeWorldProvider(null, LoggerFactory.getLogger("t")).available()).isFalse();
    }

    @Test
    void unavailableWhenTheBridgeIsNotAvailable() throws Exception {
        FakeBridge bridge = new FakeBridge();
        bridge.available = false;

        assertThat(provider(bridge).available()).isFalse();
        assertThat(provider(bridge).load(Path.of("/x.slime"), "Glacier", "m")).isFalse();
        assertThat(bridge.calls).isEmpty();
    }
}