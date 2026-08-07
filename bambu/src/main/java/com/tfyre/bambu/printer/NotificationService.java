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
import java.util.ArrayList;
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
    /** Lazy to avoid an eager circular reference (PrintAiService injects this service). Used for error-alert photos. */
    @Inject
    jakarta.enterprise.inject.Instance<PrintAiService> aiServiceInstance;

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
        reportLinkButtons();
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

    /**
     * Says at startup whether alerts will carry link buttons.
     * <p>
     * Because the absence was silent, and silent absences cost a round trip: alerts went out with no buttons,
     * the Test button produced a plain message, and the only way to find out why was to read the source. One
     * line at startup answers it. Same lesson as the timezone - a feature that is off because it was never
     * configured should say so, not just not happen.
     */
    private void reportLinkButtons() {
        if (config.notifications().webhookUrl().isEmpty()) {
            return;
        }
        config.notifications().baseUrl()
                .map(String::strip)
                .filter(b -> !b.isEmpty())
                .ifPresentOrElse(
                        b -> Log.infof("NotificationService: alerts will link back to %s", b),
                        () -> Log.infof("NotificationService: alerts will have NO link buttons - set "
                                + "bambu.notifications.base-url to this app's external URL to add them"));
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
    /**
     * One button on an alert. Always a <b>link</b>, never an action.
     * <p>
     * A Discord incoming webhook is one-way. It will happily render an action button, but Discord only delivers
     * the interaction for application-owned webhooks - so a "Pause" button on these messages would look real,
     * do nothing, and teach you not to trust the alert. A link button (style 5) fires no interaction at all: it
     * just opens a URL, which works from a plain webhook and gets you to the page that CAN act in one tap.
     */
    public record Link(String label, String url) {

    }

    /**
     * The links worth offering for an event, deepest-first.
     * <p>
     * Derived centrally from the event name rather than passed in by 25 call sites: the alert should get more
     * useful without every caller having to remember to make it so, and a caller that forgets is a caller whose
     * alert is a dead end. Empty when no base URL is configured - a button pointing at {@code localhost} is
     * worse than no button, because it fails on the one device you are holding.
     */
    private List<Link> linksFor(final String event, final String printer) {
        final Optional<String> base = config.notifications().baseUrl()
                .map(String::strip)
                .filter(b -> !b.isEmpty())
                .map(b -> b.endsWith("/") ? b.substring(0, b.length() - 1) : b);
        if (base.isEmpty()) {
            return List.of();
        }
        final String root = base.get();
        final List<Link> links = new ArrayList<>();
        // The printer's own page first, where Pause and Stop live - the reason most of these alerts exist.
        final boolean aboutAPrinter = printers.getPrinterDetail(printer).isPresent();
        if (aboutAPrinter) {
            links.add(new Link("Open " + printer, "%s/printer/%s".formatted(root, encode(printer))));
        }
        switch (event) {
            case "failure_detected", "first_layer_issue" ->
                links.add(new Link("AI checks", root + "/ai-settings"));
            case "new_order", "auto_queue", "auto_queue_skipped", "order_printed", "order_needs_requeue",
                    "order_from_stock" ->
                links.add(new Link("%s orders".formatted(printer),
                        "%s/%s-orders".formatted(root, "eBay".equalsIgnoreCase(printer) ? "ebay" : "etsy")));
            case "dispatch_blocked", "auto_requeue", "simulate_mode", "poll_failed" ->
                links.add(new Link("Automation", root + "/automation"));
            case "spool_low" ->
                links.add(new Link("Spools", root + "/spools"));
            case "maintenance" ->
                links.add(new Link("Maintenance", root + "/maintenance"));
            case "tasmota_off" ->
                links.add(new Link("Plugs", root + "/tasmota-settings"));
            default -> {
            }
        }
        // The wall display last, as the general "what is the farm doing" answer. Capped at three: Discord allows
        // five per row, but a wall of buttons is a thing you scroll past rather than press.
        if (links.size() < 3) {
            links.add(new Link("Overview", root + "/overview"));
        }
        return List.copyOf(links);
    }

    private static String encode(final String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public void notifyEvent(final String event, final String printer, final String message, final byte[] imageJpeg) {
        if (suppressedEvents.contains(event)) {
            Log.debugf("NotificationService: suppressed event '%s' for %s", event, printer);
            return;
        }
        final FarmEvent farmEvent = new FarmEvent(OffsetDateTime.now().toString(), event, printer, message);
        final List<Link> links = linksFor(event, printer);
        executor.submit(() -> {
            publishMqtt(farmEvent);
            publishWebhook(farmEvent, imageJpeg, links);
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

    private void publishWebhook(final FarmEvent event, final byte[] imageJpeg, final List<Link> links) {
        final Optional<String> url = config.notifications().webhookUrl();
        if (url.isEmpty()) {
            return;
        }
        try {
            final String format = config.notifications().webhookFormat();
            if (imageJpeg != null && "discord".equals(format)) {
                sendDiscordWithImage(withComponents(url.get(), links), event, imageJpeg, links);
                return;
            }
            if (imageJpeg != null && "ntfy".equals(format)) {
                sendNtfyWithImage(url.get(), event, imageJpeg, links);
                return;
            }
            final String body;
            final String contentType;
            switch (format) {
                case "discord" -> {
                    body = mapper.writeValueAsString(discordPayload(event, links));
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
            final String target = "discord".equals(format) ? withComponents(url.get(), links) : url.get();
            final HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(target))
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

    /**
     * Adds {@code with_components=true} to a Discord webhook URL when the message carries buttons.
     * <p>
     * <b>Without this Discord accepts the message, returns 2xx, and silently drops the components.</b> No error,
     * no warning, nothing in any log - the message simply arrives with no buttons, which is indistinguishable
     * from not having sent any. It cost a deploy and two rounds of "it just does this" to find, because every
     * check I had said the send succeeded.
     * <p>
     * Appended with the right separator: a webhook URL may already carry a query string ({@code ?thread_id=…}),
     * and blindly adding "?" would produce a URL Discord rejects.
     */
    private static String withComponents(final String url, final List<Link> links) {
        if (links.isEmpty() || url.contains("with_components=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "with_components=true";
    }

    /**
     * The Discord message body: content, plus an action row of <b>link</b> buttons when a base URL is set.
     * <p>
     * Type 1 is an action row, type 2 a button, style 5 a link button. Style 5 is the only style a plain
     * incoming webhook can usefully send: every other style produces an interaction that Discord will only
     * deliver to an application-owned webhook, so the button would appear to work and then do nothing.
     */
    private Map<String, Object> discordPayload(final FarmEvent event, final List<Link> links) {
        final String content = "**%s** %s".formatted(event.printer(), event.message());
        if (links.isEmpty()) {
            return Map.<String, Object>of("content", content);
        }
        final List<Map<String, Object>> buttons = links.stream()
                .map(l -> Map.<String, Object>of("type", 2, "style", 5, "label", l.label(), "url", l.url()))
                .toList();
        // Explicit type arguments throughout: Map.of with heterogeneous values infers the least upper bound of
        // those value types, which is not Map<String, Object>, and generics are invariant. Spelling it out costs
        // nothing and removes a class of error I cannot see without a compiler.
        return Map.<String, Object>of("content", content,
                "components", List.<Map<String, Object>>of(Map.<String, Object>of("type", 1, "components", buttons)));
    }

    /** Discord: multipart/form-data with a payload_json part and the snapshot as files[0], per their webhook API. */
    private void sendDiscordWithImage(final String url, final FarmEvent event, final byte[] imageJpeg,
            final List<Link> links) throws Exception {
        final String boundary = "bambufarm" + System.nanoTime();
        final String payloadJson = mapper.writeValueAsString(discordPayload(event, links));
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
    private void sendNtfyWithImage(final String url, final FarmEvent event, final byte[] imageJpeg,
            final List<Link> links) throws Exception {
        final String title = asciiOnly("%s: %s".formatted(event.printer(), event.message()));
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Filename", "snapshot.jpg")
                .header("X-Title", title.substring(0, Math.min(title.length(), 250)))
                .POST(HttpRequest.BodyPublishers.ofByteArray(imageJpeg));
        ntfyActions(links).ifPresent(a -> request.header("Actions", a));
        final HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            Log.errorf("NotificationService: ntfy webhook (with image) HTTP %d: %s", response.statusCode(), response.body());
        }
    }

    /**
     * ntfy's equivalent: a comma-separated {@code Actions} header of {@code view, <label>, <url>} entries.
     * <p>
     * Commas and semicolons separate fields in that header, so a label containing either would silently split
     * into nonsense - printer names are user-supplied, so they are stripped rather than trusted. ASCII-only for
     * the same reason the title is: HTTP header values are ISO-8859-1.
     */
    private static Optional<String> ntfyActions(final List<Link> links) {
        if (links.isEmpty()) {
            return Optional.empty();
        }
        final String header = links.stream()
                .map(l -> "view, %s, %s".formatted(asciiOnly(l.label()).replaceAll("[,;]", " ").strip(), l.url()))
                .collect(java.util.stream.Collectors.joining("; "));
        return header.isBlank() ? Optional.empty() : Optional.of(header);
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
            // Attach the camera frame so the Discord/ntfy alert shows what the printer looks like right now
            notifyEvent("error", printer.getName(), "Print error [%s]: %s".formatted(
                    Integer.toHexString(error), BambuErrors.getPrinterError(error).orElse("Unknown")),
                    aiServiceInstance.get().getSnapshot(printer.getName()).orElse(null));
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
