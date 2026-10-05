package dev.bedwars.core.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signature Version 4 correctness. The published AWS {@code get-vanilla} vector is
 * used because it pins the whole HMAC chain — if any step (date/region/service/
 * terminator) is wrong, the signature changes.
 */
class AwsSigV4Test {

    // The credentials from the published AWS SigV4 test suite.
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";

    @Test
    void emptyPayloadHashMatchesTheKnownSha256() {
        // Any SigV4 mismatch usually starts here.
        assertThat(AwsSigV4.sha256Hex(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void reproducesThePublishedAwsGetVanillaSignature() {
        String canonicalRequest = "GET\n"
                + "/\n"
                + "\n"
                + "host:example.amazonaws.com\n"
                + "x-amz-date:20150830T123600Z\n"
                + "\n"
                + "host;x-amz-date\n"
                + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        String signature = AwsSigV4.signature(
                canonicalRequest, "20150830T123600Z", "20150830", "us-east-1", "service", SECRET);

        assertThat(signature)
                .isEqualTo("5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");
    }

    @Test
    void signingKeyIsADeterministic32ByteHmacChain() {
        byte[] key = AwsSigV4.signingKey(SECRET, "20150830", "us-east-1", "s3");

        assertThat(key).hasSize(32);
        assertThat(key).isEqualTo(AwsSigV4.signingKey(SECRET, "20150830", "us-east-1", "s3"));
        // A different region/service must derive a different key.
        assertThat(key).isNotEqualTo(AwsSigV4.signingKey(SECRET, "20150830", "eu-west-1", "s3"));
        assertThat(key).isNotEqualTo(AwsSigV4.signingKey(SECRET, "20150830", "us-east-1", "service"));
    }

    @Test
    void signedGetCarriesTheScopeAndSignedHeaders() {
        AwsSigV4.SignedHeaders headers = AwsSigV4.signGet(
                "minio:9000", "/bedwars-templates/templates/Glacier/1.0.0.slime",
                "us-east-1", "s3", "minioadmin", "minioadmin", java.time.Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(headers.authorization())
                .startsWith("AWS4-HMAC-SHA256 Credential=minioadmin/20260101/us-east-1/s3/aws4_request")
                .contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date")
                .contains("Signature=");
        assertThat(headers.amzDate()).isEqualTo("20260101T000000Z");
        assertThat(headers.contentSha256()).isEqualTo(AwsSigV4.sha256Hex(new byte[0]));
    }

    @Test
    void canonicalHeaderMapIsLowerCasedTrimmedAndSorted() {
        var canonical = AwsSigV4.canonicalHeaders(java.util.Map.of(
                "X-Amz-Date", " 20150830T123600Z ", "host", "example.amazonaws.com"));

        assertThat(canonical).containsExactly(
                java.util.Map.entry("host", "example.amazonaws.com"),
                java.util.Map.entry("x-amz-date", "20150830T123600Z"));
    }
}