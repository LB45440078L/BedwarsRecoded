package dev.bedwars.spigot.template;

import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.api.service.TemplateSource;
import dev.bedwars.core.storage.AwsSigV4;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Production template source: downloads a Slime world archive from S3-compatible
 * object storage, verifies its checksum, and stages it for the Slime loader.
 *
 * <p>When credentials are configured the request is signed with
 * <b>AWS Signature Version 4</b> ({@link AwsSigV4}), which is what real S3, MinIO
 * and Ceph require. Without credentials the request is unsigned, which is only
 * valid for public buckets and local development.
 *
 * <p>Object layout: {@code <endpoint>/<bucket>/templates/<name>/<version>.slime}
 * (path-style addressing, so it works against MinIO and friends).
 */
public final class S3TemplateSource implements TemplateSource {

    private final URI endpoint;
    private final String bucket;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final HttpClient http;

    /** Anonymous access — public buckets and local development only. */
    public S3TemplateSource(URI endpoint, String bucket, String region) {
        this(endpoint, bucket, region, null, null);
    }

    public S3TemplateSource(URI endpoint, String bucket, String region, String accessKey, String secretKey) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.region = region;
        this.accessKey = accessKey == null || accessKey.isBlank() ? null : accessKey;
        this.secretKey = secretKey == null || secretKey.isBlank() ? null : secretKey;
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
        String path = "/" + bucket + "/templates/" + descriptor.name() + "/" + descriptor.version() + ".slime";
        URI uri = URI.create(endpoint + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));

        if (accessKey != null && secretKey != null) {
            String host = uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
            AwsSigV4.SignedHeaders signed =
                    AwsSigV4.signGet(host, path, region, "s3", accessKey, secretKey, Instant.now());
            builder.header("Authorization", signed.authorization())
                    .header("x-amz-date", signed.amzDate())
                    .header("x-amz-content-sha256", signed.contentSha256());
        }
        return builder.GET().build();
    }

    private static void verify(String expectedSha256, byte[] body, TemplateDescriptor descriptor) {
        String actual = AwsSigV4.sha256Hex(body);
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            throw new IllegalStateException("Checksum mismatch for " + descriptor.coordinate());
        }
    }

    @Override
    public boolean productionReady() {
        return true;
    }
}