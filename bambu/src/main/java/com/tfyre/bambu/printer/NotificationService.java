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

    // -------------------------------------------------------------------------
    // Notification log - what the Notifications page shows
    // -------------------------------------------------------------------------

    private static final String LOG_FILENAME = "bambu-notification-log.json";
    /** Entries kept and persisted. A busy day is about 40 events, so this is a couple of weeks. */
    private static final int LOG_MAX = 500;
    /** Photos stay in memory only, for the newest entries - they are 60-500 KB each and not worth a file. */
    private static final int LOG_PHOTOS_MAX = 40;
    public static final String STATUS_PENDING = "sending";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_SUPPRESSED = "suppressed";
    public static final String STATUS_NO_CHANNEL = "not sent - no channel configured";
    public static final String STATUS_FAILED_PREFIX = "failed - ";

    /**
     * One notification as the farm raised it: the same text Discord/ntfy/MQTT got, plus what became of it.
     * {@code photo} says a camera frame went with it; the frame itself is only held for the newest few
     * ({@link #getLogPhoto(long)}).
     */
    public record LogEntry(long id, String timestamp, String event, String printer, String message, boolean photo,
            String status) {

        LogEntry withStatus(final String newStatus) {
            return new LogEntry(id, timestamp, event, printer, message, photo, newStatus);
        }
    }

    /** Oldest first. Guarded by itself, as is {@link #logPhotos}. */
    private final List<LogEntry> eventLog = new ArrayList<>();
    private final java.util.LinkedHashMap<Long, byte[]> logPhotos = new java.util.LinkedHashMap<Long, byte[]>() {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Long, byte[]> eldest) {
            return size() > LOG_PHOTOS_MAX;
        }
    };
    /** Seeded from the clock so ids stay unique (and ordered) across restarts without persisting a counter. */
    private final java.util.concurrent.atomic.AtomicLong logSeq
            = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    private final List<Runnable> logListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Object logFileLock = new Object();

    /** Every logged notification, newest first. */
    public List<LogEntry> getLog() {
        synchronized (eventLog) {
            final List<LogEntry> copy = new ArrayList<>(eventLog);
            java.util.Collections.reverse(copy);
            return copy;
        }
    }

    /** The camera frame sent with an entry, while it is still one of the newest {@value #LOG_PHOTOS_MAX} with a photo. */
    public Optional<byte[]> getLogPhoto(final long id) {
        synchronized (eventLog) {
            return Optional.ofNullable(logPhotos.get(id));
        }
    }

    /** Calls {@code listener} (on a worker thread) whenever the log changes. Run the returned handle to stop. */
    public Runnable addLogListener(final Runnable listener) {
        logListeners.add(listener);
        return () -> logListeners.remove(listener);
    }

    private void fireLogListeners() {
        for (final Runnable listener : logListeners) {
            try {
                listener.run();
            } catch (RuntimeException ex) {
                // A closed browser tab must not be able to break delivery of the next alert.
                Log.debugf("NotificationService: log listener failed: %s", ex.getMessage());
            }
        }
    }

    private Path getLogPath() {
        final Path parent = Path.of(config.maintenanceFile()).getParent();
        return parent != null ? parent.resolve(LOG_FILENAME) : Path.of(LOG_FILENAME);
    }

    private void loadLog() {
        final Path path = getLogPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            final List<LogEntry> loaded = mapper.readValue(path.toFile(), new TypeReference<List<LogEntry>>() {});
            synchronized (eventLog) {
                for (final LogEntry e : loaded) {
                    // Nothing is mid-send after a restart; an entry saved as such never got its answer.
                    eventLog.add(STATUS_PENDING.equals(e.status()) ? e.withStatus("unknown - restarted while sending") : e);
                }
                while (eventLog.size() > LOG_MAX) {
                    eventLog.remove(0);
                }
            }
            Log.infof("NotificationService: %d logged notification(s) restored from %s", loaded.size(), path);
        } catch (IOException | RuntimeException ex) {
            Log.errorf(ex, "NotificationService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private void saveLog() {
        final List<LogEntry> copy;
        synchronized (eventLog) {
            copy = new ArrayList<>(eventLog);
        }
        synchronized (logFileLock) {
            try {
                mapper.writeValue(getLogPath().toFile(), copy);
            } catch (IOException ex) {
                Log.errorf(ex, "NotificationService: cannot save %s: %s", getLogPath(), ex.getMessage());
            }
        }
    }

    private LogEntry logEvent(final String event, final String printer, final String message, final byte[] imageJpeg,
            final String status) {
        final LogEntry entry = new LogEntry(logSeq.incrementAndGet(), OffsetDateTime.now().toString(), event,
                printer == null ? "" : printer, message == null ? "" : message, imageJpeg != null, status);
        synchronized (eventLog) {
            eventLog.add(entry);
            while (eventLog.size() > LOG_MAX) {
                logPhotos.remove(eventLog.remove(0).id());
            }
            if (imageJpeg != null) {
                logPhotos.put(entry.id(), imageJpeg);
            }
        }
        return entry;
    }

    /** Records how delivery ended, then persists and tells any open Notifications page. Worker thread only. */
    private void finishLogEntry(final long id, final String status) {
        synchronized (eventLog) {
            for (int i = eventLog.size() - 1; i >= 0; i--) {
                if (eventLog.get(i).id() == id) {
                    eventLog.set(i, eventLog.get(i).withStatus(status));
                    break;
                }
            }
        }
        saveLog();
        fireLogListeners();
    }

    private Path getSuppressedPath() {
        final Path parent = Path.of(config.maintenanceFile()).getParent();
        return parent != null ? parent.resolve(SUPPRESSED_FILENAME) : Path.of(SUPPRESSED_FILENAME);
    }

    @PostConstruct
    void loadSuppressed() {
        reportLinkButtons();
        loadLog();
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
            // The alerts that end with a part (or a failed one) on the plate get the way to say it is off.
            // It opens a page with one confirm button rather than acting on the link itself, so a link
            // preview or a mis-tap cannot open the bed gate.
            switch (event) {
                case "finish", "fail", "stopped", "auto_start_blocked", "dispatch_blocked", "auto_requeue" ->
                    links.add(new Link("Bed cleared", "%s/bed-cleared/%s".formatted(root, encode(printer))));
                default -> {
                }
            }
        }
        switch (event) {
            case "failure_detected", "first_layer_issue" ->
                links.add(new Link("AI checks", root + "/ai-settings"));
            case "new_order", "auto_queue", "auto_queue_skipped", "order_printed", "order_needs_requeue",
                    "order_from_stock" ->
                links.add(new Link("%s orders".formatted(printer),
                        "%s/%s-orders".formatted(root, "eBay".equalsIgnoreCase(printer) ? "ebay" : "etsy")));
            case "dispatch_blocked", "auto_requeue", "ams_retry", "simulate_mode", "poll_failed" ->
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

    /** Collapses a multi-line alert (AI observations run to several lines) into one log line, capped. */
    private static String oneLine(final String message) {
        if (message == null) {
            return "";
        }
        final String flat = message.replaceAll("\\s+", " ").strip();
        return flat.length() > 300 ? flat.substring(0, 297) + "..." : flat;
    }

    private static String encode(final String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public void notifyEvent(final String event, final String printer, final String message, final byte[] imageJpeg) {
        // One line per event, always, whether or not it goes anywhere. Until 2026-09-16 alerts went to Discord
        // and nowhere else, so the log could not answer "how often does the AMS jam" or "when did that printer
        // error" - the only record was a chat channel. Suppressed events are logged too, marked as such.
        final boolean suppressed = suppressedEvents.contains(event);
        Log.infof("NotificationService: event %s [%s]%s %s%s", event, printer, imageJpeg != null ? " +photo" : "",
                oneLine(message), suppressed ? " (suppressed - not sent)" : "");
        if (suppressed) {
            final LogEntry skipped = logEvent(event, printer, message, imageJpeg, STATUS_SUPPRESSED);
            executor.submit(() -> finishLogEntry(skipped.id(), STATUS_SUPPRESSED));
            return;
        }
        final FarmEvent farmEvent = new FarmEvent(OffsetDateTime.now().toString(), event, printer, message);
        final List<Link> links = linksFor(event, printer);
        final LogEntry entry = logEvent(event, printer, message, imageJpeg, STATUS_PENDING);
        executor.submit(() -> {
            final List<String> problems = new ArrayList<>();
            try {
                publishMqtt(farmEvent).ifPresent(problems::add);
                publishWebhook(farmEvent, imageJpeg, links).ifPresent(problems::add);
            } catch (RuntimeException ex) {
                problems.add(String.valueOf(ex.getMessage()));
            }
            finishLogEntry(entry.id(), !problems.isEmpty() ? STATUS_FAILED_PREFIX + String.join("; ", problems)
                    : isEnabled() ? STATUS_SENT : STATUS_NO_CHANNEL);
        });
    }

    /** @return what went wrong, or empty when it was published (or MQTT is not configured). */
    private synchronized Optional<String> publishMqtt(final FarmEvent event) {
        final Optional<String> url = config.notifications().mqtt().url();
        if (url.isEmpty()) {
            return Optional.empty();
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
            return Optional.empty();
        } catch (Exception ex) {
            Log.errorf(ex, "NotificationService: mqtt publish failed: %s", ex.getMessage());
            return Optional.of("MQTT: " + ex.getMessage());
        }
    }

    /** @return what went wrong, or empty when it was delivered (or no webhook is configured). */
    private Optional<String> publishWebhook(final FarmEvent event, final byte[] imageJpeg, final List<Link> links) {
        final Optional<String> url = config.notifications().webhookUrl();
        if (url.isEmpty()) {
            return Optional.empty();
        }
        try {
            final String format = config.notifications().webhookFormat();
            if (imageJpeg != null && "discord".equals(format)) {
                return sendDiscordWithImage(withComponents(url.get(), links), event, imageJpeg, links);
            }
            if (imageJpeg != null && "ntfy".equals(format)) {
                return sendNtfyWithImage(url.get(), event, imageJpeg, links);
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
            final HttpResponse<String> response = sendWithRetry(HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build());
            if (response.statusCode() >= 300) {
                Log.errorf("NotificationService: webhook HTTP %d: %s", response.statusCode(), response.body());
                return Optional.of("webhook HTTP %d".formatted(response.statusCode()));
            }
            return Optional.empty();
        } catch (Exception ex) {
            Log.errorf(ex, "NotificationService: webhook failed: %s", ex.getMessage());
            return Optional.of("webhook: " + ex.getMessage());
        }
    }

    /** How many times a rate-limited (HTTP 429) webhook post is retried before it is given up as failed. */
    private static final int RATE_LIMIT_RETRIES = 3;
    private static final java.util.regex.Pattern RETRY_AFTER
            = java.util.regex.Pattern.compile("\"retry_after\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");

    /**
     * Sends, and on HTTP 429 waits as long as the server asked and sends again.
     * <p>
     * Discord limits a webhook to a handful of posts per couple of seconds. Three printers going offline in the
     * same second is three photo alerts at once, and until 2026-10-04 the ones that drew a 429 were logged and
     * dropped - the alert simply never arrived. The 429 body says how long to wait ({@code retry_after}, in
     * seconds, typically a fraction of one), so waiting is cheap. This runs on the notification worker thread,
     * never on a caller's.
     */
    private HttpResponse<String> sendWithRetry(final HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        for (int attempt = 1; response.statusCode() == 429 && attempt <= RATE_LIMIT_RETRIES; attempt++) {
            final long waitMs = retryAfterMillis(response.body(), response.headers().firstValue("Retry-After").orElse(null));
            Log.infof("NotificationService: webhook rate limited (HTTP 429) - retry %d/%d in %d ms",
                    attempt, RATE_LIMIT_RETRIES, waitMs);
            Thread.sleep(waitMs);
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        return response;
    }

    /**
     * How long to wait after a 429: Discord's JSON {@code retry_after} (seconds, fractional) if present, else
     * the standard {@code Retry-After} header (seconds), else one second. Padded by 100 ms and kept between a
     * quarter of a second and ten seconds so a nonsense value can neither spin nor stall the worker.
     */
    static long retryAfterMillis(final String body, final String retryAfterHeader) {
        double seconds = -1;
        if (body != null) {
            final java.util.regex.Matcher m = RETRY_AFTER.matcher(body);
            if (m.find()) {
                try {
                    seconds = Double.parseDouble(m.group(1));
                } catch (NumberFormatException ex) {
                    seconds = -1;
                }
            }
        }
        if (seconds < 0 && retryAfterHeader != null) {
            try {
                seconds = Double.parseDouble(retryAfterHeader.strip());
            } catch (NumberFormatException ex) {
                seconds = -1;
            }
        }
        if (seconds < 0) {
            seconds = 1;
        }
        return Math.max(250L, Math.min(10_000L, (long) Math.ceil(seconds * 1000) + 100L));
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
    private Optional<String> sendDiscordWithImage(final String url, final FarmEvent event, final byte[] imageJpeg,
            final List<Link> links) throws Exception {
        final String boundary = "bambufarm" + System.nanoTime();
        final String payloadJson = mapper.writeValueAsString(discordPayload(event, links));
        final String head = "--%s\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n%s\r\n--%s\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"snapshot.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"
                .formatted(boundary, payloadJson, boundary);
        final String tail = "\r\n--%s--\r\n".formatted(boundary);
        final HttpResponse<String> response = sendWithRetry(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(List.of(
                        head.getBytes(StandardCharsets.UTF_8), imageJpeg, tail.getBytes(StandardCharsets.UTF_8))))
                .build());
        if (response.statusCode() >= 300) {
            Log.errorf("NotificationService: discord webhook (with image) HTTP %d: %s", response.statusCode(), response.body());
            return Optional.of("Discord HTTP %d".formatted(response.statusCode()));
        }
        return Optional.empty();
    }

    /**
     * ntfy: binary body = attachment, message/title via headers. Header values must be ISO-8859-1-safe, so the
     * text is reduced to ASCII (the full message still goes out via MQTT/logs regardless).
     */
    private Optional<String> sendNtfyWithImage(final String url, final FarmEvent event, final byte[] imageJpeg,
            final List<Link> links) throws Exception {
        final String title = asciiOnly("%s: %s".formatted(event.printer(), event.message()));
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Filename", "snapshot.jpg")
                .header("X-Title", title.substring(0, Math.min(title.length(), 250)))
                .POST(HttpRequest.BodyPublishers.ofByteArray(imageJpeg));
        ntfyActions(links).ifPresent(a -> request.header("Actions", a));
        final HttpResponse<String> response = sendWithRetry(request.build());
        if (response.statusCode() >= 300) {
            Log.errorf("NotificationService: ntfy webhook (with image) HTTP %d: %s", response.statusCode(), response.body());
            return Optional.of("ntfy HTTP %d".formatted(response.statusCode()));
        }
        return Optional.empty();
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
