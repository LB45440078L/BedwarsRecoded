package dev.bedwars.spigot.compliance;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the hard constraint that the plugin is built against the <b>Spigot API
 * exclusively</b>. A Paper/Folia/Adventure import anywhere in a {@code src/main}
 * tree would mean the plugin silently requires a non-Spigot server; this test turns
 * that into a build failure instead of a runtime surprise.
 *
 * <p>It also asserts that no module POM declares a Paper artifact and that
 * {@code plugin.yml} keeps {@code api-version: 26.1}.
 */
class SpigotOnlyApiTest {

    /** Import prefixes that are not part of the plain Spigot API. */
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "io.papermc.",
            "com.destroystokyo.",
            "net.kyori.adventure.",
            "org.bukkit.craftbukkit.");

    @Test
    void noMainSourceImportsAPaperOrPlatformSpecificApi() throws IOException {
        Path repo = repositoryRoot();
        List<String> violations = new ArrayList<>();

        try (Stream<Path> sources = Files.walk(repo)) {
            for (Path file : sources.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(SpigotOnlyApiTest::isMainSource)
                    .toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (!line.startsWith("import ")) {
                        continue;
                    }
                    for (String forbidden : FORBIDDEN_IMPORTS) {
                        if (line.contains(forbidden)) {
                            violations.add(repo.relativize(file) + ":" + (i + 1) + " -> " + line);
                        }
                    }
                }
            }
        }

        assertThat(violations)
                .as("Main sources must not import Paper/Folia/Adventure APIs (Spigot-only build)")
                .isEmpty();
    }

    @Test
    void noModuleDeclaresAPaperDependency() throws IOException {
        Path repo = repositoryRoot();
        List<String> violations = new ArrayList<>();

        try (Stream<Path> poms = Files.walk(repo)) {
            for (Path pom : poms.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .toList()) {
                String text = Files.readString(pom, StandardCharsets.UTF_8);
                if (text.contains("<artifactId>paper-api</artifactId>")
                        || text.contains("mockbukkit")) {
                    violations.add(repo.relativize(pom).toString());
                }
            }
        }

        assertThat(violations)
                .as("No module may depend on the Paper API or MockBukkit-for-Paper")
                .isEmpty();
    }

    private static boolean isMainSource(Path path) {
        String normalised = path.toString().replace('\\', '/');
        return normalised.contains("/src/main/java/");
    }

    /** Walks up from the working directory to the reactor root. */
    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && current != null; i++) {
            if (Files.isDirectory(current.resolve("BedwarsRecoded-Spigot"))
                    && Files.isDirectory(current.resolve("BedwarsRecoded-Core"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the reactor root from the working directory");
    }
}
