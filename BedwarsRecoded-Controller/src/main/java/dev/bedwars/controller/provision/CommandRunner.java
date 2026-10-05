package dev.bedwars.controller.provision;

import java.util.List;

/**
 * Runs an external command and captures its result. Extracted so the Docker
 * provisioner is unit-testable without a Docker daemon: tests supply a fake that
 * records the exact command line and returns canned output.
 *
 * <p>Commands are always passed as an argument list (never a shell string), so there
 * is no shell to inject into and no quoting bugs.
 */
@FunctionalInterface
public interface CommandRunner {

    Result run(List<String> command);

    record Result(int exitCode, String stdout, String stderr) {

        public boolean ok() {
            return exitCode == 0;
        }

        /** Trimmed stdout, never null. */
        public String output() {
            return stdout == null ? "" : stdout.trim();
        }

        public static Result failure(String message) {
            return new Result(-1, "", message);
        }
    }
}
