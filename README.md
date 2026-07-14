# Cannot print with latest firmware
> [!IMPORTANT]  
> https://wiki.bambulab.com/en/p1/manual/p1p-firmware-release-history
>
> Bambulab decided to block printing via MQTT unless you enable lanmode only.
>
> Consider downgrading firmware Reference [!142](https://github.com/TFyre/bambu-farm/issues/142)
>
> **OR**
>
> Check the [Cloud Section](#cloud-section) about enabling cloud mode


# Bambu Farm
[![ko-fi](https://img.shields.io/static/v1?label=Support+me+on&message=Ko-fi&logo=ko-fi&color=%23FF5E5B&style=for-the-badge)](https://ko-fi.com/tfyre)
[![GitHub](https://img.shields.io/static/v1?label=Sponsor+me+on&message=%E2%9D%A4&logo=GitHub&color=%23fe8e86&style=for-the-badge)](https://github.com/sponsors/TFyre)

Web based application to monitor multiple bambu printers using mqtt / ftp / rtsp (**no custom firmware required**)

Technologies used:
* Java 21 https://www.azul.com/
* Quarkus https://quarkus.io/
* Vaadin https://vaadin.com/

> **This fork** adds a farm-management layer on top of upstream: dashboard/UI overhaul, batch print library & queue, print history/cost tracking, maintenance tracking, notifications, Tasmota smart plug control, AI-based print/bed monitoring, AMS tray override, Etsy/eBay order-to-print integration, PWA install, and camera access from outside your LAN without forwarding an extra port. See [Fork Additions](#fork-additions) below for the full list and how to configure each one.

# Features / Supported Devices

| Feature | A1 | A1 Mini | P1P | P1S | X1C | X1E | H2D |
|--|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
|**Remote View**|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] <sup>3</sup></li></ul>|<ul><li>[x] <sup>3</sup></li></ul>|<ul><li>[x] <sup>3,6</sup></li></ul>|
|**Upload to SD card**|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] <sup>2</sup></li></ul>|<ul><li>[x] <sup>2</sup></li></ul>|<ul><li>[x] <sup>2</sup></li></ul>|
|**Print .3mf from SD card**<sup>1</sup>|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] <sup>2</sup></li></ul>|<ul><li>[x] <sup>2</sup></li></ul>|<ul><li>[x] <sup>2</sup></li></ul>|
|**Print .gcode from SD card**|?|?|?|?|?|?|?|
|**Batch Printing**<sup>4</sup>|?|?|?|<ul><li>[x] </li></ul>|<ul><li>[x] <sup>2</sup></li></ul>|<ul><li>[x] <sup>2</sup></li></ul>|<ul><li>[x] <sup>2</sup></li></ul>|
|**AMS**|?|?|?|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] <sup>6</sup></li></ul>|
|**AMS Slot Override**<sup>5</sup>|?|?|?|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] <sup>6</sup></li></ul>|
|**Send Custom GCode**|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|<ul><li>[x] </li></ul>|

1. **Currently only .3mf sliced projects are supported.**
  > In Bambu Studio/Orca slicer, make sure to slice the place and then use the "File -> Export -> Export plate sliced file". This creates a `.3mf` project with embedded `.gcode` plate.
2. **FTPS Connections needs SSL Session Reuse via [Bouncy Castle](#bouncy-castle)**
> Without enabling bouncy castle, you will see `552 SSL connection failed: session resuse required`
3. Getting the **LiveView** to work requires additional software. For more details check the [docker/bambu-liveview](docker/bambu-liveview) README. This fork adds a WebRTC (WHEP) stream with automatic HLS fallback so the camera also works from outside your LAN without forwarding an extra port - see [Cameras and remote access](#cameras-and-remote-access).
4. **Batch Priting** allows you to upload a single/multi sliced .3mf and select which plate to send to multiple printers, each with their own filament mapping.
5. Force a print onto one specific AMS tray (or the external spool), overriding the printer's current filament assignment - see [AMS Slot Override](#ams-slot-override).
6. **H2D** has two independent nozzles, each of which can be fed by an AMS unit or its own external spool slot. Dashboard, camera overlay, and print dialogs all show both nozzles side by side.

# Screenshots

* Dashboard
![Desktop browser](/docs/bambufarm1.jpg)
* Batch printing
![Batch Printing](/docs/batchprint.png)

*More screenshots in [docs](/docs)*

# I just want to run it

* Make sure you have Java 21 installed, verify with `java -version`
```bash
[user@build:~]# java -version
openjdk version "21.0.1" 2023-10-17 LTS
OpenJDK Runtime Environment Zulu21.30+15-CA (build 21.0.1+12-LTS)
OpenJDK 64-Bit Server VM Zulu21.30+15-CA (build 21.0.1+12-LTS, mixed mode, sharing)
```
* Download the latest `bambu-web-*-runner.jar` from [releases](https://github.com/TFyre/bambu-farm/releases/latest) into a new folder (or use the 1 liner below):
```bash
curl -s https://api.github.com/repos/tfyre/bambu-farm/releases/latest \
  | grep browser_download_url | cut -d'"' -f4 | xargs curl -LO
```
* Create a `.env` config file from [Minimal Config](#minimal-config)
  * *Check out the [Full Config Options](#full-config-options) section if you want to tweak some settings*
* Run with `java -jar bambu-web-x.x.x-runner.jar`
```bash
[user@build:~]# java -jar bambu-web-1.0.1-runner.jar
__  ____  __  _____   ___  __ ____  ______
 --/ __ \/ / / / _ | / _ \/ //_/ / / / __/
 -/ /_/ / /_/ / __ |/ , _/ ,< / /_/ /\ \
--\___\_\____/_/ |_/_/|_/_/|_|\____/___/
2024-01-23 08:49:05,586 INFO  [io.und.servlet] (main) Initializing AtmosphereFramework
...
...
2024-01-23 08:49:05,666 INFO  [com.vaa.flo.ser.DefaultDeploymentConfiguration] (main) Vaadin is running in production mode.
2024-01-23 08:49:05,912 INFO  [org.apa.cam.qua.cor.CamelBootstrapRecorder] (main) Bootstrap runtime: org.apache.camel.quarkus.main.CamelMainRuntime
2024-01-23 08:49:05,913 INFO  [org.apa.cam.mai.MainSupport] (main) Apache Camel (Main) 4.2.0 is starting
...
...
2024-01-23 08:49:06,029 INFO  [com.tfy.bam.cam.CamelController] (main) configured
2024-01-23 08:49:06,074 INFO  [org.apa.cam.imp.eng.AbstractCamelContext] (main) Apache Camel 4.2.0 (camel-1) is starting
2024-01-23 08:49:06,081 INFO  [org.apa.cam.imp.eng.AbstractCamelContext] (main) Routes startup (total:10 started:0 disabled:10)
...
...
2024-01-23 08:49:06,085 INFO  [org.apa.cam.imp.eng.AbstractCamelContext] (main) Apache Camel 4.2.0 (camel-1) started in 10ms (build:0ms init:0ms start:10ms)
2024-01-23 08:49:06,193 INFO  [io.quarkus] (main) bambu-web 1.0.1 on JVM (powered by Quarkus 3.6.6) started in 1.421s. Listening on: http://0.0.0.0:8084
2024-01-23 08:49:06,194 INFO  [io.quarkus] (main) Profile prod activated.
2024-01-23 08:49:06,194 INFO  [io.quarkus] (main) Installed features: [camel-core, camel-direct, camel-paho, cdi, resteasy-reactive, resteasy-reactive-jackson, 
scheduler, security, servlet, smallrye-context-propagation, vaadin-quarkus, vertx, websockets, websockets-client]
```
* If starting correctly, it will show `Routes startup (total:10 started:0 disabled:10)` with a number that is 2x your printer count
* Head over to http://127.0.0.1:8080 and log in with `admin` / `admin`

# Building & Running

Building:
```bash
mvn clean install -Pproduction
```

> **Frontend bundle caching:** Vaadin caches the compiled frontend bundle at `bambu/src/main/bundles/prod.bundle`. When only **theme/CSS or index.html** changes, the cache may be reused and your changes silently won't appear. Force a full frontend rebuild with:
> ```bash
> mvn clean install -Pproduction -Dvaadin.force.production.build=true
> ```
> (or delete `bambu/src/main/bundles/prod.bundle` before building). Java-only changes never need this.

Create a new directory and copy `bambu/target/bambu-web-1.0.0-runner.jar` into it, example:
```bash
tfyre@fsteyn-pc:/mnt/c/bambu-farm$ ls -al
total 64264
drwxrwxrwx 1 tfyre tfyre     4096 Jan 17 16:47 .
drwxrwxrwx 1 tfyre tfyre     4096 Jan 18 20:42 ..
-rw-rw-rw- 1 tfyre tfyre     4557 Jan 18 14:01 .env
-rw-rw-rw- 1 tfyre tfyre 65796193 Jan 18 20:38 bambu-web-1.0.0-runner.jar
```

Running
```bash
java -jar bambu-web-1.0.0-runner.jar
```

You can now access it via http://127.0.0.1:8080 (username: admin / password: admin)

# Running as a service

Refer to [README.service.md](/docs/README.service.md)

---

# Fork Additions

Everything below is new on top of upstream. Sidebar pages referenced here are visible once you're logged in - most require the `admin` role (see [User Section](#user-section)).

## UI & Dashboard

### OLED dark theme
The existing Dark Theme toggle now renders a true-black OLED look: pure black page background, elevated dark card surfaces with hairline borders, high-contrast text, green progress accents. The light theme is unchanged. Theme file: `bambu/frontend/themes/bambu-theme/oled.css`.

### Layout
- **Sidebar** holds all navigation with icons, plus Dark Theme / Notifications / Logout pinned at the bottom. On desktop, the menu button toggles between the full 200px drawer and a 60px icon-only rail (remembered per browser). On mobile it remains an overlay.
- **Top bar** is minimal: menu, title, and centered page controls. On phones (<820px) the title hides and controls become icon-only on a single row.
- **Favicon** at `/favicon.svg`, replaceable at `bambu/src/main/resources/META-INF/resources/favicon.svg`.

### Dashboard
- **Overview bar** at the top: colored status dots (blue printing / green available / grey offline / red errors with printer names), plus "Next available: P1S 31% • 1h 40m (~21:38)". When Etsy/eBay is connected and there are open orders, amber **"Etsy N" / "eBay N"** chips appear at the end (admin only) - click one to jump to that Sales Orders page. No chip = nothing outstanding.
- **Responsive card grid**: column count derives from a minimum card width (380px). Phones get one full-width column, ultrawides get many. Override in a custom `styles.css` next to the jar:

  ```css
  :root { --bambu-card-min: 500px; }  /* wider cards = fewer columns */
  ```
- **Rearrange**: drag a card by its name header onto another card.
- **Resize**: drag a card's right edge - it snaps to 1-6 grid columns on release.
- **Sort** dropdown: Custom (drag order) / Name / Status / Next Available. Manual dragging switches back to Custom.
- **Compact view**: "Toggle View" switches to a dense table (status, file, progress, ETA per row).
- **Reset Layout** clears order, sizes, and sorting.
- **Per-card extras**: Print Again button (green, bottom), Start Next queued job (blue, bottom), print queue dialog, maintenance-due wrench (red, opens Maintenance), Tasmota plug menu, fullscreen on thumbnail click.
- **Global lights**: header buttons switch all printer chamber lights on/off.
- **AMS / filament tray highlight**: whichever AMS tray (or external spool slot) is currently feeding the hotend is highlighted with a pulsing glow while printing, so you can see at a glance which color/spool is loaded - including on H2D's independent left/right nozzle slots.
- **H2D dual-nozzle support**: per-nozzle temperatures, side-by-side external spool slots with "Left Nozzle" / "Right Nozzle" labels, fan speed, firmware/module info, and build plate ID all shown per printer.

All layout preferences are stored in **browser localStorage** (per browser/device, not per account).

### Remember Me login
The login page has a "Remember this device" checkbox that stores a secure token (30-day expiry) so you don't have to log in again on that browser. Works alongside normal username/password auth; no configuration needed. Tokens survive server restarts - they're persisted (SHA-256 hashed, so the file never contains a usable credential) to `bambu-remember-me.json`.

## Cameras and remote access

The **Cameras** page (`/cameras`, sidebar) is a camera wall with overlays on the image: printer name (click = printer detail), status, progress bar with %, time remaining + clock ETA, and error messages. Cards resize with grid snapping like the dashboard. Click a card for fullscreen (a great single-printer kiosk view).

**Accessing the camera from outside your LAN** normally means forwarding an extra port for the video stream. This fork's camera view instead:
1. Tries **WHEP (WebRTC-HTTP Egress Protocol)** first - connects in about a second when UDP/WebRTC traffic isn't blocked (typical on LAN, and on many external networks too).
2. Falls back automatically to **HLS** (via a self-hosted `hls.js`, no CDN dependency) after a few seconds if WHEP can't establish - HLS works over plain HTTPS, so it gets through networks/proxies that block WebRTC, at the cost of a few seconds of latency.

This requires mediamtx + the reverse proxy config in [docker/bambu-liveview](docker/bambu-liveview) - see that folder's README for setup.

## SD card browser

The **SD Card** page (`/sdcard`, sidebar):
- Multi-select checkboxes + "Delete Selected" (single confirmation for all files).
- Sortable Name column; long names get tooltips.
- "Columns" button to show/hide columns; all columns resizable. Choices persist per browser.
- Toolbar wraps on narrow screens.
- Thumbnail preview for `.3mf` files.
- **Print dialog AMS Slot Override**: when printing a `.3mf` straight from the printer's SD card, an optional "AMS Slot Override" dropdown lets you force the print onto one specific AMS tray or the external spool, instead of relying on the plain "Use AMS" checkbox - see [AMS Slot Override](#ams-slot-override).

## Batch print: library and queue

The **Batch Print** page (`/batchprint`, sidebar).

### Project library
Uploading a `.3mf` on the Batch Print page saves it permanently to the library folder on the server. The **Library** dropdown reloads any saved project instantly - no re-upload from your PC. The trash button removes a project.

Combined with **Skip if same size** (on by default, skips the printer SD upload when unchanged) repeat batch prints start in seconds.

```properties
bambu.batch-print.library=bambu-library
```

### Print queue
- In Batch Print, select printers (they may be busy - only filament mapping is required) and click **Queue**. The job stores file, plate, options, and per-printer AMS mapping in `bambu-queue.json`.
- When a printer is idle (not printing/paused/etc.) with queued jobs, its dashboard card shows a **"Start Next (N queued): file"** button. This button is deliberately hidden while the printer is busy - there's nothing to start until the current job finishes, so don't be alarmed if you queue jobs and don't see a Start button right away; it appears once the printer goes idle. Clicking it asks *"Is the bed clear?"* (backed by the AI bed-clear check when configured - see [AI Print Monitoring](#ai-print-monitoring)) then uploads from the library (skipped when already on SD) and starts the print. Nothing auto-starts unless you explicitly enable AI-gated auto-start for that printer (below).
- The queue icon in the card toolbar opens a per-printer queue dialog (view/remove entries) - or see **Print Queue** below for all printers at once.

### Print Queue page
The **Print Queue** page (`/print-queue`, sidebar) shows every printer's queue in one place instead of opening each card's dialog individually - one section per printer with its queued jobs (remove any entry), current state, the same AI-gated **Start Next** button as the dashboard card, and the per-printer **auto-start** toggle below.

### AI-gated auto-start (lights-out mode)
Per-printer opt-in on the Print Queue page: *"Auto-start next when bed is clear (AI-checked)"*. When enabled, a server-side watcher (runs with no browser open) checks every minute; once the printer has been ready - finished, idle, or failed - for the settle delay with jobs queued, it runs the AI bed-clear check and:
- **Bed clear** → starts the next queued job and sends an `auto_start` notification.
- **Bed not clear** → does NOT start, sends one `auto_start_blocked` notification **with the camera frame attached**, then silently re-checks every 15 minutes (clearing the bed doesn't change printer state, so the periodic recheck is what picks it up). You're only notified once per situation, not every retry.
- **Fails closed**: if AI checks are disabled/unavailable or no snapshot can be grabbed, nothing starts - you get an `auto_start_blocked` notification instead. No AI answer = no start, ever.

A failed print counts as ready: if the AI confirms the bed is clear after a failure, the queue keeps moving. The status line next to the toggle shows the watcher's last decision (e.g. "waiting: settle", "blocked: bed not clear (12:03)", "auto-started at 03:41"). Settings persist in `bambu-auto-start.json`.

```properties
bambu.queue-file=bambu-queue.json
# How long a printer must sit ready before auto-start attempts it (default 3m)
bambu.auto-start-settle=3m
```

## AMS Slot Override

Force a print to load filament from one specific physical AMS tray - or the external spool - instead of whatever the printer currently has assigned, for:
- **SD card prints**: the "AMS Slot Override" dropdown in the SD Card page's print dialog (`/sdcard`).
- **Etsy/eBay queued prints**: an "AMS slot" dropdown per mapped part in the [Etsy and eBay order mapping](#etsy-and-ebay-order-to-print-integration) editor.

Choices are A1-D4 (covering up to 4 AMS units) plus "External Spool"; leaving it blank keeps the printer's current/default filament assignment untouched. This assumes a single-material print - multi-color files aren't individually remapped per color. The dashboard highlights whichever tray is actually feeding the hotend, so you can confirm the override took effect (see [Dashboard](#dashboard) above).

## History, stats, charts and cost

A background service records every print (any source - app, Bambu Studio, SD) by watching state transitions: file, start, duration, result (Finished/Failed/Stopped/Offline). Stored in `bambu-history.json` (capped at 1,000 jobs).

The **History** page shows per-printer stat badges (prints, success %, total time), two charts (prints per day over 14 days as stacked finished/failed bars; 7-day utilization % per printer), and a sortable job grid.

### Cost per job
```properties
bambu.cost-per-kg=18.50
bambu.currency-symbol=$
bambu.history-file=bambu-history.json
```
When `cost-per-kg` > 0, History gains Weight and Cost columns and totals in the stat badges. Weights are captured from the plate data when prints start via Batch Print or the queue (prints started elsewhere show `--`).

## Maintenance and print hours

Print hours accumulate per printer while the app runs (`bambu-maintenance.json`). The **Maintenance** view shows a Print Hours column and a wrench dialog per printer with maintenance tasks - defaults: carbon rods 200h, lead screws 300h, nozzle/hotend 100h, belts 500h - each showing hours since last done (red when overdue), Done/Remove buttons, and custom task creation.

Set each printer's real starting total in the dialog (tracking starts at zero). Overdue tasks show a red wrench on the dashboard card.

```properties
bambu.maintenance-file=bambu-maintenance.json
```

### Backup
The **Backup** button in Maintenance downloads a zip of all state files (maintenance, history, queue) plus the entire project library. Your `.env` is excluded (it contains access codes) - back it up separately.

## AI Print Monitoring

The **AI Settings** page (`/ai-settings`, sidebar). Uses a self-hosted [Ollama](https://ollama.com/) instance with a vision-capable model to watch printer camera snapshots and catch problems automatically:
- **Failure detection**: actively-printing printers are checked periodically for spaghetti/detached prints.
- **First-layer quality check**: fires once, a configurable delay after a print starts.
- **Bed-clear check**: gates the dashboard's "Start Next" queue action - it asks the AI to confirm the bed looks clear before letting the next queued job start (in addition to the "Is the bed clear?" prompt).

Every check is **context-aware**: if the printer currently has an active HMS alert (e.g. a nozzle clog) or a legacy print-error code, that's passed to the model as a hint alongside the image, so a check can correlate what it sees with what the printer's own firmware is already reporting. It's framed as a hint, not an instruction - a stale or unrelated alert (e.g. an AMS calibration reminder) won't force a false positive on its own.

Results show as a status chip on each dashboard card (with an animated "checking" dot) and on the AI Settings page, which has:
- a runtime on/off toggle (no restart needed) and a "Check Now" button per printer;
- **the last analyzed snapshot per printer** - the exact camera frame the AI looked at, why the check ran (manual / scheduled / Start Next gate / auto-start gate), what HMS/error hint was fed to the model, and what it concluded (click to enlarge);
- **check history** - the last 50 check attempts across the farm with trigger, result, and description; click any row to see that check's snapshot (in-memory, resets on restart);
- **editable prompts** - the exact text sent to the model for each of the three checks, editable at runtime and saved to `bambu-ai-prompts.json` (blank or default-identical text reverts to the built-in default, so future stock-prompt improvements still reach you). Keep the leading YES/NO/GOOD answer-keyword instructions intact - result parsing depends on that first word.

```properties
# Base URL of your Ollama server - AI checks are fully skipped when this is unset
bambu.ollama.url=http://192.168.1.x:11434
# Vision-capable model, e.g. gemma3:12b, llava, moondream2
bambu.ollama.model=gemma3:12b
bambu.ollama.failure-check-interval=5m
bambu.ollama.first-layer-delay=8m
bambu.ollama.timeout=60s
```

### Snapshots on X1C / X1E / H2D

P1S/A1/A1mini/P1P push raw JPEG frames over the port-6000 stream the app already uses for camera snapshots, so AI checks work with no extra setup. **X1C, X1E, and H2D don't push that stream** - their firmware only exposes the camera over RTSPS on port 322 - so on those models the app instead grabs a single frame on demand using **ffmpeg** (frames are cached a few seconds so back-to-back checks don't re-grab). This needs an `ffmpeg` binary reachable by the `bambuweb` process itself:

- **Bare metal / systemd**: install ffmpeg normally (`apt install ffmpeg`, `choco install ffmpeg`, ...) and make sure it's on `PATH`.
- **Docker**: the `bambuweb` service currently runs the stock `azul/zulu-openjdk:21-latest` image, which doesn't include ffmpeg. Build a small custom image on top of it, e.g.
  ```dockerfile
  FROM azul/zulu-openjdk:21-latest
  RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
  ```
  then point `bambuweb` at it (`build: .` instead of `image: azul/zulu-openjdk:21-latest` in your compose file).

```properties
# Only needed if ffmpeg isn't already on PATH
bambu.ffmpeg-path=/usr/bin/ffmpeg
```

**Where the frame actually comes from matters.** Bambu's camera firmware only accepts one RTSPS client at a time. If you're using `stream.live-view=true` for a printer (the usual setup for X1C/X1E/H2D, so the dashboard camera view works at all via `docker/bambu-liveview`'s WHEP/HLS pipeline), a persistent ffmpeg bridge container already holds that printer's single RTSPS connection permanently. Connecting a second time straight to the printer for an AI-check snapshot will either fail outright or **knock the existing live view offline** - this actually happened during development, not just a theoretical risk.

To avoid that, set `bambu.mediamtx-rtsp-url` to your mediamtx instance's internal RTSP address - AI checks then pull the frame from the same already-open relay instead of connecting to the printer again:

```properties
# For the shipped docker/bambu-liveview setup, this is the mediamtx service's RTSP port
bambu.mediamtx-rtsp-url=rtsp://mediamtx:8554
```

With this set, a printer with `stream.live-view=true` gets its AI-check frames from `<mediamtx-rtsp-url>/<printer-key>` (the same path its "liveview" bridge container publishes to, e.g. `rtsp://mediamtx:8554/printer5` for a printer configured as `bambu.printers.printer5.*`) - safe to pull from repeatedly, no effect on the printer or the live view. Leave `bambu.mediamtx-rtsp-url` unset only if a printer has no live-view bridge already connected to it (then a direct RTSPS connection is safe, since nothing else is using it).

Without ffmpeg reachable, AI checks on X1C/X1E/H2D printers keep showing "no snapshot available yet" (a warning is logged once per printer, not spammed on every check) - everything else in the app is unaffected.

## Notifications

### Browser notifications
The **Notifications** checkbox in the sidebar enables desktop notifications on print finish/fail (requires an open tab and HTTPS or localhost).

### Notification Settings page
The **Notification Settings** page (`/notification-settings`, sidebar) shows whether webhook/MQTT are currently configured (with credentials masked), lets you toggle individual event types on/off at runtime without restarting (New Order, Auto-Queue, Auto-Queue Skipped, Auto-Start, Auto-Start Blocked, AI Failure Detected, AI First Layer Issue, Printer Error, Maintenance Due, Print Finished/Failed/Stopped - saved to `bambu-notification-suppressed.json`, survives restarts), and has a "Send Test" button that fires a test event to all configured channels regardless of the toggles above.

### MQTT (recommended for Home Assistant)
```properties
bambu.notifications.mqtt.url=tcp://192.168.1.10:1883
bambu.notifications.mqtt.username=user
bambu.notifications.mqtt.password=pass
bambu.notifications.mqtt.topic=bambufarm
```
Events publish to `bambufarm/<printer>/<event>` where event is `finish`, `fail`, `stopped`, `error`, `maintenance`, `failure_detected`, `first_layer_issue`, `auto_start`, `auto_start_blocked`, `new_order`, `auto_queue`, or `auto_queue_skipped` (for the order events the printer segment is the marketplace, `Etsy`/`eBay`/`etsy`/`ebay`), with JSON payload:

```json
{"timestamp":"2026-06-12T21:30:00-04:00","event":"fail","printer":"P1S-2","message":"Print failed: part.3mf (2h 14m)"}
```

Example Home Assistant automation trigger:

```yaml
trigger:
  - platform: mqtt
    topic: bambufarm/+/fail
```

### Webhook (Discord / ntfy / generic)
```properties
bambu.notifications.webhook-url=https://discord.com/api/webhooks/...
bambu.notifications.webhook-format=discord   # json | discord | ntfy
```
Both MQTT and webhook can be enabled at once. Printer errors are checked every 30s; maintenance-due every 6h (deduped until the task is marked done).

**AI alerts include the camera frame**: when the AI failure or first-layer check fires an alert, the snapshot it analyzed is attached to the webhook delivery (Discord: image upload in the message; ntfy: attachment) - so you can judge "spaghetti or false positive?" straight from your phone. The generic `json` format and MQTT stay text-only.

## Tasmota smart plugs

```properties
bambu.printers.myprinter1.tasmota=http://192.168.1.50
# For multi-outlet Tasmota power strips only - leave unset for single-outlet plugs
bambu.printers.myprinter1.tasmota-channel=2
```
Adds a plug button to that printer's dashboard card with Power On / Power Off (confirmed; an extra warning appears if the printer is printing). Uses Tasmota's `/cm?cmnd=Power%20On|Off` HTTP API (no web password support yet).

The **Tasmota Settings** page (`/tasmota-settings`, sidebar) is a central control panel: one card per printer with a plug configured, showing live status (ON/OFF/Unreachable) plus Power On / Power Off / Refresh buttons, so you don't need to go to each printer's dashboard card individually.

## Etsy and eBay order-to-print integration

Two sidebar pages - **Etsy Sales Orders** (`/etsy-orders`) and **eBay Sales Orders** (`/ebay-orders`) - pull unfulfilled/open orders from your shop and let you map each listing straight to a print job, then queue it across your printers. Both marketplaces behave identically (same mapping model, same queueing logic).

**Connect**: each page has a "Connect" button that runs the marketplace's OAuth flow (Etsy uses PKCE; eBay uses Basic auth with your app's RuName) and stores tokens locally (`bambu-etsy-tokens.json` / `bambu-ebay-tokens.json`).

**Map a listing to a print**: for each order line item, add one or more parts:
- **Source**: a file from the batch print library, or a path already on every printer's SD card (same path on every printer).
- **Plate**: which plate/plate index to print.
- **Copies/unit**: how many times this part must print per 1 unit ordered (e.g. a part that only fits once per bed needs `copies=2` for a 2x order).
- **AMS slot** (optional): force this part onto one AMS tray or the external spool - see [AMS Slot Override](#ams-slot-override).
- **Filament** (optional): the material this part must print in (PETG, ASA, ...). Used by auto-queue to pick a printer that actually has it loaded (see below); manual queueing ignores it.

A listing can have multiple parts - useful for kits made of several different gcode files/plates. Mappings are keyed by listing + variation, so different color/size variations of the same listing can map to different files.

**Queue**: pick one or more printers and click "Queue Print" - jobs are distributed round-robin across the selected printers (`orderedQuantity × copiesPerUnit` jobs per part).

Orders are polled on a schedule and filtered to unfulfilled/open only; poll errors (bad credentials, wrong shop ID, etc.) show directly on the page instead of silently reporting "no orders".

**New-order alerts**: when a poll finds an order it has never seen before, a `new_order` notification fires to your configured channels (Discord/ntfy/MQTT). Seen-order IDs are persisted (`bambu-order-tracking.json`), so restarts don't re-alert, and connecting a shop for the first time doesn't fire one alert per existing order. Toggle on the Notification Settings page.

**Auto-queue (zero-click repeat orders)**: an opt-in "Auto-queue new orders" toggle on either Sales Orders page (one global switch, persisted to `bambu-auto-queue.json`). When a poll finds a NEW order whose line items are all mapped, the print jobs are queued automatically:
- Each mapped part can specify a required **filament type** (e.g. PETG, ASA) next to its AMS slot in the mapping editor. Auto-queue matches this against each printer's **live AMS telemetry**: with a slot also set, that exact tray must currently hold that material (catches a swapped spool); with type only, any tray with that material qualifies and the job is pinned to it per printer.
- Among qualifying printers: idle with an empty queue wins, then shortest queue.
- **All-or-nothing per order**: an unmapped line item, a missing library file, or a part no printer has filament for skips the whole order with an `auto_queue_skipped` notification saying exactly why - nothing partial, queue it manually instead.
- Queued orders get the "✓ queued" badge and are never auto-queued twice. Success fires an `auto_queue` notification with the job distribution (e.g. "6 jobs → P1S×3, P1P×3").

Combined with [AI-gated auto-start](#ai-gated-auto-start-lights-out-mode), a repeat order goes from purchase to printing with zero clicks: poll finds it → jobs queue to printers with the right filament → auto-start begins each one after the AI confirms the bed is clear.

**Queued badge**: once you queue print jobs for an order, its card shows a green **"✓ queued"** badge (hover for when) - persisted, so you can't accidentally print the same order twice after a restart. Dismissed orders are persisted too and stay hidden.

**Dashboard chips**: open order counts show as clickable "Etsy N / eBay N" chips on the dashboard overview bar.

```properties
# Etsy - from https://www.etsy.com/developers/your-apps
bambu.etsy.client-id=REPLACE_WITH_KEYSTRING
bambu.etsy.shared-secret=REPLACE_WITH_SHARED_SECRET
bambu.etsy.shop-id=REPLACE_WITH_NUMERIC_SHOP_ID
bambu.etsy.redirect-uri=https://your-domain:8081/etsy-oauth-callback
bambu.etsy.poll-interval=10m
bambu.etsy.token-file=bambu-etsy-tokens.json
bambu.etsy.mapping-file=bambu-etsy-mappings.json

# eBay - from https://developer.ebay.com/my/keys
bambu.ebay.client-id=REPLACE_WITH_APP_ID
bambu.ebay.client-secret=REPLACE_WITH_CERT_ID
bambu.ebay.ru-name=REPLACE_WITH_RUNAME
bambu.ebay.marketplace-id=EBAY_US
bambu.ebay.sandbox=false
bambu.ebay.poll-interval=10m
bambu.ebay.token-file=bambu-ebay-tokens.json
bambu.ebay.mapping-file=bambu-ebay-mappings.json
```

> If your Etsy shop ID is wrong, the page shows an HTTP 403 "User does not own Shop ..." error with a "Look up my shop ID" button that fetches the correct ID for the account you connected with.

## PWA (install as app)

The app serves a web manifest, service worker, and icon (`bambu/src/main/resources/META-INF/resources/icons/icon.png`). Over **HTTPS**, browsers offer "Install" / "Add to Home Screen" for a standalone fullscreen app - ideal on phones and tablets.

## HTTPS setup

No code needed - Quarkus handles TLS via config. Add to the `.env` next to the jar:

```properties
# PEM certificate + key
quarkus.http.ssl-port=8443
quarkus.http.ssl.certificate.files=/path/to/fullchain.pem
quarkus.http.ssl.certificate.key-files=/path/to/privkey.pem

# optional: redirect all plain-http traffic to https
quarkus.http.insecure-requests=redirect
```

For a PKCS12 keystore instead:

```properties
quarkus.http.ssl-port=8443
quarkus.http.ssl.certificate.key-store-file=/path/to/keystore.p12
quarkus.http.ssl.certificate.key-store-password=changeit
```

Then browse to `https://yourserver:8443`. HTTPS also unlocks browser notifications on any device and PWA installation.

## Quick reference

### New config properties
| Property | Default | Purpose |
|---|---|---|
| `bambu.maintenance-file` | `bambu-maintenance.json` | Print hours + maintenance tasks |
| `bambu.history-file` | `bambu-history.json` | Print job history |
| `bambu.queue-file` | `bambu-queue.json` | Print queues |
| `bambu.batch-print.library` | `bambu-library` | Saved .3mf projects |
| `bambu.cost-per-kg` | `0` | Material cost (enables Cost column) |
| `bambu.currency-symbol` | `$` | Cost display |
| `bambu.printers.X.tasmota` | - | Smart plug base URL |
| `bambu.printers.X.tasmota-channel` | - | Multi-outlet Tasmota channel number |
| `bambu.notifications.mqtt.url` | - | Event broker, e.g. `tcp://ip:1883` |
| `bambu.notifications.mqtt.username/password` | - | Broker credentials |
| `bambu.notifications.mqtt.topic` | `bambufarm` | Topic prefix |
| `bambu.notifications.webhook-url` | - | Webhook target |
| `bambu.notifications.webhook-format` | `json` | `json` / `discord` / `ntfy` |
| `bambu.ollama.url` | - | Ollama server URL (unset = AI checks skipped) |
| `bambu.ollama.model` | `gemma3:12b` | Vision model for AI checks |
| `bambu.ollama.failure-check-interval` | `5m` | How often actively-printing printers are checked |
| `bambu.ollama.first-layer-delay` | `8m` | Delay before the first-layer quality check |
| `bambu.ollama.timeout` | `60s` | Per-request Ollama timeout |
| `bambu.ffmpeg-path` | `ffmpeg` | ffmpeg binary, used to grab AI-check snapshots on X1C/X1E/H2D |
| `bambu.mediamtx-rtsp-url` | - | Internal mediamtx RTSP relay for AI-check snapshots on live-view printers (avoids conflicting with the live-view bridge's own RTSPS connection) |
| `bambu.etsy.client-id` / `shared-secret` | - | Etsy app credentials |
| `bambu.etsy.shop-id` | - | Numeric Etsy shop ID |
| `bambu.etsy.redirect-uri` | - | OAuth callback URL |
| `bambu.etsy.poll-interval` | `10m` | Order polling frequency |
| `bambu.ebay.client-id` / `client-secret` | - | eBay app credentials |
| `bambu.ebay.ru-name` | - | eBay RuName (OAuth redirect identifier) |
| `bambu.ebay.marketplace-id` | `EBAY_US` | eBay marketplace |
| `bambu.ebay.sandbox` | `false` | Use eBay sandbox environment |
| `bambu.ebay.poll-interval` | `10m` | Order polling frequency |
| `bambu.auto-start-settle` | `3m` | How long a printer must sit ready before AI-gated auto-start attempts it |

### Files to back up
`bambu-maintenance.json`, `bambu-history.json`, `bambu-queue.json`, `bambu-etsy-tokens.json`, `bambu-etsy-mappings.json`, `bambu-ebay-tokens.json`, `bambu-ebay-mappings.json`, `bambu-order-tracking.json`, `bambu-remember-me.json`, `bambu-notification-suppressed.json`, `bambu-ams-dry.json`, `bambu-ams-dry-sessions.json`, `bambu-auto-start.json`, `bambu-auto-queue.json`, `bambu-ai-prompts.json`, the library folder, and `.env` - or use the Backup button (covers maintenance/history/queue/library, not `.env` or the marketplace token/mapping files).

### Browser localStorage keys (per device)
Card order/sizes/sort/view-mode, camera sizes, SD card columns, notification opt-in, sidebar rail state, remember-me token. "Reset Layout" on the dashboard/cameras clears the relevant ones.

---

# Example Config

## Minimal config

**!!Remeber to replace `REPLACE_*` fields!!**

Create an `.env` file with  the following config:
```properties
quarkus.http.host=0.0.0.0
quarkus.http.port=8080

bambu.printers.myprinter1.device-id=REPLACE_WITH_DEVICE_SERIAL
bambu.printers.myprinter1.access-code=REPLACE_WITH_DEVICE_ACCESSCODE
bambu.printers.myprinter1.ip=REPLACE_WITH_DEVICE_IP

bambu.users.admin.password=admin
bambu.users.admin.role=admin
```

## Full Config Options

**All default options are displayed (only add to the config if you want to change)**

### Dark Mode
```properties
# Gobal
bambu.dark-mode=false
# Per user (will default to global if omitted)
bambu.users.myUserName.dark-mode=false
```

### Printer section
```properties
bambu.printers.myprinter1.enabled=true
bambu.printers.myprinter1.name=Name With Spaces
bambu.printers.myprinter1.device-id=REPLACE_WITH_DEVICE_SERIAL
bambu.printers.myprinter1.username=bblp
bambu.printers.myprinter1.access-code=REPLACE_WITH_DEVICE_ACCESSCODE
bambu.printers.myprinter1.ip=REPLACE_WITH_DEVICE_IP
bambu.printers.myprinter1.use-ams=true
bambu.printers.myprinter1.timelapse=true
bambu.printers.myprinter1.bed-levelling=true
bambu.printers.myprinter1.flow-calibration=true
bambu.printers.myprinter1.vibration-calibration=true
bambu.printers.myprinter1.model=unknown / a1 / a1mini / p1p / p1s / x1c / x1e / h2d
bambu.printers.myprinter1.mqtt.port=8883
bambu.printers.myprinter1.mqtt.url=ssl://${bambu.printers.myprinter1.ip}:${bambu.printers.myprinter1.mqtt.port}
bambu.printers.myprinter1.mqtt.report-topic=device/${bambu.printers.myprinter1.device-id}/report
bambu.printers.myprinter1.mqtt.request-topic=device/${bambu.printers.myprinter1.device-id}/request
#Requesting full status interval
bambu.printers.myprinter1.mqtt.full-status=10m
bambu.printers.myprinter1.ftp.port=990
bambu.printers.myprinter1.ftp.url=ftps://${bambu.printers.myprinter1.ip}:${bambu.printers.myprinter1.ftp.port}
bambu.printers.myprinter1.ftp.log-commands=false
bambu.printers.myprinter1.stream.port=6000
bambu.printers.myprinter1.stream.live-view=false
bambu.printers.myprinter1.stream.url=ssl://${bambu.printers.myprinter1.ip}:${bambu.printers.myprinter1.stream.port}
#Restart stream if no images received interval
bambu.printers.myprinter1.stream.watch-dog=5m
```

### Farm extras

See [Fork Additions](#fork-additions) above for what each of these enables. Full property list also in the [Quick reference](#quick-reference) table.

```properties
# Tasmota smart plug powering a printer (adds a plug button to the dashboard card)
bambu.printers.myprinter1.tasmota=http://192.168.1.50

# Filament cost per kg - when > 0 the History view shows estimated material cost per job
bambu.cost-per-kg=0
bambu.currency-symbol=$

# Storage locations (relative to the working directory)
bambu.maintenance-file=bambu-maintenance.json
bambu.history-file=bambu-history.json
bambu.queue-file=bambu-queue.json
bambu.batch-print.library=bambu-library

# Event notifications (print finish/fail, printer errors, maintenance due)
# MQTT: published to {topic}/{printer}/{event} as JSON - ideal for Home Assistant
bambu.notifications.mqtt.url=tcp://192.168.1.10:1883
bambu.notifications.mqtt.username=user
bambu.notifications.mqtt.password=pass
bambu.notifications.mqtt.topic=bambufarm
# Webhook alternative: format = json / discord / ntfy
bambu.notifications.webhook-url=https://discord.com/api/webhooks/...
bambu.notifications.webhook-format=discord

# AI print/bed monitoring via Ollama - unset url = fully disabled
bambu.ollama.url=http://192.168.1.x:11434
bambu.ollama.model=gemma3:12b
bambu.ollama.failure-check-interval=5m
bambu.ollama.first-layer-delay=8m

# Etsy order-to-print integration
bambu.etsy.client-id=REPLACE_WITH_KEYSTRING
bambu.etsy.shared-secret=REPLACE_WITH_SHARED_SECRET
bambu.etsy.shop-id=REPLACE_WITH_NUMERIC_SHOP_ID
bambu.etsy.redirect-uri=https://your-domain:8081/etsy-oauth-callback

# eBay order-to-print integration
bambu.ebay.client-id=REPLACE_WITH_APP_ID
bambu.ebay.client-secret=REPLACE_WITH_CERT_ID
bambu.ebay.ru-name=REPLACE_WITH_RUNAME
bambu.ebay.marketplace-id=EBAY_US
```

### Cloud Section

Enable MQTT connection via cloud instead of directly to printer. 

The access userid and token can be fetched from your browser cookies or a multi liner curl
```bash
export MY_USERNAME=fixme@fixme.com
export MY_PASSWORD=fixme

# Request verification code
curl -sS --fail -X POST -H 'Content-Type: application/json' -d "{\"account\":\"${MY_USERNAME}\",\"password\":\"${MY_PASSWORD}\"}" https://api.bambulab.com/v1/user-service/user/login | jq
```

Output:
```json
{
  "accessToken": "",
  "refreshToken": "",
  "expiresIn": 0,
  "refreshExpiresIn": 0,
  "tfaKey": "",
  "accessMethod": "",
  "loginType": "verifyCode"
}
```

```bash
# Check email for verification code
export MY_CODE=1234
curl -sS --fail -X POST -H 'Content-Type: application/json' -d "{\"account\":\"${MY_USERNAME}\",\"code\":\"${MY_CODE}\"}" https://api.bambulab.com/v1/user-service/user/login | jq
```

Output:
```json
{
  "accessToken": "AA...",
  "refreshToken": "SAME_AS_ACCESS_TOKEN",
  "expiresIn": 7776000,
  "refreshExpiresIn": 7776000,
  "tfaKey": "",
  "accessMethod": "",
  "loginType": ""
}
```

```bash
# Grab the access Token
export MY_TOKEN=AA...

# Grab username (uid) from here
curl -sS --fail  -H "Authorization: Bearer ${MY_TOKEN}" https://api.bambulab.com/v1/design-user-service/my/preference | jq '{"username": ("u_" + (.uid | tostring))}'
```

Output:
```json
{
  "username": "u_12345"
}
```

Configuration:

```properties
bambu.cloud.enabled=true
bambu.cloud.username=u_12345
bambu.cloud.token=AA...
```

### User Section

**Remember to encrypt your passwords with bcrypt (eg https://bcrypt-generator.com/)**

Current roles supported:

* `admin` - full access
* `normal` - only dashboard with readonly access

```properties
# https://bcrypt-generator.com/
#bambu.users.REPLACE_WITH_USERNAME.password=REPLACE_WITH_PASSWORD

# Insecure version:
#bambu.users.myUserName.password=myPassword
# Secure version:
bambu.users.myUserName.password=$2a$12$GtP15HEGIhqNdeKh2tFguOAg92B3cPdCh91rj7hklM7aSOuTMh1DC 
bambu.users.myUserName.role=admin
bambu.users.myUserName.dark-mode=false

#Guest account with readonly role
bambu.users.guest.password=guest
bambu.users.guest.role=normal

# Skip users and automatically login as admin (default: false)
bambu.auto-login=true
```

### Batch Print Section
Default batch printing options is below:

```properties
bambu.batch-print.skip-same-size=true
bambu.batch-print.timelapse=true
bambu.batch-print.bed-levelling=true
bambu.batch-print.flow-calibration=true
bambu.batch-print.vibration-calibration=true
bambu.batch-print.enforce-filament-mapping=true
```

### Preheat

Default preheat configuration is below:
```properties
bambu.preheat[0].name=Off 0/0
bambu.preheat[0].bed=0
bambu.preheat[0].nozzle=0
bambu.preheat[1].name=PLA 55/220
bambu.preheat[1].bed=55
bambu.preheat[1].nozzle=220
bambu.preheat[2].name=ABS 90/270
bambu.preheat[2].bed=90
bambu.preheat[2].nozzle=270
```

### Remote View

Remote View is the ability to remotely view or stream the printer's camera.

```properties
# defaults to true, when false, disables remote view globally
bambu.remote-view=true

# defaults to true, when false, disables remote view for dashboard, but will still be available in detail view
bambu.dashboard.remote-view=true

# defaults to true, when false, disables per printer
bambu.printers.myprinter1.stream.enable=true
```


### Live View

Live View is the ability to remotely stream the X1C camera (or any other webcam) and requires Remote View to be enabled.

> [!NOTE]
> Getting the **LiveView** to work requires additional software. For more details check the [docker/bambu-liveview](docker/bambu-liveview) README. This fork's camera page also falls back to HLS automatically when WebRTC can't connect (e.g. from outside your LAN) - see [Cameras and remote access](#cameras-and-remote-access).


```properties
bambu.live-view-url=/_camerastream/

# For each printer:
bambu.printers.PRINTER_ID.stream.live-view=true

# Default LiveView URL
bambu.printers.PRINTER_ID.stream.url=${bambu.live-view-url}${PRINTER_ID}

# Custom LiveView URL
bambu.printers.PRINTER_ID.stream.url=https://my_stream_domain.com/mystream
# 
```


### Bouncy Castle
`X1C` needs SSL Session Reuse so that SD Card functionality can work. Reference: https://stackoverflow.com/a/77587106/23289205

Without this you will see `552 SSL connection failed: session resuse required`.

Add to `.env`:
```properties
bambu.use-bouncy-castle=true
```
Add JVM startup flag:

bash / cmd:
```bash
java -Djdk.tls.useExtendedMasterSecret=false -jar bambu-web-x.x.x-runner.jar
```

powershell:
```powershell
java "-Djdk.tls.useExtendedMasterSecret=false" -jar bambu-web-x.x.x-runner.jar
```

### Uploading bigger files

Add to `.env`:
```properties
quarkus.http.limits.max-body-size=30M
```
> Multi-plate batch print projects can be considerably larger than a single-plate `.3mf` - if uploads fail on the Batch Print page, raise this (e.g. `300M`). If you're behind a reverse proxy (nginx, etc.), also raise its body-size limit (e.g. nginx's `client_max_body_size`) to match.

### Configure XY/Z movement speeds

Add to `.env`:
```properties
# values are in mm/minute
bambu.move-xy=5000
bambu.move-z=3000
```

### Use Right click for menus

Add to `.env`:
```properties
bambu.menu-left-click=false
```

### Display Filament Type instead of Name
Add to `.env`:
```properties
bambu.dashboard.filament-full-name=false
```



### Custom CSS

If you want to modify the CSS, create a file next to the `.jar` file called `styles.css`

#### Changing the display columns

*The dashboard is a CSS grid; the column count is derived from the screen width and a minimum card width (phones get 1 full-width column, ultrawides get many)*

Refer to [bambu.css](/bambu/frontend/themes/bambu-theme/bambu.css#L1-L25)

To change the density, override the minimum card width - smaller values give more columns:

```css
/* wider cards = fewer columns */
:root {
  --bambu-card-min: 500px;
}
```

Cards can also be resized by dragging their right edge (snaps to grid columns) and reordered by dragging the printer name; use "Reset Layout" in the dashboard header to restore defaults.


#### Ordering items inside printer box

* Move display order of `image` / `status` / `filaments` **"down"** so that `progress` is after `name`

```css
.dashboard-printer .image {
    order: 3;
}
.dashboard-printer .status {
    order: 4;
}
.dashboard-printer .filaments {
    order: 1;
}
```

# Debug

For debugging the application, add the following to .env and uncomment DEBUG or TRACE logging sections

```properties
### Log To File
quarkus.log.file.enable=true
quarkus.log.file.path=application.log


### DEBUG logging
#quarkus.log.category."com.tfyre".level=DEBUG


### TRACE logging
#quarkus.log.min-level=TRACE
#quarkus.log.category."com.tfyre".min-level=TRACE
#quarkus.log.category."com.tfyre".level=TRACE
```

# Links

## Inspirational Web interface

* https://github.com/davglass/bambu-farm/tree/main

## Printer MQTT Interface

* https://github.com/Doridian/OpenBambuAPI/blob/main/mqtt.md
* https://github.com/xperiments-in/xtouch/blob/main/src/xtouch/device.h
* https://github.com/SoftFever/OrcaSlicer/blob/main/src/slic3r/GUI/DeviceManager.hpp

## Remoteview

* https://github.com/bambulab/BambuStudio/issues/1536#issuecomment-1811916472

## Marketplace APIs

* Etsy Open API v3: https://developer.etsy.com/documentation/
* eBay Sell Fulfillment API: https://developer.ebay.com/api-docs/sell/fulfillment/overview.html

## Images from

* https://github.com/SoftFever/OrcaSlicer/tree/main/resources/images

## Json to Proto

* https://json-to-proto.github.io/
* https://formatter.org/protobuf-formatter
