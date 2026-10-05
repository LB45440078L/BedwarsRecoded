package dev.bedwars.spigot.report;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Answers one question at boot: is a controller actually listening?
 *
 * <p>Used by {@code deployment.mode: AUTO} so a plugin dropped into an ordinary
 * server (or a pod on a cluster whose controller is not up yet) stops trying to
 * report to something that is not there, instead of logging connection failures.
 */
public final class ControllerProbe {

    private ControllerProbe() {
    }

    /** True when {@code <baseUrl>/healthz} answers 200 within the timeout. */
    public static boolean isReachable(String baseUrl, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(root + "/healthz"))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}