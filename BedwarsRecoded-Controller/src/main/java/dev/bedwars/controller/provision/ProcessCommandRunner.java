package dev.bedwars.controller.provision;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * {@link CommandRunner} backed by {@link ProcessBuilder}. Both output streams are
 * drained on virtual threads so a chatty command can never deadlock on a full pipe,
 * and every command is bounded by a timeout &mdash; infrastructure calls must not be
 * able to hang the controller forever.
 */
public final class ProcessCommandRunner implements CommandRunner, AutoCloseable {

    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();
    private final long timeoutMillis;

    public ProcessCommandRunner(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public Result run(List<String> command) {
        if (command == null || command.isEmpty()) {
            return Result.failure("empty command");
        }
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            // The binary is missing (e.g. docker not installed): a failure, not a crash.
            return Result.failure(e.toString());
        }
        CompletableFuture<String> out = drain(process.getInputStream());
        CompletableFuture<String> err = drain(process.getErrorStream());
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return Result.failure("timed out after " + timeoutMillis + "ms");
            }
            return new Result(process.exitValue(), out.join(), err.join());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return Result.failure("interrupted");
        }
    }

    private CompletableFuture<String> drain(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream in = stream) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }, streams);
    }

    @Override
    public void close() {
        streams.close();
    }
}
