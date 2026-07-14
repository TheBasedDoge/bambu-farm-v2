package com.tfyre.bambu;

import com.tfyre.bambu.printer.BambuConst.PrinterModel;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithConverter;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 *
 * @author Francois Steyn - (fsteyn@tfyre.co.za)
 */
@ConfigMapping(prefix = "bambu")
public interface BambuConfig {

    @WithDefault("false")
    boolean useBouncyCastle();

    @WithDefault("true")
    boolean menuLeftClick();

    @WithDefault("false")
    boolean darkMode();

    @WithDefault("5000")
    int moveXy();

    @WithDefault("3000")
    int moveZ();

    @WithDefault("1s")
    Duration refreshInterval();

    @WithDefault("true")
    boolean remoteView();

    /**
     * Path to the ffmpeg binary, used by {@link com.tfyre.bambu.printer.RtspSnapshotService} to grab AI-check
     * snapshots directly from a printer's RTSPS camera stream on models (X1C, X1E, H2D) that don't push raw
     * JPEGs over the usual port-6000 mechanism. Defaults to relying on PATH.
     */
    @WithDefault("ffmpeg")
    String ffmpegPath();

    /**
     * Base RTSP URL of an internal camera relay (e.g. {@code rtsp://mediamtx:8554} for the shipped
     * {@code docker/bambu-liveview} setup) that {@link com.tfyre.bambu.printer.RtspSnapshotService} should pull
     * AI-check snapshots from - instead of connecting to the printer's RTSPS port directly - for any printer
     * with {@code stream.live-view=true} (X1C/X1E/H2D). The printer's config key (the map key under
     * {@code bambu.printers.*}) is appended as the path, e.g. {@code rtsp://mediamtx:8554/printer5}, which
     * matches the {@code PRINTER_ID} used by that printer's "liveview" bridge container.
     * <p>
     * Bambu's camera firmware only tolerates one RTSPS client at a time. If a persistent bridge (like the
     * "liveview" container) already holds that connection, a second direct connection from this app will
     * either fail or - worse - can knock the existing one offline, breaking the live view. Setting this
     * property routes AI-check snapshots through the same already-open relay instead. Leave unset to connect
     * directly to the printer (fine when nothing else is already holding a connection to it).
     */
    Optional<String> mediamtxRtspUrl();

    /**
     * How long a printer must sit in a ready state (finished/idle/failed) before AI-gated auto-start will
     * attempt to start the next queued job on it - a small buffer so we're not racing end-of-print telemetry
     * or a person who's mid-way through clearing the bed. See {@link com.tfyre.bambu.printer.AutoStartService}.
     */
    @WithDefault("3m")
    Duration autoStartSettle();

    @WithDefault("bambu-maintenance.json")
    String maintenanceFile();

    @WithDefault("bambu-history.json")
    String historyFile();

    @WithDefault("bambu-queue.json")
    String queueFile();

    /**
     * Filament cost per kg - when greater than 0, the History view shows estimated material cost per job.
     */
    @WithDefault("0")
    double costPerKg();

    @WithDefault("$")
    String currencySymbol();

    Notify notifications();

    public interface Notify {

        /**
         * Webhook URL for farm events (print finish/fail, printer errors, maintenance due).
         */
        Optional<String> webhookUrl();

        /**
         * Webhook payload format: json (full event), discord, or ntfy (plain text).
         */
        @WithDefault("json")
        String webhookFormat();

        NotifyMqtt mqtt();

        public interface NotifyMqtt {

            /**
             * MQTT broker for farm events, e.g. tcp://192.168.1.10:1883
             */
            Optional<String> url();

            Optional<String> username();

            Optional<String> password();

            @WithDefault("bambufarm")
            String topic();

        }

    }

    Ollama ollama();

    public interface Ollama {

        /**
         * Base URL of the Ollama server, e.g. http://192.168.1.x:11434. When absent, all AI checks are skipped.
         */
        Optional<String> url();

        /**
         * Vision-capable model to use for AI checks, e.g. gemma3:12b, llava, moondream2.
         */
        @WithDefault("gemma3:12b")
        String model();

        /**
         * How often to check actively-printing printers for spaghetti / failure.
         */
        @WithDefault("5m")
        Duration failureCheckInterval();

        /**
         * How long after a print starts before the first-layer quality check fires.
         */
        @WithDefault("8m")
        Duration firstLayerDelay();

        /**
         * HTTP request timeout for each Ollama inference call.
         */
        @WithDefault("60s")
        Duration timeout();

    }

    Optional<String> liveViewUrl();

    Dashboard dashboard();

    BatchPrint batchPrint();

    Map<String, Printer> printers();

    @WithDefault("false")
    boolean autoLogin();

    Map<String, User> users();

    Optional<List<Temperature>> preheat();

    Cloud cloud();

    Etsy etsy();

    Ebay ebay();

    public interface Ebay {

        /**
         * eBay App ID (Client ID), from https://developer.ebay.com/my/keys
         */
        Optional<String> clientId();

        /**
         * eBay Cert ID (Client Secret).
         */
        Optional<String> clientSecret();

        /**
         * The "RuName" (redirect URL name) eBay assigns your app - an opaque identifier, NOT a real URL. Set the
         * actual HTTPS callback (this app's /ebay-oauth-callback) as the "Auth Accepted URL" for this RuName in
         * Your Account &gt; Application Keys &gt; User Tokens.
         */
        Optional<String> ruName();

        /**
         * Marketplace to fetch orders for, e.g. EBAY_US, EBAY_GB, EBAY_AU.
         */
        @WithDefault("EBAY_US")
        String marketplaceId();

        /**
         * Use the sandbox environment (auth.sandbox.ebay.com / api.sandbox.ebay.com) instead of production.
         */
        @WithDefault("false")
        boolean sandbox();

        /**
         * How often to poll eBay for new/updated unfulfilled orders.
         */
        @WithDefault("10m")
        Duration pollInterval();

        @WithDefault("30s")
        Duration timeout();

        @WithDefault("bambu-ebay-tokens.json")
        String tokenFile();

        @WithDefault("bambu-ebay-mappings.json")
        String mappingFile();

    }

    public interface Etsy {

        /**
         * Etsy App API Key (keystring), from https://www.etsy.com/developers/your-apps
         */
        Optional<String> clientId();

        /**
         * Etsy App shared secret, used together with the keystring for the x-api-key header.
         */
        Optional<String> sharedSecret();

        /**
         * Numeric shop ID to pull receipts/listings for.
         */
        Optional<String> shopId();

        /**
         * Callback URL registered with the Etsy app, e.g. https://backup.lockoncomputer.com:8081/etsy-oauth-callback
         */
        Optional<String> redirectUri();

        /**
         * How often to poll Etsy for new/updated unfulfilled orders.
         */
        @WithDefault("10m")
        Duration pollInterval();

        @WithDefault("30s")
        Duration timeout();

        @WithDefault("bambu-etsy-tokens.json")
        String tokenFile();

        @WithDefault("bambu-etsy-mappings.json")
        String mappingFile();

    }

    public interface BatchPrint {

        @WithDefault("true")
        boolean skipSameSize();

        @WithDefault("true")
        boolean timelapse();

        @WithDefault("true")
        boolean bedLevelling();

        @WithDefault("true")
        boolean flowCalibration();

        @WithDefault("true")
        boolean vibrationCalibration();

        @WithDefault("true")
        boolean enforceFilamentMapping();

        @WithDefault("bambu-library")
        String library();
        
    }

    public interface Cloud {

        @WithDefault("false")
        boolean enabled();

        @WithDefault("ssl://us.mqtt.bambulab.com:8883")
        String url();

        Optional<String> username();

        Optional<String> token();

    }

    public interface Dashboard {

        @WithDefault("true")
        boolean remoteView();

        @WithDefault("true")
        boolean filamentFullName();

    }

    public interface Printer {

        @WithDefault("true")
        boolean enabled();

        Optional<String> name();

        String deviceId();

        @WithDefault("bblp")
        String username();

        String accessCode();

        String ip();

        @WithDefault("true")
        boolean useAms();

        @WithDefault("true")
        boolean timelapse();

        @WithDefault("true")
        boolean bedLevelling();

        @WithDefault("true")
        boolean flowCalibration();

        @WithDefault("true")
        boolean vibrationCalibration();

        Mqtt mqtt();

        Ftp ftp();

        Stream stream();

        @WithDefault("unknown")
        @WithConverter(PrinterModelConverter.class)
        PrinterModel model();

        /**
         * Base URL of a Tasmota smart plug powering this printer, e.g. http://192.168.1.50
         */
        Optional<String> tasmota();

        /**
         * For multi-outlet Tasmota devices (power strips), the outlet channel number: 1, 2, 3…
         * Leave empty (default) for single-outlet plugs.
         * Config: bambu.printers.&lt;id&gt;.tasmota-channel=2
         */
        Optional<Integer> tasmotaChannel();

        public interface Mqtt {

            @WithDefault("8883")
            int port();

            Optional<String> url();

            Optional<String> reportTopic();

            Optional<String> requestTopic();

            @WithDefault("10m")
            Duration fullStatus();

        }

        public interface Ftp {

            @WithDefault("990")
            int port();

            Optional<String> url();

            @WithDefault("false")
            boolean logCommands();

        }

        public interface Stream {

            @WithDefault("true")
            boolean enabled();

            @WithDefault("6000")
            int port();

            @WithDefault("false")
            boolean liveView();

            Optional<String> url();

            @WithDefault("5m")
            Duration watchDog();
        }
    }

    public interface User {

        String password();

        String role();

        Optional<Boolean> darkMode();

    }

    public interface Temperature {

        String name();

        int bed();

        int nozzle();
    }
}
