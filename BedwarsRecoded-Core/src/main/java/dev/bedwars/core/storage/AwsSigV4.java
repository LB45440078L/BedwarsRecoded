package dev.bedwars.core.storage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * AWS Signature Version 4 request signing for S3-compatible storage (AWS S3,
 * MinIO, Ceph, SeaweedFS). Dependency-free: only the JDK's {@code javax.crypto}.
 *
 * <p>Implements the documented algorithm
 * ({@code AWS4-HMAC-SHA256}):
 * <pre>
 *   canonicalRequest = METHOD \n URI \n query \n canonicalHeaders \n signedHeaders \n payloadHash
 *   stringToSign     = "AWS4-HMAC-SHA256" \n amzDate \n scope \n SHA256(canonicalRequest)
 *   signingKey       = HMAC(HMAC(HMAC(HMAC("AWS4"+secret, date), region), service), "aws4_request")
 *   signature        = HEX(HMAC(signingKey, stringToSign))
 * </pre>
 *
 * <p>Verified against the published AWS test vector for {@code get-vanilla}
 * (see {@code AwsSigV4Test}).
 */
public final class AwsSigV4 {

    public static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String TERMINATOR = "aws4_request";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private AwsSigV4() {
    }

    /** Headers to attach to a signed S3 request. */
    public record SignedHeaders(String authorization, String amzDate, String contentSha256) {
    }

    /**
     * Signs a GET for {@code host + path} (no query string) with an empty payload,
     * which is exactly the shape of an S3 GetObject.
     */
    public static SignedHeaders signGet(String host, String path, String region, String service,
                                        String accessKey, String secretKey, Instant now) {
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);
        String payloadHash = sha256Hex(new byte[0]);

        String canonicalHeaders = "host:" + host + "\n" + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";

        String canonicalRequest = "GET\n" + path + "\n" + "\n" + canonicalHeaders + "\n"
                + signedHeaders + "\n" + payloadHash;

        String scope = dateStamp + "/" + region + "/" + service + "/" + TERMINATOR;
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest);

        byte[] signingKey = signingKey(secretKey, dateStamp, region, service);
        String signature = HexFormat.of().formatHex(hmac(signingKey, stringToSign));

        String authorization = ALGORITHM + " Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        return new SignedHeaders(authorization, amzDate, payloadHash);
    }

    /** The derived signing key: HMAC chain over date, region, service, terminator. */
    public static byte[] signingKey(String secretKey, String dateStamp, String region, String service) {
        byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmac(kSecret, dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        return hmac(kService, TERMINATOR);
    }

    /** The final signature over a canonical request, given its date stamp and scope. */
    public static String signature(String canonicalRequest, String amzDate, String dateStamp,
                                   String region, String service, String secretKey) {
        String scope = dateStamp + "/" + region + "/" + service + "/" + TERMINATOR;
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest);
        return HexFormat.of().formatHex(hmac(signingKey(secretKey, dateStamp, region, service), stringToSign));
    }

    /** Canonicalises a header map: lower-cased names, trimmed values, sorted by name. */
    public static Map<String, String> canonicalHeaders(Map<String, String> headers) {
        Map<String, String> canonical = new TreeMap<>();
        headers.forEach((name, value) -> canonical.put(name.toLowerCase(java.util.Locale.ROOT), value.trim()));
        return canonical;
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}