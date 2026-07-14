package com.tfyre.bambu.printer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * Publishes farm events (print finished/failed, printer errors, maintenance due) to an MQTT broker and/or a webhook so they can be consumed by Home Assistant,
 * Discord, ntfy etc - independent of any open browser tab.
 *
 * MQTT topics: {topic}/{printer}/{event} with a JSON payload.
 */
@ApplicationScoped
public class NotificationService {

    public record FarmEvent(String timestamp, String event, String printer, String message) {

    }

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;
    @Inject
    ManagedExecutor executor;
    @Inject
    BambuPrinters printers;
    @Inject
    MaintenanceService maintenanceService;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Integer> lastErrors = new HashMap<>();
    private final Set<String> maintenanceNotified = new HashSet<>();
    private MqttClient mqtt;

    private static final String SUPPRESSED_FILENAME = "bambu-notification-suppressed.json";

    /** Events suppressed at runtime (toggled from the Notification Settings view). Persisted across restarts. */
    private final java.util.concurrent.CopyOnWriteArraySet<String> suppressedEvents = new java.util.concurrent.CopyOnWriteArraySet<>();

    public void suppressEvent(final String event) {
        if (suppressedEvents.add(event)) {
            saveSuppressed();
        }
    }

    public void unsuppressEvent(final String event) {
        if (suppressedEvents.remove(event)) {
            saveSuppressed();
        }
    }

    public boolean isEventSuppressed(final String event) { return suppressedEvents.contains(event); }

    private Path getSuppressedPath() {
        final Path parent = Path.of(config.maintenanceFile()).getParent();
        return parent != null ? parent.resolve(SUPPRESSED_FILENAME) : Path.of(SUPPRESSED_FILENAME);
    }

    @PostConstruct
    void loadSuppressed() {
        final Path path = getSuppressedPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            suppressedEvents.addAll(mapper.readValue(path.toFile(), new TypeReference<List<String>>() {}));
            Log.infof("NotificationService: %d suppressed event(s) restored from %s", suppressedEvents.size(), path);
        } catch (IOException ex) {
            Log.errorf(ex, "NotificationService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private void saveSuppressed() {
        final Path path = getSuppressedPath();
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), List.copyOf(suppressedEvents));
        } catch (IOException ex) {
            Log.errorf(ex, "NotificationService: cannot save %s: %s", path, ex.getMessage());
        }
    }

    /** Returns true if at least one delivery channel (webhook or MQTT) is configured. */
    public boolean isConfigured() { return isEnabled(); }

    public void notifyEvent(final String event, final String printer, final String message) {
        notifyEvent(event, printer, message, null);
    }

    /**
     * Like {@link #notifyEvent(String, String, String)} but with an optional JPEG snapshot attached to the
     * webhook delivery (Discord: multipart file upload; ntfy: attachment; generic webhook and MQTT: image is
     * skipped, JSON payload unchanged). Used by the AI checks so a failure alert shows the actual camera frame.
     */
    public void notifyEvent(final String event, final String printer, final String message, final byte[] imageJpeg) {
        if (suppressedEvents.contains(event)) {
            Log.debugf("NotificationService: suppressed event '%s' for %s", event, printer);
            return;
        }
        final FarmEvent farmEvent = new FarmEvent(OffsetDateTime.now().toString(), event, printer, message);
        executor.submit(() -> {
            publishMqtt(farmEvent);
            publishWebhook(farmEvent, imageJpeg);
        });
    }

    private synchronized void publishMqtt(final FarmEvent event) {
        final Optional<String> url = config.notifications().mqtt().url();
        if (url.isEmpty()) {
            return;
        }
        try {
            if (mqtt == null) {
                mqtt = new MqttClient(url.get(), "bambufarm-notify-%d".formatted(System.nanoTime()), new MemoryPersistence());
            }
            if (!mqtt.isConnected()) {
                final MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(true);
                options.setConnectionTimeout(5);
                config.notifications().mqtt().username().ifPresent(options::setUserName);
                config.notifications().mqtt().password().ifPresent(p -> options.setPassword(p.toCharArray()));
                mqtt.connect(options);
            }
            final String topic = "%s/%s/%s".formatted(config.notifications().mqtt().topic(), event.printer(), event.event());
            mqtt.publish(topic, mapper.writeValueAsBytes(event), 0, false);
        } catch (Exception ex) {
            Log.errorf(ex, "NotificationService: mqtt publish failed: %s", ex.getMessage());
        }
    }

    private void publishWebhook(final FarmEvent event, final byte[] imageJpeg) {
        final Optional<String> url = config.notifications().webhookUrl();
        if (url.isEmpty()) {
            return;
        }
        try {
            final String format = config.notifications().webhookFormat();
            if (imageJpeg != null && "discord".equals(format)) {
                sendDiscordWithImage(url.get(), event, imageJpeg);
                return;
            }
            if (imageJpeg != null && "ntfy".equals(format)) {
                sendNtfyWithImage(url.get(), event, imageJpeg);
                return;
            }
            final String body;
            final String contentType;
            switch (format) {
                case "discord" -> {
                    body = mapper.writeValueAsString(Map.of("content", "**%s** %s".formatted(event.printer(), event.message())));
                    contentType = "application/json";
                }
                case "ntfy" -> {
                    body = "%s: %s".formatted(event.printer(), event.message());
                    contentType = "text/plain";
                }
                default -> {
                    body = mapper.writeValueAsString(event);
                    contentType = "application/json";
                }
            }
            final HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url.get()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                Log.errorf("NotificationService: webhook HTTP %d: %s", response.statusCode(), response.body());
            }
        } catch (Exception ex) {
            Log.errorf(ex, "NotificationService: webhook failed: %s", ex.getMessage());
        }
    }

    /** Discord: multipart/form-data with a payload_json part and the snapshot as files[0], per their webhook API. */
    private void sendDiscordWithImage(final String url, final FarmEvent event, final byte[] imageJpeg) throws Exception {
        final String boundary = "bambufarm" + System.nanoTime();
        final String payloadJson = mapper.writeValueAsString(Map.of("content", "**%s** %s".formatted(event.printer(), event.message())));
        final String head = "--%s\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n%s\r\n--%s\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"snapshot.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"
                .formatted(boundary, payloadJson, boundary);
        final String tail = "\r\n--%s--\r\n".formatted(boundary);
        final HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(List.of(
                        head.getBytes(StandardCharsets.UTF_8), imageJpeg, tail.getBytes(StandardCharsets.UTF_8))))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            Log.errorf("NotificationService: discord webhook (with image) HTTP %d: %s", response.statusCode(), response.body());
        }
    }

    /**
     * ntfy: binary body = attachment, message/title via headers. Header values must be ISO-8859-1-safe, so the
     * text is reduced to ASCII (the full message still goes out via MQTT/logs regardless).
     */
    private void sendNtfyWithImage(final String url, final FarmEvent event, final byte[] imageJpeg) throws Exception {
        final String title = asciiOnly("%s: %s".formatted(event.printer(), event.message()));
        final HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Filename", "snapshot.jpg")
                .header("X-Title", title.substring(0, Math.min(title.length(), 250)))
                .POST(HttpRequest.BodyPublishers.ofByteArray(imageJpeg))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            Log.errorf("NotificationService: ntfy webhook (with image) HTTP %d: %s", response.statusCode(), response.body());
        }
    }

    private static String asciiOnly(final String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        for (final char c : s.toCharArray()) {
            sb.append(c >= 32 && c < 127 ? c : '?');
        }
        return sb.toString();
    }

    private boolean isEnabled() {
        return config.notifications().mqtt().url().isPresent() || config.notifications().webhookUrl().isPresent();
    }

    @Scheduled(every = "30s")
    synchronized void watchErrors() {
        if (!isEnabled()) {
            return;
        }
        printers.getPrinters().forEach(printer -> {
            final int error = printer.getPrintError();
            final Integer previous = lastErrors.put(printer.getName(), error);
            if (previous == null || previous == error || error == 0) {
                return;
            }
            notifyEvent("error", printer.getName(), "Print error [%s]: %s".formatted(
                    Integer.toHexString(error), BambuErrors.getPrinterError(error).orElse("Unknown")));
        });
    }

    @Scheduled(every = "6h")
    synchronized void watchMaintenance() {
        if (!isEnabled()) {
            return;
        }
        printers.getPrinters().forEach(printer ->
                maintenanceService.getTaskStatus(printer.getName()).stream()
                        .filter(MaintenanceService.TaskStatus::overdue)
                        .forEach(ts -> {
                            final String key = "%s|%s|%.1f".formatted(printer.getName(), ts.task().name(), ts.task().lastDoneHours());
                            if (!maintenanceNotified.add(key)) {
                                return;
                            }
                            notifyEvent("maintenance", printer.getName(), "Maintenance due: %s (%.1fh since last done)"
                                    .formatted(ts.task().name(), ts.hoursSince()));
                        }));
    }

}
