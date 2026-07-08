package com.tfyre.bambu.printer;

import com.tfyre.bambu.model.BambuMessage;
import com.tfyre.bambu.printer.BambuConst.PrinterModel;
import com.vaadin.flow.server.StreamResource;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 *
 * @author Francois Steyn - (fsteyn@tfyre.co.za)
 */
public interface BambuPrinter {

    String getName();

    PrinterModel getModel();

    int getPrintError();

    int getTotalLayerNum();

    BambuConst.GCodeState getGCodeState();

    Optional<Message> getStatus();

    Optional<Message> getFullStatus();

    Optional<String> getIFrame();

    Optional<Thumbnail> getThumbnail();

    Collection<Message> getLastMessages();

    boolean isBlocked();

    void setBlocked(final boolean blocked);

    void commandFullStatus(final boolean force);

    void commandClearPrinterError();

    void commandLight(BambuConst.LightMode lightMode);

    void commandControl(BambuConst.CommandControl control);

    void commandSpeed(BambuConst.Speed speed);

    void commandPrintGCodeLine(final String lines);

    void commandPrintGCodeLine(final List<String> lines);

    void commandPrintGCodeFile(final String filename);

    void commandPrintProjectFile(final CommandPPF command);

    /**
     * The last printed file - either reported by the printer or tracked from a print started in the app.
     */
    Optional<String> getLastPrintFile();

    /**
     * Reprints the last printed file from the SD card.
     */
    void commandPrintAgain();

    /**
     * Raw JPEG bytes of the most recent camera snapshot, for AI analysis.
     */
    Optional<byte[]> getSnapshotBytes();

    void commandFilamentLoad(final int amsTrayId);

    void commandFilamentUnload();

    void commandFilamentSetting(final int amsId, final int trayId, final Filament filament, final String color, final int minTemp, final int maxTemp);

    void commandSystemReboot();

    /**
     * Requests firmware and hardware version info from the printer.
     * The printer responds with an Info/module list which is parsed asynchronously.
     * Results become available via {@link #getModules()} and {@link #getFirmwareVersion()}.
     */
    void commandGetVersion();

    /**
     * Starts filament drying on an AMS 2 Pro unit.
     * Only supported by AMS 2 Pro hardware; the command is silently ignored by other AMS types.
     *
     * @param amsId        AMS unit index (0-based)
     * @param targetTemp   drying temperature in °C — minimum 45, maximum 65 for AMS 2 Pro
     * @param durationHours drying duration in HOURS (firmware expects hours, not minutes)
     */
    void commandAmsDry(int amsId, int targetTemp, int durationHours);

    /**
     * Stops an active drying cycle on an AMS 2 Pro unit.
     *
     * @param amsId AMS unit index (0-based)
     */
    void commandAmsStopDry(int amsId);

    /**
     * Returns the list of hardware modules reported by the printer's last get_version response.
     * Empty until {@link #commandGetVersion()} has been sent and acknowledged.
     */
    List<ModuleInfo> getModules();

    /**
     * Returns the OTA firmware version string, e.g. {@code "01.07.02.00"}.
     * Empty until module info has been received.
     */
    Optional<String> getFirmwareVersion();

    /**
     * A single hardware module as reported by the printer's get_version response.
     *
     * @param name        module identifier, e.g. {@code "ota"}, {@code "ams"}, {@code "esp32"}
     * @param projectName project name from older firmware, e.g. {@code "C11"} for P1P
     * @param hwVer       hardware version string, e.g. {@code "AMS2_PRO_A"}
     * @param swVer       firmware/software version string, e.g. {@code "00.00.07.89"}
     * @param productName product name from newer firmware, e.g. {@code "Bambu Lab H2D"}
     */
    record ModuleInfo(String name, String projectName, String hwVer, String swVer, String productName) {

        /** Derive the AMS type for this module. Returns {@link BambuConst.AmsType#UNKNOWN} for non-AMS modules. */
        public BambuConst.AmsType getAmsType() {
            return BambuConst.AmsType.fromModule(name, projectName, hwVer);
        }

        /**
         * The unit index from the module name suffix, e.g. {@code "ams/0"} → 0, {@code "n3f/2"} → 2.
         * Returns 0 when no suffix is present.
         */
        public int unitIndex() {
            final int slash = name != null ? name.lastIndexOf('/') : -1;
            if (slash < 0) {
                return 0;
            }
            try {
                return Integer.parseInt(name.substring(slash + 1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    record Message(OffsetDateTime lastUpdated, BambuMessage message, String raw) {

    }

    record Thumbnail(OffsetDateTime lastUpdated, StreamResource thumbnail, byte[] bytes) {

    }

    record CommandPPF(
            String filename,
            int plateId,
            boolean useAms,
            boolean timelapse,
            boolean bedLevelling,
            boolean flowCalibration,
            boolean vibrationCalibration,
            List<Integer> amsMapping) {

    }
}
