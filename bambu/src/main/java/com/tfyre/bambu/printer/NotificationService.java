package com.tfyre.bambu.printer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
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

    /** Events suppressed at runtime (toggled from the Notification Settings view). */
    private final java.util.concurrent.CopyOnWriteArraySet<String> suppressedEvents = new java.util.concurrent.CopyOnWriteArraySet<>();

    public void suppressEvent(final String event) { suppressedEvents.add(event); }
    public void unsuppressEvent(final String event) { suppressedEvents.remove(event); }
    public boolean isEventSuppressed(final String event) { return suppressedEvents.contains(event); }

    /** Returns true if at least one delivery channel (webhook or MQTT) is configured. */
    public boolean isConfigured() { return isEnabled(); }

    public void notifyEvent(final String event, final String printer, final String message) {
        if (suppressedEvents.contains(event)) {
            Log.debugf("NotificationService: suppressed event '%s' for %s", event, printer);
            return;
        }
        final FarmEvent farmEvent = new FarmEvent(OffsetDateTime.now().toString(), event, printer, message);
        executor.submit(() -> {
            publishMqtt(farmEvent);
            publishWebhook(farmEvent);
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

    private void publishWebhook(final FarmEvent event) {
        final Optional<String> url = config.notifications().webhookUrl();
        if (url.isEmpty()) {
            return;
        }
        try {
            final String body;
            final String contentType;
            switch (config.notifications().webhookFormat()) {
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
