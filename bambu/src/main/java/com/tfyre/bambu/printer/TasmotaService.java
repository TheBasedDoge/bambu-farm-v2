package com.tfyre.bambu.printer;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * Controls Tasmota smart plugs assigned to printers (bambu.printers.XXX.tasmota=http://ip).
 */
@ApplicationScoped
public class TasmotaService {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Inject
    ManagedExecutor executor;

    /**
     * Identifies a Tasmota outlet.
     *
     * @param baseUrl the base URL of the Tasmota device, e.g. {@code http://192.168.1.50}
     * @param channel outlet channel: 0 (or absent) = single-outlet device;
     *                1, 2, 3… = specific outlet on a multi-outlet power strip
     */
    public record TasmotaTarget(String baseUrl, int channel) {

        /** Build a target from config values. */
        public static TasmotaTarget of(final String baseUrl, final Optional<Integer> channel) {
            return new TasmotaTarget(baseUrl, channel.orElse(0));
        }

        /**
         * The Tasmota power command name for this outlet: {@code "Power"} for single-outlet,
         * {@code "Power1"}, {@code "Power2"}, … for multi-outlet strips.
         */
        public String powerCommand() {
            return channel > 0 ? "Power" + channel : "Power";
        }

        /** Human-readable label, e.g. {@code "http://…"} or {@code "http://… ch2"}. */
        public String label() {
            return channel > 0 ? "%s ch%d".formatted(baseUrl, channel) : baseUrl;
        }
    }

    private String normalizeUrl(final String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * Queries the current power state. Callback receives Optional.of(true/false) for ON/OFF,
     * or Optional.empty() on error. Runs on a worker thread — wrap callback with ui.access().
     */
    public void getStatus(final TasmotaTarget target, final Consumer<Optional<Boolean>> callback) {
        final String url = "%s/cm?cmnd=%s".formatted(normalizeUrl(target.baseUrl()), target.powerCommand());
        executor.submit(() -> {
            try {
                final HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    // Single: {"POWER":"ON"}  Multi: {"POWER2":"ON"} — just look for the value
                    final boolean on = response.body().toUpperCase().contains("\"ON\"");
                    callback.accept(Optional.of(on));
                } else {
                    Log.warnf("TasmotaService: getStatus HTTP %d from %s", response.statusCode(), url);
                    callback.accept(Optional.empty());
                }
            } catch (Exception ex) {
                Log.errorf(ex, "TasmotaService: getStatus %s: %s", url, ex.getMessage());
                callback.accept(Optional.empty());
            }
        });
    }

    /**
     * Sends a power command. Callbacks run on a worker thread - wrap with ui.access().
     */
    public void power(final TasmotaTarget target, final boolean on, final Runnable onSuccess, final Consumer<String> onError) {
        final String url = "%s/cm?cmnd=%s".formatted(
                normalizeUrl(target.baseUrl()),
                URLEncoder.encode(target.powerCommand() + " " + (on ? "On" : "Off"), StandardCharsets.UTF_8));
        executor.submit(() -> {
            try {
                final HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    Log.infof("TasmotaService: %s -> %s", url, response.body());
                    onSuccess.run();
                } else {
                    onError.accept("HTTP %d".formatted(response.statusCode()));
                }
            } catch (Exception ex) {
                Log.errorf(ex, "TasmotaService: %s: %s", url, ex.getMessage());
                onError.accept(ex.getMessage());
            }
        });
    }

}
