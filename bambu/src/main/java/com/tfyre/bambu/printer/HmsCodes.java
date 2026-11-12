package com.tfyre.bambu.printer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bambu's own text for HMS alerts and print-error codes, per printer model.
 * <p>
 * 2026-11-12: an H2D failed bed leveling twice and the app only showed "HMS 0x03002600" - half the code (the
 * attr), looked up in the old xtouch print-error table, which has no H2D entries. The full code
 * (0300-2600-0001-0008) says exactly what happened ("the left nozzle touches the heating bed abnormally ...").
 * <p>
 * The text comes from the database Bambu Studio uses ({@code e.bambulab.com/query.php?lang=en&d=<device id>}).
 * A snapshot ships in {@code resources/hms/}; {@link HmsCatalogService} refreshes it into the data folder.
 */
public final class HmsCodes {

    /**
     * One active HMS alert.
     *
     * @param code     full code, 0300-2600-0001-0008
     * @param severity 1 fatal, 2 serious, 3 common, 4 info
     * @param text     Bambu's description, or empty when the database has none
     * @param url      Bambu's wiki redirect for this code, or empty for a model without a known device id
     */
    public record Hms(String code, int severity, String text, String url) {

        /** "HMS 0300-2600-0001-0008: The left nozzle touches the heating bed abnormally..." */
        public String describe() {
            return text.isBlank() ? "HMS " + code : "HMS %s: %s".formatted(code, text);
        }
    }

    record Db(String ver, Map<String, String> hms, Map<String, String> errors) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Db> DBS = new ConcurrentHashMap<>();
    private static final Db EMPTY = new Db("", Map.of(), Map.of());

    private HmsCodes() {
    }

    /** Bambu's device id for a model - the {@code d=} of the HMS database and wiki links. */
    public static Optional<String> deviceId(final BambuConst.PrinterModel model) {
        if (model == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(switch (model) {
            case H2D -> "094";
            case P1S -> "01P";
            case P1P -> "01S";
            case X1C -> "00M";
            case X1E -> "03W";
            case A1 -> "039";
            case A1MINI -> "030";
            default -> null;
        });
    }

    /** 0300-2600-0001-0008 from the hms entry's attr and code. */
    public static String hmsCode(final long attr, final long code) {
        return "%04X-%04X-%04X-%04X".formatted((attr >> 16) & 0xFFFF, attr & 0xFFFF, (code >> 16) & 0xFFFF, code & 0xFFFF);
    }

    /** 0300-4002 for print_error 0x03004002 (the form Bambu's app and wiki use). */
    public static String errorCode(final int code) {
        return "%04X-%04X".formatted((code >> 16) & 0xFFFF, code & 0xFFFF);
    }

    public static Hms hms(final BambuConst.PrinterModel model, final long attr, final long code) {
        final String key = "%08X%08X".formatted(attr & 0xFFFFFFFFL, code & 0xFFFFFFFFL);
        final Optional<String> dev = deviceId(model);
        final String text = dev.map(d -> db(d).hms().get(key)).orElse(null);
        final String url = dev.map(d -> "https://e.bambulab.com/index.php?e=%s&d=%s&s=device_hms&lang=en".formatted(key, d))
                .orElse("");
        return new Hms(hmsCode(attr, code), (int) ((code >> 16) & 0xFFFF), text == null ? "" : text, url);
    }

    /** Bambu's text for a print_error code on this model, falling back to the old built-in table. */
    public static Optional<String> printError(final BambuConst.PrinterModel model, final int code) {
        final String key = "%08X".formatted(code);
        return deviceId(model).map(d -> db(d).errors().get(key))
                .or(() -> BambuErrors.getPrinterError(code))
                .filter(s -> !s.isBlank());
    }

    /** "Error 0300-4002: Auto Bed Leveling failed; the task has been stopped." */
    public static String describeError(final BambuConst.PrinterModel model, final int code) {
        return "%s: %s".formatted(errorCode(code), printError(model, code).orElse("unknown error"));
    }

    /**
     * The print error and the active HMS alerts together - "Error 0300-4002: Auto Bed Leveling failed... · HMS
     * 0300-2600-0001-0008: The left nozzle touches..." - or empty when the printer reports neither.
     */
    public static Optional<String> problems(final BambuPrinter p) {
        final List<String> parts = new ArrayList<>();
        if (p.getPrintError() != 0) {
            parts.add("Error " + describeError(p.getModel(), p.getPrintError()));
        }
        parts.addAll(p.getActiveHmsErrors());
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join(" · ", parts));
    }

    // ------------------------------------------------------------------------------------------------------------

    static String version(final String dev) {
        return db(dev).ver();
    }

    /** Replaces a model's database (a fresher download). */
    static void install(final String dev, final Db db) {
        DBS.put(dev, db);
    }

    private static Db db(final String dev) {
        return DBS.computeIfAbsent(dev, HmsCodes::bundled);
    }

    private static Db bundled(final String dev) {
        try (InputStream in = HmsCodes.class.getResourceAsStream("/hms/hms_%s.json".formatted(dev))) {
            return in == null ? EMPTY : parse(MAPPER.readTree(in));
        } catch (IOException | RuntimeException ex) {
            Log.warnf("HmsCodes: bundled database for %s unreadable: %s", dev, ex.getMessage());
            return EMPTY;
        }
    }

    /** Reads either the compact form ({ver, hms, error}) or Bambu's raw query.php answer. */
    static Db parse(final JsonNode root) {
        if (root.has("data")) {
            final JsonNode data = root.path("data");
            return new Db(data.path("device_hms").path("ver").asText(""),
                    rawEntries(data.path("device_hms").path("en")), rawEntries(data.path("device_error").path("en")));
        }
        return new Db(root.path("ver").asText(""), compactEntries(root.path("hms")), compactEntries(root.path("error")));
    }

    private static Map<String, String> rawEntries(final JsonNode list) {
        final Map<String, String> m = new HashMap<>();
        list.forEach(e -> {
            final String text = e.path("intro").asText("").strip();
            if (!text.isEmpty()) {
                m.put(e.path("ecode").asText("").toUpperCase(), text);
            }
        });
        return Map.copyOf(m);
    }

    private static Map<String, String> compactEntries(final JsonNode obj) {
        final Map<String, String> m = new HashMap<>();
        obj.fields().forEachRemaining(e -> m.put(e.getKey().toUpperCase(), e.getValue().asText("")));
        return Map.copyOf(m);
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }

    /** The compact JSON written to the data folder. */
    static Map<String, Object> toJson(final Db db) {
        return Map.of("ver", db.ver(), "hms", db.hms(), "error", db.errors());
    }
}
