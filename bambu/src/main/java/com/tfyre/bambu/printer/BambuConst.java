package com.tfyre.bambu.printer;

import com.tfyre.bambu.BambuConfig;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 *
 * @author Francois Steyn - (fsteyn@tfyre.co.za)
 */
public class BambuConst {

    public static final String CHAMBER_LIGHT = "chamber_light";
    /** H2D's second chamber light. Driving only {@link #CHAMBER_LIGHT} leaves half the chamber dark. */
    public static final String CHAMBER_LIGHT_2 = "chamber_light2";
    public static final String FILE_GCODE = ".gcode";
    public static final String FILE_3MF = ".3mf";
    //FIXME GCODE not printing public static final Set<String> EXT = Set.of(FILE_GCODE, FILE_3MF);
    public static final Set<String> EXT = Set.of(FILE_3MF);
    public static final String PATHSEP = "/";
    public static final int TEMPERATURE_MAX_BED = 100;
    public static final int TEMPERATURE_MAX_NOZZLE = 300;
    public static final int AMS_TRAY_VIRTUAL = 254;
    public static final int AMS_TRAY_UNLOAD = 255;
    public static final int AMS_TRAY_TEMP = 210;

    public static final List<BambuConfig.Temperature> PREHEAT = List.of(
            newTemperature("Off 0 / 0", 0, 0),
            newTemperature("PLA 55 / 220", 55, 220),
            newTemperature("ABS 90 / 270", 90, 270)
    );

    private static BambuConfig.Temperature newTemperature(final String name, final int bed, final int nozzle) {
        return new BambuConfig.Temperature() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int bed() {
                return bed;
            }

            @Override
            public int nozzle() {
                return nozzle;
            }
        };
    }

    public static String gcodeTargetTemperatureBed(final int temperature) {
        return "M140 S%d".formatted(Math.max(Math.min(temperature, TEMPERATURE_MAX_BED), 0));
    }

    public static String gcodeTargetTemperatureNozzle(final int temperature) {
        return "M104 S%d".formatted(Math.max(Math.min(temperature, TEMPERATURE_MAX_NOZZLE), 0));
    }

    /**
     * Sets the chamber target on printers with an <i>active</i> chamber heater (X1E, H2 series).
     * <p>
     * Unlike the bed and nozzle this is not one command, which is why it never worked here: on everything but
     * the X1E the chamber heater is tied to the airduct, so {@code M141} alone sets a target the airduct never
     * acts on. {@code M145 P1} puts the duct in heating mode first; dropping to 40°C or below switches it back
     * with {@code M145 P0} <i>after</i> the new target, so the duct isn't left heating toward a lower number.
     * The X1E has no airduct and takes {@code M141} on its own.
     * <p>
     * Order matters in both branches and is taken from ha-bambulab's {@code set_temperature_to_gcode}.
     *
     * @param model       the printer, because the X1E form differs
     * @param temperature target °C, clamped to that model's maximum
     */
    public static String gcodeTargetTemperatureChamber(final PrinterModel model, final int temperature) {
        final int clamped = Math.max(Math.min(temperature, model.getMaxChamberTemperature()), 0);
        if (model == PrinterModel.X1E) {
            return "M141 S%d".formatted(clamped);
        }
        return clamped > 40
                ? "M145 P1\nM141 S%d".formatted(clamped)
                : "M141 S%d\nM145 P0".formatted(clamped);
    }

    public static String gcodeDisableSteppers() {
        return "M18";
    }

    public static String gcodeFanSpeed(final Fan fan, final FanSpeed speed) {
        return "M106 P%d S%d".formatted(fan.getValue(), speed.getValue());

    }

    public static List<String> gcodeMoveXYZ(final Move move, final int value, final int speed) {
        return List.of(
                "M211 S",
                "M211 X1 Y1 Z1",
                "M1002 push_ref_mode",
                "G91",
                "G1 %s%d F%d".formatted(move.getValue(), value, speed),
                "M1002 pop_ref_mode",
                "M211 R"
        );
    }

    public static List<String> gcodeMoveExtruder(final boolean up) {
        return List.of(
                "M83",
                "G0 %s%d F900".formatted(Move.E.getValue(), up ? -10 : 10)
        );
    }

    /**
     * Bit in the printer's {@code fun} bitfield meaning "MQTT commands must be cryptographically signed".
     * <p>
     * This is the single most confusing failure this app can hit, because it produces no failure. The printer
     * accepts an unsigned command, returns nothing, and discards it - so Home, fan control, pause and light
     * all report success and do nothing at all. Enabling <b>Developer Mode</b> on the printer clears the bit;
     * a firmware update can turn Developer Mode back off, which is exactly how an H2D that worked yesterday
     * stops responding today.
     * <p>
     * Values observed by the ha-bambulab project, whose reading of this field this matches:
     * {@code fun="3EC1AFFF9CFF"} with Developer Mode off (bit set), {@code "3EC18FFF9CFF"} with it on (clear).
     * Nobody has implemented request signing - that project responds by removing the affected controls from
     * the UI rather than sending commands into a void, and so does this one.
     */
    public static final long MQTT_SIGNATURE_REQUIRED = 0x20000000L;

    /** Highest AMS tray {@code state} value using the legacy encoding, where only 3 means "spool loaded". */
    private static final int AMS_TRAY_STATE_LEGACY_MAX = 3;
    /** A spool is physically present in the slot. */
    private static final int AMS_TRAY_STATE_SPOOL = 0x01;
    /** The tray has settled - not mid-load, mid-unload or scanning. */
    private static final int AMS_TRAY_STATE_STEADY = 0x08;

    /**
     * Whether an AMS tray physically holds a spool, from its {@code state} flags.
     * <p>
     * <b>This, not {@code tray_type}, is what "has filament" means.</b> {@code tray_type} is the material a
     * slot is <i>configured</i> for and it survives the spool being pulled, so an emptied slot goes on
     * reporting PETG indefinitely - which is how an order was dispatched onto a printer with nothing in slot 1
     * while the overview cheerfully displayed "PETG". {@code tray_exist_bits} on the AMS looked like the
     * answer and is not: it did not stop that dispatch.
     * <p>
     * Two encodings, and the boundary matters. Values at or below {@value #AMS_TRAY_STATE_LEGACY_MAX} are the
     * legacy form where only exactly 3 counts as loaded; above that the flags apply and a spool must be both
     * present ({@code SPOOL}) and settled ({@code STEADY}). Requiring STEADY is deliberate: a tray mid-load or
     * being scanned reports SPOOL but its metadata is not yet trustworthy, and dispatching against it would
     * reintroduce the same class of bug one step later.
     * <p>
     * Mirrors ha-bambulab's {@code ams_tray_spool_loaded}, checked against all eleven of its test vectors
     * (0,1,2,5,8,10,17,21 → empty; 3,9,11 → loaded).
     */
    public static boolean amsTraySpoolLoaded(final int state) {
        if ((state & AMS_TRAY_STATE_SPOOL) == 0) {
            return false;
        }
        if (state <= AMS_TRAY_STATE_LEGACY_MAX) {
            return state == AMS_TRAY_STATE_LEGACY_MAX;
        }
        return (state & AMS_TRAY_STATE_STEADY) != 0;
    }

    /** {@code buzzer_ctrl} modes. H2 series only. */
    public enum BuzzerMode {
        SILENT(0),
        FIRE_ALARM(1),
        BEEPING(2);

        private final int value;

        private BuzzerMode(final int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    /**
     * Printers whose {@code project_file} command takes {@code file:///sdcard/<name>}. Everything else -
     * H2D included - expects {@code ftp:///<name>}, and sending the wrong form fails the print with no
     * useful diagnostic. Mirrors ha-bambulab's LEGACY_SDCARD_PRINTERS.
     */
    private static final Set<PrinterModel> LEGACY_SDCARD_MODELS
            = Set.of(PrinterModel.X1C, PrinterModel.X1E, PrinterModel.P1P, PrinterModel.P1S,
                    PrinterModel.A1, PrinterModel.A1MINI);

    /**
     * The {@code url} for a {@code project_file} print command on this model.
     *
     * @param model    the printer's model; {@link PrinterModel#UNKNOWN} takes the legacy form, because every
     *                 printer this app supported before the H2D used it and an unrecognised model is far more
     *                 likely to be an older one than a new one
     * @param fileName SD-card-relative file name, no leading slash
     */
    public static String projectFileUrl(final PrinterModel model, final String fileName) {
        return LEGACY_SDCARD_MODELS.contains(model) || model == PrinterModel.UNKNOWN
                ? "file:///sdcard/%s".formatted(fileName)
                : "ftp:///%s".formatted(fileName);
    }

    public static String gcodeHomeAll() {
        return "G28";
    }

    public static String gcodeHomeXY() {
        return "G28 X Y";
    }

    public static String gcodeHomeZ() {
        return "G28 Z";
    }

    private BambuConst() {
    }

    public enum Move {
        E("E"),
        X("X"),
        Y("Y"),
        Z("Z");

        private final String value;

        private Move(final String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

    }

    public enum Fan {
        PART("Part", 1),
        AUX("AUX", 2),
        CHAMBER("Chamber", 3);

        private final String name;
        private final int value;

        private Fan(final String name, final int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public int getValue() {
            return value;
        }

    }

    public enum FanSpeed {
        OFF("Off", 0),
        P25("25%", (int) (0.25 * 255)),
        P50("50%", (int) (0.50 * 255)),
        P75("75%", (int) (0.75 * 255)),
        FULL("Full", (int) (1.0 * 255));

        private final String name;
        private final int value;

        private FanSpeed(final String name, final int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public int getValue() {
            return value;
        }

    }

    public enum LightMode {
        ON("on"),
        OFF("off");
        //FIXME what does this do?
        //FLASHING("flashing");

        private final String value;

        private LightMode(final String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

    }

    public enum CommandControl {
        STOP("stop"),
        PAUSE("pause"),
        RESUME("resume");

        private final String value;

        private CommandControl(final String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public enum Speed {
        UNKNOWN(0, "Unknown"),
        SILENT(1, "Silent"),
        NORMAL(2, "Normal"),
        SPORT(3, "Sport"),
        LUDICROUS(4, "Ludicrous");

        private final int speed;
        private final String description;

        private static final Map<Integer, Speed> MAP = EnumSet.allOf(Speed.class).stream().collect(Collectors.toMap(Speed::getSpeed, Function.identity()));

        private Speed(final int speed, final String description) {
            this.speed = speed;
            this.description = description;
        }

        public int getSpeed() {
            return speed;
        }

        public String getDescription() {
            return description;
        }

        public static Speed fromSpeed(final int speed) {
            return MAP.getOrDefault(speed, UNKNOWN);
        }

    }

    public enum Color {
        COL00(0xffffff),
        COL01(0xfff144),
        COL02(0xdcf478),
        COL03(0x0acc38),
        COL04(0x057748),
        COL05(0x0d6284),
        COL06(0x0ee2a0),
        COL07(0x76d9f4),
        COL08(0x46a8f9),
        COL09(0x2850e0),
        COL10(0x443089),
        COL11(0xa03cf7),
        COL12(0xf330f9),
        COL13(0xd4b1dd),
        COL14(0xf95d73),
        COL15(0xf72323),
        COL16(0x7c4b00),
        COL17(0xf98c36),
        COL18(0xfcecd6),
        COL19(0xd3c5a3),
        COL20(0xaf7933),
        COL21(0x898989),
        COL22(0xbcbcbc),
        COL23(0x161616);

        private final long color;
        private final String htmlColor;

        private Color(final long color) {
            this.color = color;
            this.htmlColor = "#%06X".formatted(color);
        }

        public long getColor() {
            return color;
        }

        public String getHtmlColor() {
            return htmlColor;
        }

    }

    public enum PrinterModel {
        UNKNOWN("unknown", false, false),
        A1("a1", false, false),
        A1MINI("a1mini", false, false),
        P1P("p1p", false, false),
        P1S("p1s", false, false),
        X1C("x1c", true, false),
        X1E("x1e", true, false),
        H2D("h2d", true, true);

        private static final Map<String, PrinterModel> MAP = EnumSet.allOf(PrinterModel.class).stream().collect(Collectors.toMap(PrinterModel::getModel, Function.identity()));

        private final String model;
        private final boolean temperature;
        private final boolean dualNozzle;

        private PrinterModel(final String model, final boolean temperature, final boolean dualNozzle) {
            this.model = model;
            this.temperature = temperature;
            this.dualNozzle = dualNozzle;
        }

        public String getModel() {
            return model;
        }

        public boolean isTemperature() {
            return temperature;
        }

        /** True for printers with two independent extruders (e.g. H2D). */
        public boolean isDualNozzle() {
            return dualNozzle;
        }

        /**
         * True for printers that can actively HEAT the chamber, as opposed to merely reporting its temperature.
         * <p>
         * Deliberately narrower than {@link #isTemperature()}: the X1C reports a chamber temperature but has no
         * heater, so offering a target there would be a control that does nothing - the exact failure mode this
         * app spent a day chasing on the H2D.
         */
        public boolean isActiveChamberHeater() {
            return this == X1E || this == H2D;
        }

        /** Chamber ceiling: the X1E stops at 60°C, the H2 series at 65°C. 0 when there is no heater. */
        public int getMaxChamberTemperature() {
            if (this == X1E) {
                return 60;
            }
            return this == H2D ? 65 : 0;
        }

        public static Optional<PrinterModel> fromModel(final String model) {
            return Optional.ofNullable(MAP.get(model));
        }

    }

    /**
     * AMS unit type, derived from the module hw_ver / project_name strings returned by get_version.
     * <p>
     * Known strings (update as more hardware is tested):
     * <ul>
     *   <li>AMS v1:      hw_ver starts with "AMS_A", project_name "AMS"</li>
     *   <li>AMS 2 Pro:   hw_ver contains "AMS2_PRO", project_name "AMS2_PRO"</li>
     *   <li>AMS 2 Lite:  hw_ver contains "AMS2_LITE", project_name "AMS2_LITE"</li>
     *   <li>AMS Hub:     hw_ver contains "AMS_HUB"</li>
     * </ul>
     */
    public enum AmsType {
        UNKNOWN("Unknown", false),
        AMS("AMS", false),
        AMS_2_PRO("AMS 2 Pro", true),
        AMS_2_LITE("AMS 2 Lite", false),
        AMS_HUB("AMS Hub", false);

        private final String label;
        private final boolean supportsDrying;

        private AmsType(final String label, final boolean supportsDrying) {
            this.label = label;
            this.supportsDrying = supportsDrying;
        }

        public String getLabel() {
            return label;
        }

        public boolean isSupportsDrying() {
            return supportsDrying;
        }

        /**
         * Derive AMS type from the module name, project_name and hw_ver fields.
         * <p>
         * Module names include a unit-index suffix (e.g. {@code "ams/0"}, {@code "n3f/0"})
         * which is stripped before matching.
         * Observed in the wild:
         * <ul>
         *   <li>AMS v1:    name {@code "ams/N"}, hw_ver {@code "AMS08"} (or similar AMS prefix)</li>
         *   <li>AMS 2 Pro: name {@code "n3f/N"}, hw_ver {@code "N3F05"} (Bambu internal code "n3f")</li>
         * </ul>
         */
        public static AmsType fromModule(final String name, final String projectName, final String hwVer) {
            if (name == null || name.isBlank()) {
                return UNKNOWN;
            }
            // Strip the "/N" unit-index suffix before comparing
            final String slash = name.contains("/") ? name.substring(0, name.lastIndexOf('/')).trim() : name.trim();
            final String baseName = slash.toLowerCase();
            final String hw = hwVer != null ? hwVer.toUpperCase() : "";

            // AMS 2 Pro: Bambu internal code name "n3f", hw_ver prefix "N3F"
            if ("n3f".equals(baseName) || hw.startsWith("N3F")) {
                return AMS_2_PRO;
            }
            // AMS 2 Lite: not yet observed — add when hw_ver strings are known
            if (hw.contains("AMS2_LITE") || hw.contains("AMS_LITE")) {
                return AMS_2_LITE;
            }
            // AMS Hub: not yet observed — add when hw_ver strings are known
            if (hw.contains("AMS_HUB")) {
                return AMS_HUB;
            }
            // Original AMS: module name starts with "ams"
            if (baseName.startsWith("ams")) {
                return AMS;
            }
            return UNKNOWN;
        }
    }

    public enum GCodeState {
        UNKNOWN("", "Unknown"),
        OFFLINE("OFFLINE", "Offline"),
        IDLE("IDLE", "Idle"),
        RUNNING("RUNNING", "Running"),
        PAUSE("PAUSE", "Pause"),
        PREPARE("PREPARE", "Prepare"),
        FINISH("FINISH", "Finish"),
        FAILED("FAILED", "Failed"),
        SLICING("SLICING", "Slicing");

        private static final Map<String, GCodeState> MAP = EnumSet.allOf(GCodeState.class).stream().collect(Collectors.toMap(GCodeState::getValue, Function.identity()));
        private static final Set<GCodeState> IS_IDLE = Set.of(IDLE, FINISH);
        private static final Set<GCodeState> IS_READY = Set.of(IDLE, FINISH, FAILED);
        private static final Set<GCodeState> IS_ERROR = Set.of(UNKNOWN, OFFLINE, FAILED);
        private static final Set<GCodeState> IS_PRINTING = Set.of(PREPARE, SLICING, RUNNING);
        private final String value;
        private final String description;

        private GCodeState(final String value, final String description) {
            this.value = value;
            this.description = description;
        }

        public String getValue() {
            return value;
        }

        public String getDescription() {
            return description;
        }

        public boolean isIdle() {
            return IS_IDLE.contains(this);
        }

        public boolean isReady() {
            return IS_READY.contains(this);
        }

        public boolean isError() {
            return IS_ERROR.contains(this);
        }

        public boolean isPrinting() {
            return IS_PRINTING.contains(this);
        }

        public static GCodeState fromValue(final String value) {
            return MAP.getOrDefault(value, UNKNOWN);
        }

    }

}
