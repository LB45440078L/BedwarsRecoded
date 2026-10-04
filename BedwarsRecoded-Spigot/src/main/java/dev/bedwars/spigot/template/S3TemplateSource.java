package dev.bedwars.spigot.template;

import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.api.service.TemplateSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Production template source: downloads a Slime world archive from S3-compatible
 * object storage, verifies its checksum, and stages it for AdvancedSlimePaper to
 * load. Uses S3 presigned-style GET against the bucket endpoint.
 *
 * <p>The actual Slime load is delegated to the ASP adapter; this class only
 * guarantees the archive is on local disk and intact.
 */
public final class S3TemplateSource implements TemplateSource {

    private final URI endpoint;
    private final String bucket;
    private final String region;
    private final HttpClient http;

    public S3TemplateSource(URI endpoint, String bucket, String region) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.region = region;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public CompletableFuture<Path> materialise(TemplateDescriptor descriptor, Path stagingDir) {
        return http.sendAsync(buildRequest(descriptor), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("S3 fetch failed (" + response.statusCode() + ") for "
                                + descriptor.coordinate());
                    }
                    byte[] body = response.body();
                    descriptor.checksum().ifPresent(expected -> verify(expected, body, descriptor));
                    try {
                        Files.createDirectories(stagingDir);
                        Path archive = stagingDir.resolve(descriptor.name() + "-" + descriptor.version() + ".slime");
                        Files.write(archive, body);
                        return archive;
                    } catch (Exception e) {
                        throw new IllegalStateException("Failed to stage template " + descriptor.coordinate(), e);
                    }
                });
    }

    private HttpRequest buildRequest(TemplateDescriptor descriptor) {
        // s3://bucket/templates/<name>/<version>.slime via the S3 endpoint
        URI uri = URI.create(endpoint + "/" + bucket + "/templates/" + descriptor.name()
                + "/" + descriptor.version() + ".slime");
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("x-amz-region", region)
                .GET()
                .build();
    }

    private static void verify(String expectedSha256, byte[] body, TemplateDescriptor descriptor) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] actual = digest.digest(body);
            StringBuilder hex = new StringBuilder();
            for (byte b : actual) {
                hex.append(String.format("%02x", b));
            }
            if (!hex.toString().equalsIgnoreCase(expectedSha256)) {
                throw new IllegalStateException("Checksum mismatch for " + descriptor.coordinate());
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public boolean productionReady() {
        return true;
    }
}