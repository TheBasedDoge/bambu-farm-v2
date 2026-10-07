package com.tfyre.bambu.printer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tfyre.bambu.BambuConfig;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Schedules, changes and cancels a USPS carrier pickup through the USPS Carrier Pickup API (v3).
 * <p>
 * Endpoints, all under {@code bambu.usps.base-url}:
 * <ul>
 * <li>{@code POST /oauth2/v3/token} - client-credentials token</li>
 * <li>{@code GET /pickup/v3/carrier-pickup/eligibility} - is this address served</li>
 * <li>{@code POST /pickup/v3/carrier-pickup} - schedule</li>
 * <li>{@code GET|PUT|DELETE /pickup/v3/carrier-pickup/{confirmationNumber}} - look up, change, cancel. A change
 * or a cancel must send back, as {@code If-Match}, the {@code ETag} the last GET returned.</li>
 * </ul>
 * The address, contact and usual package spot are saved in {@code bambu-usps-pickup.json} together with the
 * pickup currently booked, so the page can show it after a restart without asking USPS.
 * <p>
 * Every failure comes back as a message meant to be shown to the person who pressed the button - USPS's own
 * error text where there is one - because the common failures here (address not eligible, too late for
 * today, a mail class it does not collect) are things only they can act on.
 */
@ApplicationScoped
public class UspsPickupService {

    private static final String STORE_FILENAME = "bambu-usps-pickup.json";

    /** Where the carrier will find the packages. Values are the API's own. */
    public static final List<String> LOCATIONS = List.of("FRONT_DOOR", "BACK_DOOR", "SIDE_DOOR", "KNOCK_ON_DOOR",
            "MAIL_ROOM", "OFFICE", "RECEPTION", "IN_MAILBOX", "OTHER");
    /** Mail classes the API counts packages by. Values are the API's own. */
    public static final List<String> PACKAGE_TYPES = List.of("FIRST-CLASS_PACKAGE_SERVICE", "PRIORITY_MAIL",
            "PRIORITY_MAIL_EXPRESS", "RETURNS", "INTERNATIONAL", "OTHER");

    /** Who and where. Saved from the page; nothing here is sent anywhere but USPS. */
    public record Profile(String firstName, String lastName, String firm, String streetAddress, String secondaryAddress,
            String city, String state, String zip, String phone, String email, String packageLocation,
            String specialInstructions, String packageType) {

        public static Profile empty() {
            return new Profile("", "", "", "", "", "", "", "", "", "", "FRONT_DOOR", "", "FIRST-CLASS_PACKAGE_SERVICE");
        }

        public boolean complete() {
            return !blank(firstName) && !blank(lastName) && !blank(streetAddress) && !blank(zip)
                    && (!blank(phone) || !blank(email));
        }
    }

    /** The pickup currently booked, as USPS confirmed it. */
    public record Booked(String confirmationNumber, String pickupDate, int packageCount, int estimatedWeight,
            String packageType, String scheduledAt) {
    }

    /** What is persisted. */
    public record Store(Profile profile, Booked booked) {
    }

    /** Outcome of a call: either {@code error} is set, or it worked and {@code message} says what happened. */
    public record Result(boolean ok, String message) {

        static Result ok(final String message) {
            return new Result(true, message);
        }

        static Result fail(final String message) {
            return new Result(false, message);
        }
    }

    @Inject
    BambuConfig config;
    @Inject
    ObjectMapper mapper;
    @Inject
    NotificationService notificationService;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private Profile profile = Profile.empty();
    private Booked booked;
    private String token;
    private Instant tokenExpires = Instant.EPOCH;

    private static boolean blank(final String s) {
        return s == null || s.isBlank();
    }

    private Path getPath() {
        final Path parent = Path.of(config.maintenanceFile()).getParent();
        return parent != null ? parent.resolve(STORE_FILENAME) : Path.of(STORE_FILENAME);
    }

    @PostConstruct
    synchronized void load() {
        final Path path = getPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            final Store store = mapper.readValue(path.toFile(), Store.class);
            if (store.profile() != null) {
                profile = store.profile();
            }
            booked = store.booked();
        } catch (IOException | RuntimeException ex) {
            Log.errorf(ex, "UspsPickupService: cannot load %s: %s", path, ex.getMessage());
        }
    }

    private synchronized void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(getPath().toFile(), new Store(profile, booked));
        } catch (IOException ex) {
            Log.errorf(ex, "UspsPickupService: cannot save %s: %s", getPath(), ex.getMessage());
        }
    }

    /** Whether the Consumer Key and Secret are in the config. */
    public boolean isConfigured() {
        return config.usps().clientId().filter(s -> !s.isBlank()).isPresent()
                && config.usps().clientSecret().filter(s -> !s.isBlank()).isPresent();
    }

    /** True when pointed at USPS's test environment, where nothing is really collected. */
    public boolean isTestEnvironment() {
        return config.usps().baseUrl().contains("apis-tem");
    }

    public synchronized Profile getProfile() {
        return profile;
    }

    public synchronized void setProfile(final Profile p) {
        profile = p;
        save();
    }

    /** The pickup on record, dropped once its date has passed. */
    public synchronized Optional<Booked> getBooked() {
        if (booked != null) {
            try {
                if (LocalDate.parse(booked.pickupDate()).isBefore(LocalDate.now())) {
                    booked = null;
                    save();
                }
            } catch (RuntimeException ex) {
                // An unreadable date is not a reason to hide a real booking.
            }
        }
        return Optional.ofNullable(booked);
    }

    // -------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------
    private synchronized String accessToken() throws Exception {
        if (token != null && Instant.now().isBefore(tokenExpires)) {
            return token;
        }
        final ObjectNode body = mapper.createObjectNode();
        body.put("client_id", config.usps().clientId().orElse(""));
        body.put("client_secret", config.usps().clientSecret().orElse(""));
        body.put("grant_type", "client_credentials");
        final HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(config.usps().baseUrl() + "/oauth2/v3/token"))
                .timeout(config.usps().timeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("USPS did not accept the Consumer Key/Secret (HTTP %d): %s"
                    .formatted(response.statusCode(), errorText(response.body())));
        }
        final JsonNode root = mapper.readTree(response.body());
        token = root.path("access_token").asText();
        // Refresh a minute early; USPS tokens last about eight hours.
        tokenExpires = Instant.now().plusSeconds(Math.max(60, root.path("expires_in").asLong(3600) - 60));
        return token;
    }

    private HttpResponse<String> call(final String method, final String path, final String json, final String ifMatch)
            throws Exception {
        final HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.usps().baseUrl() + path))
                .timeout(config.usps().timeout())
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken());
        if (json != null) {
            b.header("Content-Type", "application/json");
        }
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        HttpResponse<String> response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            // The token was revoked or expired early - get a new one and try once more.
            synchronized (this) {
                token = null;
            }
            b.setHeader("Authorization", "Bearer " + accessToken());
            response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }
        return response;
    }

    /**
     * What to say when USPS accepts the credentials but will not let this app near Carrier Pickup.
     * <p>
     * A new USPS app only gets the "Public Access I" product (addresses, prices, tracking and so on). Carrier
     * Pickup is not in it and has to be asked for. The first version reported this as "this address is not
     * eligible for pickup", which sends you to check a perfectly good address.
     */
    private static final String NO_ACCESS = "Your USPS app is connected, but USPS has not given it access to Carrier "
            + "Pickup yet (new apps only get the basic APIs). Ask for it at emailus.usps.com/s/web-tools-inquiry: "
            + "choose API Onboarding, then API Onboarding/Upgrade, and request the Carrier Pickup API for your app.";

    private static boolean noAccess(final HttpResponse<String> response) {
        final String body = response.body() == null ? "" : response.body().toLowerCase(java.util.Locale.ROOT);
        return response.statusCode() == 403 || (response.statusCode() == 401 && body.contains("scope"))
                || body.contains("insufficient oauth scope");
    }

    /** USPS's own explanation out of an error body, or the body itself when it is not the usual shape. */
    private String errorText(final String body) {
        if (body == null || body.isBlank()) {
            return "no details given";
        }
        try {
            final JsonNode root = mapper.readTree(body);
            final JsonNode error = root.path("error");
            final StringBuilder sb = new StringBuilder();
            if (error.isObject()) {
                sb.append(error.path("message").asText(""));
                for (final JsonNode e : error.path("errors")) {
                    final String detail = e.path("detail").asText(e.path("title").asText(""));
                    if (!detail.isBlank() && sb.indexOf(detail) < 0) {
                        sb.append(sb.length() > 0 ? " - " : "").append(detail);
                    }
                }
            } else if (error.isTextual()) {
                sb.append(error.asText()).append(' ').append(root.path("error_description").asText(""));
            }
            if (sb.length() > 0) {
                return sb.toString().strip();
            }
        } catch (IOException ex) {
            // not JSON - fall through to the raw text
        }
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }

    private static String enc(final String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private ObjectNode pickupBody(final LocalDate date, final int packageCount, final int estimatedWeight) {
        final Profile p = getProfile();
        final ObjectNode root = mapper.createObjectNode();
        root.put("pickupDate", date.toString());
        final ObjectNode pa = root.putObject("pickupAddress");
        pa.put("firstName", p.firstName());
        pa.put("lastName", p.lastName());
        if (!blank(p.firm())) {
            pa.put("firm", p.firm());
        }
        final ObjectNode a = pa.putObject("address");
        a.put("streetAddress", p.streetAddress());
        if (!blank(p.secondaryAddress())) {
            a.put("secondaryAddress", p.secondaryAddress());
        }
        a.put("city", p.city());
        a.put("state", p.state());
        a.put("ZIPCode", p.zip());
        final ArrayNode contact = pa.putArray("contact");
        if (!blank(p.email())) {
            contact.addObject().put("email", p.email());
        }
        if (!blank(p.phone())) {
            contact.addObject().put("phone", p.phone().replaceAll("[^0-9]", ""));
        }
        root.putArray("packages").addObject()
                .put("packageType", blank(p.packageType()) ? PACKAGE_TYPES.get(0) : p.packageType())
                .put("packageCount", Math.max(1, packageCount));
        root.put("estimatedWeight", Math.max(1, estimatedWeight));
        final ObjectNode loc = root.putObject("pickupLocation");
        loc.put("packageLocation", blank(p.packageLocation()) ? "FRONT_DOOR" : p.packageLocation());
        if (!blank(p.specialInstructions())) {
            loc.put("specialInstructions", p.specialInstructions());
        }
        return root;
    }

    // -------------------------------------------------------------------------
    // operations
    // -------------------------------------------------------------------------
    private Optional<Result> notReady() {
        if (!isConfigured()) {
            return Optional.of(Result.fail("USPS is not set up yet - add bambu.usps.client-id and bambu.usps.client-secret to data/.env and restart."));
        }
        if (!getProfile().complete()) {
            return Optional.of(Result.fail("Fill in the name, street address, ZIP code and a phone number or email first."));
        }
        return Optional.empty();
    }

    /** Asks USPS whether it collects from the saved address. */
    public Result checkEligibility() {
        final Optional<Result> stop = notReady();
        if (stop.isPresent()) {
            return stop.get();
        }
        final Profile p = getProfile();
        try {
            final HttpResponse<String> response = call("GET", "/pickup/v3/carrier-pickup/eligibility?streetAddress="
                    + enc(p.streetAddress()) + (blank(p.secondaryAddress()) ? "" : "&secondaryAddress=" + enc(p.secondaryAddress()))
                    + "&city=" + enc(p.city()) + "&state=" + enc(p.state()) + "&ZIPCode=" + enc(p.zip()), null, null);
            if (noAccess(response)) {
                return Result.fail(NO_ACCESS);
            }
            if (response.statusCode() >= 300) {
                return Result.fail("USPS says this address is not eligible for pickup: " + errorText(response.body()));
            }
            final JsonNode a = mapper.readTree(response.body()).path("pickupAddress").path("address");
            return Result.ok("Eligible. USPS reads the address as %s, %s %s %s.".formatted(a.path("streetAddress").asText(),
                    a.path("city").asText(), a.path("state").asText(), a.path("ZIPCode").asText()));
        } catch (Exception ex) {
            Log.errorf(ex, "UspsPickupService: eligibility check failed: %s", ex.getMessage());
            return Result.fail("Could not reach USPS: " + ex.getMessage());
        }
    }

    /** Books a pickup. Refuses when one is already on record - change or cancel that one instead. */
    public synchronized Result schedule(final LocalDate date, final int packageCount, final int estimatedWeight) {
        final Optional<Result> stop = notReady();
        if (stop.isPresent()) {
            return stop.get();
        }
        if (getBooked().isPresent()) {
            return Result.fail("A pickup is already booked for %s (%s). Change or cancel it first."
                    .formatted(booked.pickupDate(), booked.confirmationNumber()));
        }
        try {
            final HttpResponse<String> response = call("POST", "/pickup/v3/carrier-pickup",
                    mapper.writeValueAsString(pickupBody(date, packageCount, estimatedWeight)), null);
            if (noAccess(response)) {
                return Result.fail(NO_ACCESS);
            }
            if (response.statusCode() >= 300) {
                Log.warnf("UspsPickupService: schedule -> HTTP %d: %s", response.statusCode(), response.body());
                return Result.fail("USPS did not book the pickup: " + errorText(response.body()));
            }
            final JsonNode root = mapper.readTree(response.body());
            final String confirmation = root.path("confirmationNumber").asText("");
            if (confirmation.isBlank()) {
                return Result.fail("USPS answered without a confirmation number - check usps.com before assuming it is booked.");
            }
            booked = new Booked(confirmation, root.path("pickupDate").asText(date.toString()), Math.max(1, packageCount),
                    Math.max(1, estimatedWeight), getProfile().packageType(), Instant.now().toString());
            save();
            final String msg = "%sUSPS pickup booked for %s - %d package(s), about %d lb. Confirmation %s.".formatted(
                    isTestEnvironment() ? "[TEST] " : "", booked.pickupDate(), booked.packageCount(), booked.estimatedWeight(), confirmation);
            Log.infof("UspsPickupService: %s", msg);
            notificationService.notifyEvent("usps_pickup", "USPS", msg);
            return Result.ok(msg);
        } catch (Exception ex) {
            Log.errorf(ex, "UspsPickupService: schedule failed: %s", ex.getMessage());
            return Result.fail("Could not reach USPS: " + ex.getMessage());
        }
    }

    /** The ETag USPS currently holds for a pickup - a change or a cancel has to quote it. */
    private String etagOf(final String confirmationNumber) throws Exception {
        final HttpResponse<String> response = call("GET", "/pickup/v3/carrier-pickup/" + enc(confirmationNumber), null, null);
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("USPS could not find pickup %s: %s".formatted(confirmationNumber, errorText(response.body())));
        }
        return response.headers().firstValue("ETag")
                .orElseThrow(() -> new IllegalStateException("USPS returned the pickup without an ETag, so it cannot be changed from here."));
    }

    /** Changes the date, count or weight of the booked pickup (address and location are re-sent as saved). */
    public synchronized Result update(final LocalDate date, final int packageCount, final int estimatedWeight) {
        final Optional<Result> stop = notReady();
        if (stop.isPresent()) {
            return stop.get();
        }
        if (getBooked().isEmpty()) {
            return Result.fail("There is no pickup booked to change.");
        }
        try {
            final String confirmation = booked.confirmationNumber();
            final ObjectNode body = pickupBody(date, packageCount, estimatedWeight);
            body.put("confirmationNumber", confirmation);
            final HttpResponse<String> response = call("PUT", "/pickup/v3/carrier-pickup/" + enc(confirmation),
                    mapper.writeValueAsString(body), etagOf(confirmation));
            if (response.statusCode() >= 300) {
                Log.warnf("UspsPickupService: update -> HTTP %d: %s", response.statusCode(), response.body());
                return Result.fail("USPS did not change the pickup: " + errorText(response.body()));
            }
            final JsonNode root = mapper.readTree(response.body());
            booked = new Booked(confirmation, root.path("pickupDate").asText(date.toString()), Math.max(1, packageCount),
                    Math.max(1, estimatedWeight), getProfile().packageType(), booked.scheduledAt());
            save();
            final String msg = "USPS pickup %s changed: %s, %d package(s), about %d lb.".formatted(confirmation,
                    booked.pickupDate(), booked.packageCount(), booked.estimatedWeight());
            Log.infof("UspsPickupService: %s", msg);
            notificationService.notifyEvent("usps_pickup", "USPS", msg);
            return Result.ok(msg);
        } catch (Exception ex) {
            Log.errorf(ex, "UspsPickupService: update failed: %s", ex.getMessage());
            return Result.fail("Could not change the pickup: " + ex.getMessage());
        }
    }

    /** Cancels the booked pickup. USPS does not let a cancelled pickup be brought back; book a new one. */
    public synchronized Result cancel() {
        if (!isConfigured()) {
            return notReady().orElse(Result.fail("USPS is not set up."));
        }
        if (getBooked().isEmpty()) {
            return Result.fail("There is no pickup booked to cancel.");
        }
        try {
            final String confirmation = booked.confirmationNumber();
            final HttpResponse<String> response = call("DELETE", "/pickup/v3/carrier-pickup/" + enc(confirmation), null,
                    etagOf(confirmation));
            if (response.statusCode() >= 300) {
                Log.warnf("UspsPickupService: cancel -> HTTP %d: %s", response.statusCode(), response.body());
                return Result.fail("USPS did not cancel the pickup: " + errorText(response.body()));
            }
            final String msg = "USPS pickup %s for %s cancelled.".formatted(confirmation, booked.pickupDate());
            booked = null;
            save();
            Log.infof("UspsPickupService: %s", msg);
            notificationService.notifyEvent("usps_pickup", "USPS", msg);
            return Result.ok(msg);
        } catch (Exception ex) {
            Log.errorf(ex, "UspsPickupService: cancel failed: %s", ex.getMessage());
            return Result.fail("Could not cancel the pickup: " + ex.getMessage());
        }
    }

    /** Forgets the booking locally without telling USPS - for a pickup that was cancelled on usps.com. */
    public synchronized void forgetBooking() {
        booked = null;
        save();
    }
}
