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

> **This fork** adds a farm-management and automation layer on top of upstream: dashboard/UI overhaul, batch print library & queue, print history/cost tracking, maintenance tracking, notifications with printer photos, Tasmota smart plug control (incl. idle auto-off), AI-based print/bed monitoring with editable prompts, AMS tray override, Etsy/eBay order-to-print integration, and a full **Automation hub** — new orders auto-queue to filament-matching printers, AI-gated auto-start runs the queue lights-out, failed prints retry once, and you get a "ready to ship" ping when an order's last part finishes. Plus PWA install and camera access from outside your LAN without forwarding an extra port. See [Fork Additions](#fork-additions) below for the full list and how to configure each one.

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
> Without enabling bouncy castle, you will see `522 SSL connection failed: session reuse required` when browsing or uploading to the SD card. `bambu.use-bouncy-castle` **defaults to `false`**, so this is off until you set it.
>
> Not every printer enforces it, which makes it confusing: the P1 series has been fine without it, while an **H2D moved into LAN mode fails immediately**. Its FTPS server requires the data connection to resume the TLS session from the control connection, and the default JSSE stack won't. `BambuFtp._prepareDataSocket_` implements the reuse, but it is a no-op unless Bouncy Castle is enabled - so the symptom is per-printer even though the switch is global.
3. Getting the **LiveView** to work requires additional software. For more details check the [docker/bambu-liveview](docker/bambu-liveview) README. This fork adds a WebRTC (WHEP) stream with automatic HLS fallback so the camera also works from outside your LAN without forwarding an extra port - see [Cameras and remote access](#cameras-and-remote-access).
4. **Batch Priting** allows you to upload a single/multi sliced .3mf and select which plate to send to multiple printers, each with their own filament mapping.
5. Force a print onto one specific AMS tray (or the external spool), overriding the printer's current filament assignment - see [AMS Slot Override](#ams-slot-override).
6. **H2D** has two independent nozzles, each of which can be fed by an AMS unit or its own external spool slot. Dashboard, camera overlay, and print dialogs all show both nozzles side by side. It also has two chamber lights, an active chamber heater, an airduct and a buzzer, all of which this fork drives - see [H2D specifics](#h2d-specifics).
7. **If a printer accepts every command and does nothing**, it wants signed MQTT - turn on Developer Mode. See [When a printer stops responding](#when-a-printer-stops-responding); this is not a fault you can fix in software.

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
- **Tables**: every data grid (Mappings, AI Settings, History, SD Card, Batch Print, Maintenance, Dashboard table view) has **resizable columns** (drag the column border) and **reorderable columns** (drag the header) so you can lay each table out for readability.

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
- **Chamber temperature is settable** on printers with an *active* chamber heater (X1E, H2 series) - right-click the Chamber badge, same as Bed and Nozzle, and it shows a target beside the reading. The X1C reports a chamber temperature but cannot heat it, so it keeps a read-only badge; a Set Target that silently does nothing is worse than none.

  Worth knowing why this isn't just another `M140`: outside the X1E the chamber heater is driven by the **airduct**, so `M141` on its own sets a target nothing acts on. Going above 40 °C sends `M145 P1` first; dropping to 40 or below sends `M145 P0` after the new target, so the duct is never left heating toward a lower number. Expect the airduct to spin up when you set a warm target - that's the heater working, not a fault.
- **Global lights drive both H2D chamber lights.** The H2D has two, and lighting only the first leaves half the chamber dark - which matters beyond aesthetics, because that's the chamber the AI bed check has to photograph.

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
- **Broadcast Upload** sends **one or more** files to every selected printer at once - pick the destination folder (created if missing), drop in as many `.3mf`/`.gcode` files as you like, tick the printers, and each gets all of them over a single FTP connection with live per-printer progress ("uploading 2 of 5").
- Sortable Name column; long names get tooltips.
- "Columns" button to show/hide columns; all columns resizable. Choices persist per browser.
- Toolbar wraps on narrow screens.
- Thumbnail preview for `.3mf` files.
- **Print dialog AMS Slot Override**: when printing a `.3mf` straight from the printer's SD card, an optional "AMS Slot Override" dropdown lets you force the print onto one specific AMS tray or the external spool, instead of relying on the plain "Use AMS" checkbox - see [AMS Slot Override](#ams-slot-override).

## Batch print: library and queue

The **Batch Print** page (`/batchprint`, sidebar).

### Project library
**Projects (multi-file)**: the library is a folder, and any **subfolder holding `.3mf` files is a project** - one product whose parts print separately (speaker pods plus their covers, a bracket plus its cap). Upload accepts **multiple files at once**; the *Save into project* box picks an existing project or names a new one (leave it blank for a loose file at the root, exactly as before). Selecting a project previews its first plate and reveals **Queue whole project**, which distributes every file in it round-robin across the printers you've ticked - the same queuing path marketplace orders use. Selecting an entry lists **its files underneath**, each with a preview button (loads that file's plates) and its own delete, so you can drop one part without deleting the project. A **loose** file instead offers *Move into project…*, which is also how you file something that was saved to the root by mistake. Deleting a whole project removes the `.3mf` files it listed and then the folder, leaving anything else in there untouched. Loose files keep working unchanged, so nothing you already had needs migrating.
Uploading a `.3mf` on the Batch Print page saves it permanently to the library folder on the server. The **Library** dropdown reloads any saved project instantly - no re-upload from your PC. The trash button removes a project.

Combined with **Skip if same size** (on by default, skips the printer SD upload when unchanged) repeat batch prints start in seconds.

```properties
bambu.batch-print.library=bambu-library
```

### Print queue
- In Batch Print, select printers (they may be busy - only filament mapping is required) and click **Queue**. The job stores file, plate, options, and per-printer AMS mapping in `bambu-queue.json`.
- When a queued job starts, the upload is skipped if an identical file (same name and size) already exists on the printer's SD card - at the root **or in a first-level subfolder** (e.g. a hand-organized `_Audi/part.3mf`), in which case the print runs from the existing copy instead of duplicating it at the root.
- When a printer is idle (not printing/paused/etc.) with queued jobs, its dashboard card shows a **"Start Next (N queued): file"** button. This button is deliberately hidden while the printer is busy - there's nothing to start until the current job finishes, so don't be alarmed if you queue jobs and don't see a Start button right away; it appears once the printer goes idle. Clicking it asks *"Is the bed clear?"* (backed by the AI bed-clear check when configured - see [AI Print Monitoring](#ai-print-monitoring)) then uploads from the library (skipped when already on SD) and starts the print. Nothing auto-starts unless you explicitly enable AI-gated auto-start for that printer (below).
- The queue icon in the card toolbar opens a per-printer queue dialog (view/remove entries) - or see **Print Queue** below for all printers at once.

### Automation page
The **Automation** page (`/automation`, sidebar) is the control center for the whole order-to-print pipeline, with three tabs:
- **Overview** - the whole pipeline on one screen. Big one-click toggles up top (Auto-Queue, AI Checks, Auto-Start, Auto-Requeue, Simulate) plus a **Dispatch Now** button, then five headline numbers (open orders, waiting to dispatch, printing, needs attention, today) and live ⏱ countdowns for whatever the pipeline is waiting on, with the **dispatch pool** directly beneath them.
  - **Needs attention** counts parked jobs, failing AI checks, a held dispatch pool, and **printers whose bed check isn't actually protecting them** - a printer whose bed gate is armed but whose reference has aged out is running on the model's word alone, which is how a print starts on an occupied plate. Those printers also show "bed check unreliable" next to their AI verdict, because a green tick beside an unusable reference is the misleading combination. Printers not opted into auto-start or dispatch are ignored here: their bed gate never runs, so a stale reference costs them nothing.
  - **Today** counts prints finished and failed on the local calendar day, with filament used (and estimated cost when `bambu.cost-per-kg` is set). Calendar day rather than a rolling 24 hours, so at breakfast it reads zero instead of still showing last night's prints.
  - **Printer table** (full width) - a **live camera thumbnail**, state, current job, progress with time remaining, **loaded filament** (one chip per occupied tray, the feeding tray highlighted), queue depth, the AI verdict as a single ✓/⚠, auto-start status, and a **Start next** button on any ready printer with a queue. A printer reporting a **fault** gets a red row and a full-width error line underneath with the code and the printer's own message - a paused printer with a jammed AMS previously looked identical to a paused one. An AI verdict from before the current situation (the printer has since faulted, or it's older than two check intervals) greys out to "stale" rather than showing a green tick next to a broken printer. **Click the thumbnail** to enlarge it (X1C/X1E/H2D don't push the still-frame stream, so on those it opens the live view instead); **click the printer name** to jump to that printer's own page. **Click a row** to expand it: that printer's queue entries (⏫ move-to-front, remove), its per-printer *auto-start* and *auto-queue* checkboxes, and the last AI check in full including the pixel diff. Printers used to be split across two cards, so you read the same machines twice and the AI's reasoning text crowded out everything scannable - this replaces both, and absorbs what used to be the separate Print Queue tab.
  - **Order dispatch pool** - waiting order jobs with "Send to…", retry and remove, the ⏳/⚠ hold banner explaining anything that's stalled, and **when the next printer frees up** (soonest remaining time across the busy printers) - which is the actual question when everything is mid-print.
  - **Orders** and **Recent** - side by side across the full width. **Orders** is purely what's still open; **Recent** carries finished prints and recently queued orders. Orders lead with **what was ordered** (item name, quantity where >1), with the order number and buyer as a sub-line, because an order number tells you nothing at a glance. Only orders the marketplace still lists as **open** appear. An order whose parts have all printed shows **green "ready to ship"** and sorts to the top - that's the one waiting on you rather than on a printer - while one still printing shows amber "X/Y printed", and one with a part that failed and was never reprinted shows red "⚠ N parts failed - re-queue". The count of ready-to-ship orders also appears under the Open orders figure.
- **Mappings** - every listing → gcode assignment in one place. Both marketplaces have a **"Load active listings"** button that pulls the whole shop into a table so products can be mapped **before an order ever arrives** - which is exactly what auto-queue needs to handle a first-time order hands-free. Etsy uses the `listings_r` scope the connect flow always requested; eBay uses the Trading API's `GetMyeBaySelling` (sees ALL listings, not just API-created ones), which in practice works with any connected account token - the legacy Trading API doesn't enforce the granular REST scopes. New connections request the base + `sell.inventory.readonly` scopes anyway for future REST use; in the unlikely event the pull reports a permissions error, Disconnect and reconnect eBay once. eBay rows also include listing keys from saved mappings and open orders. A third table lists every raw saved mapping (including per-variation ones) with edit/delete. The listing-level Map button saves a listing-wide mapping (order lookups fall back to it for any variation), but each row also has a **Variations button** that pulls the listing's individual variations - Etsy variation combinations from the listing's inventory (`listings_r`), eBay variations straight from `GetMyeBaySelling` - so each specific variation (color, size, …) can be mapped to its own gcode, before any order arrives. eBay variations map by their own SKU (the same key an order line item reports, so the match is exact); Etsy variations map by their property=value signature. Listing tables show **product thumbnails** (Etsy listing images, eBay gallery pictures). Loaded listings are **cached** in memory, so they're still shown after a page reload or navigating away and back - the Load button re-fetches on demand. Each listing (and each variation) also has an **On-hand stock** field (default 0): bump it when you print spares or take a return, and incoming orders are filled from stock first - covered units are decremented and an `order_from_stock` notification fires *instead of* printing them, so only the shortfall is auto-queued. **Stock is only decremented once the order is actually accepted.** What stock would cover is worked out first and committed afterwards, because auto-queue declines orders for several reasons - buyer personalization, an unmapped line elsewhere in the same order, a missing library file, no printer with the right filament, or the master switch simply being off - and a decrement made before that decision spent units on something that was then never printed. If an order is covered by stock *entirely* there is nothing to queue, so it's marked as handled and only the `order_from_stock` notification fires. Listings that are never printed (digital items, add-ons) can be **hidden** (eye-slash button, "Show hidden listings" to reveal) - orders containing only hidden, unmapped listings are silently ignored by auto-queue instead of raising a "not mapped" alert. The mapping editor's gcode picker lists loose library files **and** files inside [projects](#project-library) (shown as `Project/file.3mf`), plus an **"Add all files from project…"** control that fills in one part per file in one go - the quick way to map a multi-part product. That's an *expansion*, not a stored link: the mapping is saved as an ordinary list of parts, so auto-queue, dispatch, the dry run and stock behave exactly as they always have and **every existing mapping keeps working untouched**. The trade-off is that adding a file to a project later doesn't update mappings already saved - re-pick the project to refresh one. Every mapped row also has a **Test (flask) button**: a dry run that simulates auto-queueing one unit right now - which printers qualify per part, which tray each would use, and the copy distribution, or exactly why the listing would be skipped (nothing is actually queued).
- **AI Settings** - the full page below, embedded as a tab. The old direct routes (`/print-queue`, `/ai-settings`, `/mappings`) still work as deep links; `/print-queue` keeps the older standalone queue editor for anyone who prefers it.

### Print Queue tab
The **Print Queue** tab (also `/print-queue`) shows every printer's queue in one place instead of opening each card's dialog individually - one section per printer with its queued jobs (remove any entry, or **move it to the front** with the ⏫ button so it prints next; entries queued from an order show the order they belong to), current state, the same AI-gated **Start Next** button as the dashboard card, and two per-printer toggles below: **auto-start** (below) and **"Auto-queue new orders to this printer"** - the latter is on by default and only ever narrows the global Auto-Queue switch, so you can, say, let new orders auto-queue onto the P1S units but keep the H2D for manual jobs.

### AI-gated auto-start (lights-out mode)
Two levels of control: a **farm-wide master switch** (the **Auto-Start** button on the Automation overview toggles it) and a **per-printer opt-in** on the Print Queue tab (*"Auto-start next when bed is clear (AI-checked)"*). The master switch off means nothing auto-starts anywhere - the per-printer selections are remembered and take effect again when you turn it back on. With the master on, a server-side watcher (runs with no browser open) checks every enabled printer every minute; once a printer has been ready - finished, idle, or failed - for the settle delay with jobs queued, it runs the AI bed-clear check and:
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

### Filament spools
The **Spools** page (sidebar) tracks remaining filament on **non-Bambu spools** - which have no AMS RFID, so the printer can't report their weight. Create a spool (name, material, colour, total grams, low-warning threshold), then **assign it to a printer's tray** (only trays the printer reports filament in are listed, plus the external spool). When a print finishes, the grams it used (from the same plate-weight data History uses) are subtracted from the spool on the tray the printer was actually feeding at that moment, and a **Spool Low** notification fires when a spool crosses its threshold. Remaining is shown as a bar (red when low); **Refill** resets it to full, or edit the remaining grams directly. Persisted to `bambu-spools.json`. Best-effort: it needs the printer to have reported an active tray and a known job weight (prints started outside Batch Print / the queue often have no weight, so they won't decrement). Only **completed** prints decrement a spool - the recorded weight is the slicer's estimate for the whole plate, so charging it against a print that failed or was cancelled part-way would badly over-count.

### Backup
The **Backup** button in Maintenance downloads a zip of all state files (maintenance, history, queue) plus the entire project library. Your `.env` is excluded (it contains access codes) - back it up separately.

## AI Print Monitoring

The **AI Settings** tab on the Automation page (also `/ai-settings` directly). Uses a self-hosted [Ollama](https://ollama.com/) instance with a vision-capable model to watch printer camera snapshots and catch problems automatically:
- **Failure detection**: actively-printing printers are checked periodically for spaghetti/detached prints.
- **First-layer quality check**: fires once, **when the printer actually reports its first layer** - the layer number is polled every 10s from print start rather than waiting a fixed delay. A fixed delay is only "the first layer" if the layers happen to be slow: an 8-minute wait landed on layer 89 of a fast part, judging a mid-print surface against a first-layer prompt. `bambu.ollama.first-layer-delay` is now the *timeout* on waiting for a layer number, and `bambu.ollama.first-layer-max-layer` (default 3) is the highest layer still worth judging - past that the check is skipped rather than reporting nonsense. The alert names the layer it looked at.
- **Bed-clear check**: gates the dashboard's "Start Next" queue action - it asks the AI to confirm the bed looks clear before letting the next queued job start (in addition to the "Is the bed clear?" prompt).

**How a verdict is read:** the prompts ask the model to lead with YES/NO/GOOD, but local models frequently ignore that and answer straight into the structured fields. So the parser treats the fields as primary - `Problems:` / `Objects:` (a listed finding, versus "none"), then an explicit `Clear: yes|no`, then the leading keyword, then `Confidence:` - and an answer it cannot read at all is treated as **no answer**, which fails closed at the gates. A positive verdict on the **bed-clear gate** also needs `Confidence: 90` or better, or it's downgraded - a hedged "clear" isn't good enough to start a print on. The two *monitoring* checks are exempt: for failure detection, suppressing an alarm over low confidence is the wrong way to be wrong; for the first-layer check, turning an 85%-confident "looks fine" into an alert would warn on nearly every print, which is how you train yourself to ignore the one that matters.

**Acting on a failure.** A confirmed failure now **pauses the print** (`bambu.ollama.pause-on-failure`, default on). Until this existed the check was advisory only — it logged and notified, and nothing in the app ever called `commandControl`, so a spaghetti failure kept extruding into a nest for as long as it took someone to read a phone.

Pause rather than stop, deliberately: pausing is reversible — the heater stays on, the part stays stuck to the plate, and a false positive costs one Resume click. Stopping would save more filament and free the printer, but a false positive would destroy a good print with no way back, and a check that is sometimes wrong should fail in the direction you can undo.

**Two gates, not one.** A suspected failure has to survive both:

1. **A re-check on a fresh frame** after `bambu.ollama.failure-confirm-delay` (default 12s).
2. **The next scheduled check agreeing** — you're notified on the first confirmation, but the printer isn't touched until a second one five minutes later says the same thing. Any clean check in between resets it.

The second gate exists because the first isn't enough, and the H2D proved it: eight "tangle of loose filament extending from the nozzle" verdicts in 95 minutes on a print that was perfectly fine. That camera looks across the toolhead, so nozzle ooze and the machine's own coiled hoses are permanently in shot — and two frames twelve seconds apart both see them. Two checks five minutes apart don't, because ooze moves and a real nest only grows. It costs one extra cycle on a genuine failure, which is nothing against a nest that sat there for two hours.

**A pause is verified, not assumed.** `commandControl` publishes an MQTT message and returns; it throws nothing if the printer ignores it, and a printer in **cloud mode rather than LAN mode** ignores everything, because this app has read-only access to it. The first version assumed no-exception meant paused and reported "The print has been PAUSED" for a printer that carried on printing. It now waits up to ten seconds for the printer to actually leave the printing state, and if it doesn't, the alert says so and tells you to stop it yourself.

It never acts on a single verdict. A suspected failure is re-checked on a fresh snapshot after `bambu.ollama.failure-confirm-delay` (default 12s) and only acted on if both agree — the same reasoning as the two-pass bed gate, and cheap because it only runs on the rare bad path. A genuine tangle is still there twelve seconds later; a nozzle caught mid-travel across the part is not. If the print has already stopped by then, nothing is done. The notification is sent whether or not the pause is enabled or succeeds: being told is the part that must never depend on anything else working.

**Post-print cooldown.** A printer that has just stopped printing is held for **30 minutes** (editable on the AI Settings page, 0 = off) before it can take a pooled order job. In the moments after a print ends the bed is *certainly* occupied — the part that just finished is sitting on it — so there's nothing for a bed check to usefully decide, and asking a vision model to judge that frame is asking it to be right exactly when being wrong is most expensive. On 2026-08-01 a printer finished at 02:08:45 and a bed check ran 61 seconds later; the pixel diff caught that one, but a confident "clear" on a mid-range reading would have printed onto the part. Auto-start has always had this (its `bambu.auto-start-settle`, default 3m); the dispatch pool didn't, so that route in was the unprotected one. It shows on the overview as the usual bed-backoff countdown. Note this removes the *guaranteed*-occupied window — it isn't a substitute for the bed check, which is still what has to catch a part that's still there afterwards.

A restart no longer triggers this on every printer. `getGCodeState()` reports `OFFLINE` until a printer's first status message arrives, and `OFFLINE → IDLE` looks exactly like a print finishing — so for a while **every restart put a full 30-minute hold on the whole farm**, roughly 90 seconds in, logging "just stopped printing" for machines that had been idle for hours. A genuine finish spanning a restart is still recovered, from the print history's real end timestamp rather than by assuming the print ended when the app came back.

**Two-pass verification (bed-clear gate only).** A "clear" verdict must survive a **second, independently captured snapshot** before a print may start - a fresh light-settle, a fresh frame, a fresh inference. One vision-model verdict isn't stable on a marginal bed: on this farm the same plate, at the same pixel reading, was judged not-clear at 01:12 and clear at 01:16, and the "clear" one started a print onto an occupied plate. Two passes turn one coin flip into two that have to agree, and a second look that returns no answer counts as disagreement (the gate authorises a print, so silence fails closed).

It only runs when the first pass wants to **approve** and the pixel backstop hasn't already blocked, so the common "bed dirty" path costs nothing at all - the extra inference is spent only on the one decision that can waste filament. Toggle it on the AI Settings page; `bambu.ollama.two-pass-bed-check` sets the starting position.

Worth being clear about what it does and doesn't fix: it catches an *unstable* verdict, not a *consistent* blind spot. A part the model reliably fails to see - a large dark ring on a dark plate being the known one - is missed by both passes just as it was by one. That's what the contradiction override and the pixel backstop are for.

**Contradiction override (bed-clear gate only).** A "clear" verdict is also rejected when the model's own text describes something on the plate - phrases like *sitting on the bed*, *left on the plate*, *not empty*, or any round shape (*ring*, *donut*, *circular shape*, *tray*), which on a flat rectangular bed can only be a printed part. This exists because of a real dispatch onto two occupied beds, where every field said clear and the sentence said otherwise:

> `Objects: none, Confidence: 100, Reason: The circular shape is a gridded plate feature, not a 3D printed object.`

The prompt already tells the model that a round shape on the plate *is* a part; it overrode that. So the parser distrusts the verdict rather than the prompt. Negation is honoured, so "there are no rings on the plate" still passes - but only when the negation comes **before** the phrase and **within the same field**, because the failure above explains the object away *afterwards* ("…is a gridded plate feature, **not** a printed object") and a trailing "not" must not excuse it. Blocks are logged at INFO and the reason is appended to the check history entry, so a bed rejected this way reads *"[blocked: says clear but mentions "circular shape"]"* rather than a bare "not clear". Only the bed-clear gate is affected - the monitoring checks start nothing, so making them trigger-happy has no upside.

Every check is **context-aware**: if the printer currently has an active HMS alert (e.g. a nozzle clog) or a legacy print-error code, that's passed to the model as a hint alongside the image, so a check can correlate what it sees with what the printer's own firmware is already reporting. It's framed as a hint, not an instruction - a stale or unrelated alert (e.g. an AMS calibration reminder) won't force a false positive on its own.

Results show as a status chip on each dashboard card (with an animated "checking" dot) and on the AI Settings page, which has:
- a runtime on/off toggle (no restart needed) and a "Check Now" button per printer;
- **the last analyzed snapshot per printer** - the exact camera frame the AI looked at, why the check ran (manual / scheduled / Start Next gate / auto-start gate), what HMS/error hint was fed to the model, and what it concluded (click to enlarge);
The page is split into four sub-tabs - **Status** (last result and snapshot per printer, Ollama connection), **Bed reference**, **History** and **Prompts** - instead of one long scroll.
- **check history** - the last 50 check attempts across the farm with trigger, result, and description; click any row to see that check's snapshot (in-memory, resets on restart);
- **editable prompts** - the exact text sent to the model for each of the three checks, editable at runtime and saved to `bambu-ai-prompts.json` (blank or default-identical text reverts to the built-in default, so future stock-prompt improvements still reach you). Keep the leading YES/NO/GOOD answer-keyword instructions intact - result parsing depends on that first word. The built-in defaults are tuned for **gemma3:12b**. Each prompt has a **Test** button: pick a printer, and it runs the prompt as currently edited (unsaved) against that printer's live camera frame and shows the model's verdict + the analyzed image, so you can tune a prompt without waiting for a real check. There's also a **Test all three now** button that runs all three saved prompts against a single capture. Test runs are **recorded in the check history** (trigger "Prompt test"), so you can review them alongside real checks.
- **HMS / error context hint** - there is no separate "HMS check". Instead, when a printer is actively reporting an HMS alert or print-error code, that code is prepended to the three checks above as a hint (so e.g. a nozzle-clog alert nudges the failure check). That wrapper text is itself an editable prompt at the bottom of the page (keep its `{context}` placeholder, which is replaced with the live code).
- **Empty-bed reference (experimental)** - save a photo of each printer's *empty* bed, and when the toggle is on, the bed-clear check sends the model **two images** (the saved empty reference + the live frame) and asks it to compare, rather than judging one frame in isolation. **⚠ In practice this over-flags**: vision models report normal glue marks, lighting differences and slight plate shifts as "an object that wasn't in the reference" and block genuinely clear beds. The reference image is far more reliable feeding the deterministic [pixel-diff backstop](#pixel-diff-backstop-deterministic-bed-check) below - **the recommended setup is this AI compare OFF and the pixel-diff backstop ON**. Because it has a per-printer ground truth for bed texture, glue marks and lighting, it's much steadier at telling a real object apart from normal bed features. Fully opt-in and only used for printers that have a reference saved; the compare prompt is editable too. Retake the reference whenever you change the plate or move the camera. References live in a `bambu-bed-refs/` folder; the toggle in `bambu-bed-reference.json`.

### Pixel-diff backstop (deterministic bed check)

**Vision models cannot reliably see a dark part on the dark PEI plate.** This is a perception limit, not a prompt problem - it has been reproduced across gemma3:12b, Gemma 4, Qwen2.5-VL and Qwen3-VL, and it is how prints end up dispatched onto an occupied bed. Better prompts move the errors around; they don't fix it.

So the bed-clear check has a second layer that **doesn't involve the model at all**. It measures how structurally different the live frame is from the saved empty-bed reference, as a single number. Above the limit, the bed is treated as NOT clear no matter what the model said.

The measurement is built to ignore everything that *isn't* an object on the plate: greyscale → downscale → crop to the plate → high-pass filter (removes lighting *gradients* - sun patches, day vs night) → normalise to unit contrast (removes lighting *gain* - a dim mid-exposure frame) → **align** → difference. What's left is structure: plate texture and part edges.

**Frames are aligned before comparing.** The plate doesn't park at the same height every time - where it stops depends on the Z height of the print that just finished, because Bambu's end-gcode parks at `max_layer_z + 98mm`. That changes both where the plate sits in frame and how large it appears, so the two frames are fitted to each other first: a search over a small range of shifts *and* zooms (±10% in 1% steps), keeping the best fit, with both readings then taken at that fit. A real object can't be fitted away, because no shift or zoom makes an object match bare plate.

In practice this alignment turned out to matter far less than expected - on real fleet frames the best fit is consistently 0-1%, i.e. essentially identity. It is kept because it costs nothing and fails safe, but **it is not what makes the check work**. The two things that do are the limit and the reference age, below.

Each printer card has a **Measure now** button that runs just the pixel comparison, with no model call - the practical way to calibrate is to measure a known-empty bed, put a part on it, measure again, and set the limit between the two readings.

Each card also shows **"What differs"** - the compared region with the blocks driving the reading washed red. If the red lands on bare plate rather than on an object, the metric is reacting to something that isn't a part (lighting, plate position, or image noise) and the limits won't fix it.

**Capture the reference in the same state the check runs in**: after a print has finished and the bed has been cleared, rather than with the bed parked somewhere it never sits during a real check.

**Retaking a reference discards that printer's last reading.** Every measurement is relative to one specific reference image, so replacing the reference doesn't make the old number stale, it makes it meaningless - it's the answer to a question nobody is asking any more. The printer reads as "no reading yet" until the next check runs, rather than continuing to report a warning derived from the picture you just replaced.

### Upload integrity and the retry limit

Two failures that arrived together on one order, both worth knowing about.

**Uploads are verified.** A completed FTP conversation is not proof of an intact file — a truncated transfer finishes cleanly and the client still reports success. The printer's only reply to a short `.3mf` is error `5004003`, *"There was a problem parsing gcode.3mf"*, raised long after the print command was accepted, so nothing upstream sees a failure. After every upload the file's size on the SD card is now compared to the local file, and a mismatch fails immediately with the two numbers in the message.

**Only one un-verified dispatch per printer.** A printer that has accepted a print command but hasn't started yet still reports READY, so the dispatcher would hand it another job on the very next pass — one printer took three jobs from a single order inside two minutes, none of which it could start. And because the pending-verification map is keyed by *printer*, each new dispatch overwrote the previous one's entry: the first two jobs left both the pool and the queue and were never checked on again. That is the exact silent under-print the verification exists to catch, caused by the verification's own bookkeeping. A printer with an outstanding verification is now skipped until it resolves.

**A job that never starts now parks.** Dispatch already had a three-strikes rule — `MAX_JOB_FAILURES` — and a "parked" state for a job that can't succeed. It never engaged for start failures, because the recovery path re-pooled the job through `enqueue()`, which mints a **new job id**. The failure counter is keyed by that id, so every retry looked like a brand new job with zero failures. One bad file retried every 24 minutes for hours, burning an AI check and a chamber-light cycle each time. The job is now returned with its identity intact and parks on the third attempt, with the reason on the Print Queue page.

### Filament colour filter

A mapped part can require a **colour** as well as a material. Without it the dispatcher takes the lowest-numbered tray holding the right material, which is how an ASA order once went out in grey because slot 4 happened to come first.

You pick a name ("Black", "Grey", …) on the Mappings page, not a hex. The printer reports each tray's colour from the spool's RFID tag, and two spools both sold as black are routinely not the same number — matching on the literal value would mean a mapping that quietly stops working the day you open a new roll, with nothing to show for it but a job that never dispatches. Each tray's reported colour is classified onto the named set instead.

Classification reads **hue first** and falls back to brightness only for greys, whites and blacks. The obvious implementation — nearest palette colour by RGB distance — is wrong in a way that matters: blue is only 11% of perceived brightness, so Bambu's Basic Blue genuinely sits closer to black than to any blue reference, and a blue tray would have satisfied a "black" filter. A test caught that before it compiled.

Two deliberate behaviours:

- **A tray whose colour the printer hasn't reported never matches.** An untagged spool satisfying every filter is the exact situation the filter exists to prevent, so it fails closed — the job waits.
- **A colour name this build doesn't recognise reads as "any"**, not "never". A typo shouldn't silently stop a farm.

Colour only narrows a material match; on its own it would let black PLA satisfy a part that needs ASA. When nothing can take a job, the hold message names both ("no printer has black ASA loaded") — "no ASA" while three trays hold ASA sends you looking in the wrong place.

### Per-model crops

The compared region is set **per printer model**, with a per-printer override. Where the camera sits is a property of the machine: every P1S frames the plate identically, and an H2D frames it nothing like a P1S — its camera looks *across* the toolhead, so more than half the frame is gantry, cable loom and coiled hose. One global crop tuned on a P1 was being applied to all five, which is why the H2D's readings never behaved.

Each printer card now shows **two** previews, because the two checks look at different regions and an invisible mismatch between them is what let a camera full of hoses produce eight false positives:

- **"Pixel diff + bed check see"** — the crop itself.
- **"Failure check sees (+ headroom)"** — the same crop with 20% of frame height added above it.

Tune with the sliders and watch both. The crop is a spatial setting judged by looking, so dragging while watching is the workflow; the number is still shown beside each slider because that's what you write down. Sliders apply on release rather than on every pixel — each change re-renders five cards and re-encodes their previews.

The scope row tells you whether you're editing this scope's own values or **inherited** ones, and offers "Reset to inherited" when an override exists. Editing an inherited value silently creates an override, so it says so before you do.

Pick the scope on the AI Settings page next to the crop fields: **Every H2D**, or **Only H2D** for one machine whose camera has been knocked. Resolution is printer → model → global, so a sixth printer of a known type works the moment it's added. Each printer card's preview now shows *that printer's* region, so a wrong crop is visible rather than inferred.

**The crop is also applied to the images sent to the vision model**, which it wasn't before — the model used to see the whole frame, hoses and all. That's what produced eight "tangle of loose filament extending from the nozzle" verdicts on the H2D in 95 minutes on a print that was fine.

The two checks get different regions, deliberately:

- **Bed-clear** gets the plate and nothing else. The question is "is anything on the plate"; everything above it is noise.
- **Failure detection** keeps 20% of the frame height *above* the crop. A spaghetti nest grows upward out of the print — the one that started all this grew out of the top of the part — so a tight plate crop would hide exactly what that check is looking for. It still excludes the toolhead, which is where the false positives came from.

Turn it on under **Empty-bed reference** on the AI Settings page (needs a reference image saved for that printer). Four controls:
- **Enable the pixel-diff backstop** - off by default; it needs a reference and a calibrated limit.
- **Compared region** - four fractions marking the part of the camera frame that is build plate. **This matters more than the limit.** Each printer card shows a preview of exactly what is being compared; if it includes chamber walls or a blown-out highlight, the plate gets averaged away and an occupied bed can measure *no higher* than an empty one. On a test frame, a region including the chamber wall gave **negative** separation (a part scored lower than an empty bed under a harsh light gradient); tuned to the plate alone the same comparison separated cleanly. Get the preview to be mostly plate before trusting anything else here.
- **One limit: the mean** (default **6.0**). Each check measures the mean difference over the whole region, and that alone decides. Every card also displays a **worst block** reading (region split into a 6x4 grid) and the "What differs" heatmap is shaded against its scale, but **it does not gate anything**. It used to, and that was the source of essentially every false block on this fleet:

  | printer | what's on the bed | mean | worst block |
  |---|---|---|---|
  | P1S | **cupholder on the plate** | 6.91 | **19.91** |
  | P1S-2 | empty | 4.76 | **21.24** |
  | P1P | empty | 5.21 | **23.15** |
  | P1S-3 | empty, *freshly captured* reference | 0.19 | 0.30 |

  The worst block rated the **occupied** bed as the cleanest of the three. A maximum over tiles is dominated by whichever tile happens to contain a high-contrast edge, and the slightest misregistration there swamps the signal from an actual part. No threshold can rescue a reading whose ordering is inverted. The mean, which averages that away, does separate the cases. This matches HA `bed_diff.py`, which has no block channel at all. Note that the script's *docstring* default is 8, but the HA script that actually ran in production called it with **6** (`diff_threshold | default(6)` in `bambu_scripts.yaml`) - and 6.0 is also what falls out of the table above, sitting between the worst empty bed (5.21) and the occupied one (6.91). Copying the docstring's 8 would let the cupholder through at 6.91.
- **Self-refresh the reference** (**on by default**) - replaces the reference whenever the model *and* the pixel check both call the bed clear and the reading is at or below half the limit. **This is the single highest-value setting here.** The last row of the table is the same scene as the others against a reference captured minutes earlier: 0.19 instead of ~5. A stale reference is worth more error than any tuning, and left unrefreshed every reference drifts towards the limit until the check is useless.

  It has poisoned references before - a cupholder measured 4.53 against a limit of 6.0 while empty beds measured 4.07-4.92, the model called it "a gridded plate feature", and the occupied bed was adopted on two printers, which then read clear indefinitely. Two things have changed. That happened while `OllamaService.parseVerdict` only accepted a verdict *leading* with the keyword, which gemma3 never does - so the model half of "both must agree" had **never once fired** and adoption was effectively unguarded. And adoption now requires the reading to be at or below half the limit, i.e. 3.0, below every stale-reference reading observed.

  **Bootstrapping is deliberately manual.** That 3.0 ceiling sits below the 4.76-5.21 a stale reference produces, so self-refresh cannot lift a printer out of a stale reference on its own. Capture one good reference by hand on an empty bed ("Save current frame") and it keeps itself current from then on. Adopting a reference is the one action here that can silently redefine "clear", so it only ever happens from an already-good state. Every adoption is logged at INFO.

**A reading that isn't near zero is not a weak pass - it's no measurement at all.** This backstop only discriminates while its clear-bed readings sit near zero. Measured on this fleet: a *fresh* reference reads **0.19** on an empty bed and **5.94-8.28** with a part. Against a day-old reference the same empty bed reads **4.76-5.64** - and on 2026-08-01 a large speaker adapter measured **5.08**, i.e. *lower than the empty bed*. There is no limit that separates those.

So anything above **half the limit** (3.0 by default, the same figure self-refresh requires before adopting a frame) is treated as "cannot tell" and **fails closed**: either the reference is stale or there's an object the model missed, and both mean don't start a print. Before this rule, mid-range counted as a pass, which left the model as the only thing guarding the bed - and it dispatched onto an occupied one, which is precisely the perception failure this backstop exists to cover.

This is self-correcting but **needs one manual capture to bootstrap**: take a reference on a genuinely empty bed, readings drop to ~0.2, and self-refresh keeps them there. It cannot lift itself out of staleness, because adoption uses this same ceiling. If every printer starts refusing to dispatch, that's this rule telling you the references have aged out - re-capture them.

**It still fails open when there is nothing to measure.** No reference image saved, an unreadable frame, any error, or the backstop switched off - the measurement is skipped and the AI verdict stands alone. A *missing* reference must never stall the farm; a *misleading* one is different, which is the distinction above.

Every bed check records its measured diff in the check history whether or not the backstop is enabled, so you can watch the numbers for a while before turning it on.

**Use this instead of the AI two-image compare, not alongside it.** Both use the same reference image, but only the pixel diff uses it deterministically. Feeding both images to the model produces confident false positives ("there's an object that wasn't in the reference" for what is actually a glue mark or a lighting change), and because the AI gate fails closed, those block dispatch even when the pixel diff correctly measures the bed as clear.

**Fallback server**: the bed gate fails closed, which is right - but it means an Ollama that is down, rebooting, or wedged stops the farm dispatching entirely rather than degrading. Set `bambu.ollama.fallback-url` to a second endpoint (a spare box, or the same machine's other address) and a connection failure on the primary is retried there, turning a total stop into a slower check. The log says which endpoint answered.

Failover is deliberately narrow. **Only connection-level failures fail over** - unreachable host, timeout. An HTTP error status, or a reply the parser can't read, is *not* retried elsewhere: the model answered, and asking a second model until you get a usable answer isn't a safety check, it's shopping for one. For the same reason, run the **same model** on both: the prompts and the 90% confidence floor were tuned against one model's behaviour, and a check that means something different depending on which box replied isn't a check. Requires `bambu.ollama.url` to be set - this is a fallback, not an alternative.

**Lighting**: before every AI check, the printer's chamber light is switched on and the check waits `bambu.ollama.light-settle` (default **10s**) for the camera's auto-exposure to settle, so checks always analyze a well-lit frame. Afterward the light is **restored to its prior state** - if it was off before the check, it's switched back off, so lights-off setups stay dark between checks. Don't shorten the settle without testing: P1-series chamber cameras adapt slowly, and a dim mid-adaptation frame both confuses the model and inflates the pixel diff.

```properties
# Base URL of your Ollama server - AI checks are fully skipped when this is unset
bambu.ollama.url=http://192.168.1.x:11434
# Optional second server, tried only when the first can't be REACHED (down, rebooting, timed out).
# Run the same model on it - see "Fallback server" below.
bambu.ollama.fallback-url=http://192.168.1.y:11434
# Vision-capable model, e.g. gemma3:12b, llava, moondream2
bambu.ollama.model=gemma3:12b
bambu.ollama.failure-check-interval=5m
bambu.ollama.first-layer-delay=8m
bambu.ollama.first-layer-max-layer=3
bambu.ollama.timeout=60s
# Chamber-light settle before each snapshot (P1 cameras adapt slowly - don't shorten without testing)
bambu.ollama.light-settle=10s
# Pixel-diff backstop: crop (fractions of the frame) selecting the build plate. Defaults suit a P1 chamber cam.
bambu.bed-diff.crop-left=0.146
bambu.bed-diff.crop-top=0.407
bambu.bed-diff.crop-right=0.896
bambu.bed-diff.crop-bottom=1.0
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
The **Notification Settings** page (`/notification-settings`, sidebar) shows whether webhook/MQTT are currently configured (with credentials masked), lets you toggle individual event types on/off at runtime without restarting (New Order, Auto-Queue, Auto-Queue Skipped, Auto-Start, Auto-Start Blocked, Auto-Requeue, Order Fully Printed, Fulfilled From Stock, Spool Low, Daily Digest, Plug Auto-Off, AI Failure Detected, AI First Layer Issue, Printer Error, Maintenance Due, Print Finished/Failed/Stopped - saved to `bambu-notification-suppressed.json`, survives restarts), and has a "Send Test" button that fires a test event to all configured channels regardless of the toggles above.

### MQTT (recommended for Home Assistant)
```properties
bambu.notifications.mqtt.url=tcp://192.168.1.10:1883
bambu.notifications.mqtt.username=user
bambu.notifications.mqtt.password=pass
bambu.notifications.mqtt.topic=bambufarm
```
Events publish to `bambufarm/<printer>/<event>` where event is `finish`, `fail`, `stopped`, `error`, `maintenance`, `failure_detected`, `first_layer_issue`, `auto_start`, `auto_start_blocked`, `auto_requeue`, `new_order`, `auto_queue`, `auto_queue_skipped`, `dispatch_blocked`, `poll_failed`, `order_printed`, `order_needs_requeue`, `order_from_stock`, `simulate_mode`, `spool_low`, `digest`, or `tasmota_off` (for the order events the printer segment is the marketplace; for `digest` it is `farm`), with JSON payload:

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

**Printer photos on alerts**: Discord/ntfy deliveries attach the relevant printer's camera frame wherever one makes sense - AI failure and first-layer alerts (the exact frame the AI analyzed), print finished/failed/stopped (the bed as the job ended), printer errors, auto-start confirmations (the bed the AI approved), and auto-start blocked. The generic `json` format and MQTT stay text-only.

**Daily digest** (optional): set a cron expression to get a scheduled farm summary - prints finished/failed in the last 24h with filament used, open orders, queued jobs, and printers with errors:

```properties
# every morning at 07:00 (Quartz cron); unset/off = disabled
bambu.digest-cron=0 0 7 * * ?
```

## Tasmota smart plugs

```properties
bambu.printers.myprinter1.tasmota=http://192.168.1.50
# For multi-outlet Tasmota power strips only - leave unset for single-outlet plugs
bambu.printers.myprinter1.tasmota-channel=2
```
Adds a plug button to that printer's dashboard card with Power On / Power Off (confirmed; an extra warning appears if the printer is printing). Uses Tasmota's `/cm?cmnd=Power%20On|Off` HTTP API (no web password support yet).

The **Tasmota Settings** page (`/tasmota-settings`, sidebar) is a central control panel: one card per printer with a plug configured, showing live status (ON/OFF/Unreachable) plus Power On / Power Off / Refresh buttons, so you don't need to go to each printer's dashboard card individually.

### Idle auto-off
Each plug card on the Tasmota Settings page has an "Auto-off after idle minutes" field: once that printer has sat finished/idle with an **empty print queue** for the configured time, its plug is switched off automatically (one attempt per idle period, `tasmota_off` notification). It never fires while printing, paused, or with jobs queued - so auto-start always wins. Per-printer, persisted to `bambu-tasmota-autooff.json`, 0 disables.

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

**New-order alerts**: when a poll finds an order it has never seen before, a `new_order` notification fires to your configured channels (Discord/ntfy/MQTT), listing each item with its **variation** (e.g. "1x Cupholder Insert (Version: Blue)"). Seen-order IDs are persisted (`bambu-order-tracking.json`), so restarts don't re-alert, and connecting a shop for the first time doesn't fire one alert per existing order. Toggle on the Notification Settings page.

**Auto-queue (zero-click repeat orders)**: an opt-in "Auto-queue new orders" toggle on either Sales Orders page or the Automation overview (one global switch, persisted to `bambu-auto-queue.json`). When a poll finds a NEW order whose line items are all mapped, the jobs are added to a **global dispatch pool** (they are NOT pinned to one printer):
- Each mapped part can specify a required **filament type** (e.g. PETG, ASA) next to its AMS slot in the mapping editor. A printer is **eligible** for a job when it has that material loaded (matched against **live AMS telemetry**): with a slot also set, that exact tray must currently hold that material (catches a swapped spool); with type only, any tray with that material qualifies.
- **Global dispatch pool** (`bambu-dispatch.json`): pooled jobs are handed out by a dispatcher that runs every minute. It gives each job to whichever **eligible** printer is **idle, ready, has an empty local queue, and passes the AI bed-clear check** - and *only* then. If a printer's bed isn't clear it's simply skipped and re-checked shortly, so a job **flows to whichever printer clears first** instead of stalling on one. Dispatch requires the **Auto-Start master switch** on (it starts prints) and is fail-closed on the AI check, exactly like auto-start.
- Printers can be **individually excluded** from the pool on the Print Queue page (each is eligible by default) - useful to keep, say, the H2D out of the automatic rotation while the P1S units run lights-out. Excluded printers still take manual and Batch Print jobs.
- Each job is **claimed out of the pool before its bed check runs**, so two printers being checked at the same time can never both print the same copy; a job that isn't dispatched goes straight back to the front of the pool.
- A job that **can't be queued** (library file deleted, unreadable plate) is retried after 10 minutes and **parked** after 3 failures - shown in red on the Print Queue tab with a retry button - so one broken mapping can't burn an AI check and a chamber-light cycle every minute forever.
- **Start verification**: `startNext` reporting success only means the print command was *accepted*. 90 seconds after a dispatch the printer is checked for actually printing; if it isn't (the usual cause is the file missing from *that* printer's SD card) the job goes **back into the pool**, that printer is held for 20 minutes so the retry lands elsewhere, and a `dispatch_blocked` alert names the printer and file. Without this the job would vanish from both the pool and the queue and the order would silently under-print.
- The `auto_start` notification includes the **bed-check verdict** (the model's reasoning, plus the pixel diff when the backstop is on) next to the camera frame, so your notification history is an audit trail of every bed decision.
- **Live countdowns** on the Automation overview show what the pipeline is waiting on and for how long - next dispatch pass, a printer's bed re-check backoff, "confirming print started", failed-job retry, and the next Etsy/eBay poll - so a waiting farm is never mistaken for a stuck one.
- **Dispatch Now** button on the Automation overview: after clearing beds by hand, click it to re-check every idle printer immediately and send the waiting jobs to whichever beds are clear, instead of waiting for the next automatic pass. It clears the per-printer and per-job backoffs; parked jobs stay parked (retry those individually). If nothing can run it tells you why - master switch off, AI off, or no printer idle.
- **The pool is never silently stuck**: whenever jobs are waiting and a pass dispatches nothing, a `dispatch_blocked` notification fires saying exactly why, and the reason shows on both the Automation overview and the Print Queue tab (camera frame attached where there is one). Two flavours:
  - **⏳ Waiting** (amber) - normal congestion, e.g. *"3 order jobs waiting for a free printer - all 4 eligible printers are busy (3 printing, 1 working through its own queue). They'll dispatch as printers free up."* Notified **once per occurrence**, not repeatedly, since it resolves itself.
  - **⚠ Held** (red) - needs you: no printer has the required filament (the message names it), no printer opted into auto-queue, AI checks off, Ollama unreachable, a bed still dirty, or every job parked. Re-notified every 30 minutes while it persists, because a dirty bed won't clear itself.

  When the bed check can't produce a verdict the alert **names which of the three causes it was** - *"No camera snapshot available"* or *"AI did not answer (Ollama error or timeout)"* - rather than listing all of them. It used to read "(Ollama unreachable, timed out, or no camera snapshot)", which sent people to check a perfectly healthy Ollama while the real fault was a camera relay. The reason was always recorded; the alert just wasn't using it.
- The **Print Queue** tab shows the pool at the top: each waiting job with a **"Send to…"** picker (only printers that currently have the right filament) to place it on a specific printer manually, plus a remove button. Removing a job also **reduces the order's expected job count**, so the order can still reach "ready to ship". Manual **Batch Print** jobs keep their own per-printer queues (unchanged).
- **All-or-nothing per order**: an unmapped line item, a missing library file, a part no printer has filament for, or **buyer personalization on any item** (custom text must never be auto-printed from the generic mapping) skips the whole order with an `auto_queue_skipped` notification saying exactly why - nothing partial, queue it manually instead.
- Queued orders get the "✓ queued" badge and are never auto-queued twice. Adding to the pool fires an `auto_queue` notification; each dispatch fires an `auto_start` notification naming the printer it landed on.

**Simulate mode** (the **Simulate** button on the Automation overview): rehearse the live pipeline without printing. Dispatch, printer eligibility, the filament match, the bed-clear gate with its AI and pixel checks and the notifications all run for real — only the irreversible parts are skipped: no print command, no SD-card upload, no on-hand stock spent, and no start-verification (nothing will start, so it would "fail" every time and dump jobs back into the pool).

**Real marketplace orders are left completely alone while it's on** — not queued, not marked, not counted. Rehearsing a real order would mark it queued and it would then never print for real, which is worse than not rehearsing at all. Queue something by hand to exercise the pipeline. This pairs with the Mappings tab's flask **Test** button, which covers the other half — order parsing, mapping and filament eligibility — so between them the whole path is testable without committing a print.

It **switches itself off after an hour**, because while it's on the farm looks healthy and quietly isn't accepting orders. The button shows the minutes remaining, and a `simulate_mode` notification reports what it would have done and when it expires.

**Order rows link to the marketplace.** On the Automation overview, an order's title is a link straight to that order on Etsy or eBay (new tab) - the usual reason for opening that card is to go and print the label. The URLs are config, `bambu.etsy.order-url` and `bambu.ebay.order-url`, each with an `{id}` placeholder: these are seller-UI page addresses rather than documented API endpoints, so if a marketplace reorganises its seller pages you can correct the link without a rebuild. Set one to blank to turn the link off.

**Order progress & ready-to-ship**: every job queued from an order (auto or manual) is linked back to it. The Automation overview shows in-flight orders as "Etsy #123 — 2/4 printed", and when the LAST part finishes an `order_printed` notification fires: "Etsy order #123 is fully printed - ready to ship". Shipping itself stays manual.

**A print that fails releases its claim on the order.** When a queued print ends failed or stopped and isn't auto-requeued, the order stops expecting that job. Without this, re-queueing the part by hand registers a *second* expected job for the same physical part, so the order over-counts and can never reach "ready to ship" - a single-item order read `0/2` after one stopped print and one re-queue.

An **`order_needs_requeue`** notification fires at the same moment, with the camera frame attached. It's deliberately a separate event from the ordinary `stopped` alert: a stop that costs you an order is a different thing from one you performed on purpose, and it needs an action, because nothing will reprint that part on its own. An eBay order sat abandoned for five hours because the only alert said "print stopped", which doesn't read as something to act on.

The released part is remembered as **abandoned**, and an order with abandoned parts **cannot go green**. It shows red "⚠ N parts failed - re-queue" until you queue it again, at which point the re-queued jobs clear the flag and normal counting resumes. That distinction matters: releasing the count alone would let the *other* parts finishing mark a short order as ready to ship, which is the one wrong answer here. Deliberately removing a job from the dispatch pool is different - that's "don't print this", so it lowers the expected count without flagging anything.

**Polling failure alerts**: a failing order poll is the most dangerous failure this app can have, because its symptom is *silence* - expired credentials or a marketplace outage mean orders simply stop arriving while they keep piling up at the marketplace. After **two consecutive** failed polls (one blip is usually a transient 5xx and self-heals) a `poll_failed` notification fires naming the marketplace and the error, repeating every 6 hours until it's fixed, and once more when polling recovers. Leave this event enabled.

**Auto-requeue** (opt-in, Automation overview toggle): a failed queue-started print goes back to the FRONT of its printer's queue for exactly one retry (auto-start's bed-clear gate still applies before it runs). A second failure of the same job stops and alerts instead of looping filament into the bin. Direct prints (SD card, Print Again) are never auto-requeued.

A repeat order goes from purchase to printing with zero clicks: poll finds it → jobs land in the dispatch pool → the dispatcher sends each to a printer with the right filament that's idle and AI-confirmed bed-clear (see [AI-gated auto-start](#ai-gated-auto-start-lights-out-mode) for the master switch and the bed-clear gate they share).

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

## Overview (wall display)

A separate page at `/overview`, first in the sidebar. Meant to be left up on a monitor and read from across the room - not a denser version of the Automation overview, which is where you go to *work*.

The governing rule is that **the screen is boring when the farm is fine.** When nothing is wrong there's no banner at all, the printer tiles carry no outlines, and the attention panel is one line. When something is wrong, exactly one headline says what, at a size you can read standing up, and the rest becomes a quiet count beside it. Five warnings at equal weight is how you train yourself to read none of them.

There are no interactions - nothing to click, nothing to expand, no saved layout. A display is state anyone can disturb by leaning on the desk, and a dashboard left in the wrong tab is one you stop trusting.

**It takes the whole screen.** The navbar and sidebar are hidden while this page is open - a monitor across the room has no use for a hamburger and a username, and the space they occupy is the difference between a printer tile you can read standing up and one you squint at. Two ways back, because one is never enough on a screen with no visible controls: press **Esc**, or click the app mark in the top-left corner. The pointer hides itself after three seconds of stillness - an idle cursor parked over a printer tile is a distraction at best and, on an OLED, one more unmoving bright pixel - and moving the mouse brings it back. The chrome is restored automatically the moment you navigate away.

Three things exist for the "left on for months" case:

- **Burn-in protection.** The page walks around an eight-point box, one step a minute. A transform, so it rides the compositor and can't reflow anything; a few pixels is enough that no static edge ghosts an OLED or plasma, and small enough that nobody notices.
- **Overnight dim** between 1am and 6am. Dimmed, not blanked - the farm still runs at 3am and a red banner should be visible from the doorway, it just shouldn't light the room.
- **A chime**, off by default. Set `localStorage['bambufarm-wall-sound'] = 'on'` in the browser you leave running. It fires only on the *transition* into a failed state - an alarm that re-sounds every poll is one you mute permanently, which is worse than none - and never on the first paint, so opening the page during an existing failure is silent.

Each camera tile shows the most current thing available, in this order:

1. **The live stream**, for any printer with `stream.url` configured - the H2D, via `/_camerastream/printer5`. Embedded once and never touched again.
2. **The port-6000 JPEG thumbnail**, which only the P-series push. Refreshed in place, so it's current by definition.
3. **The last still anyone captured** - the frame from the most recent AI check, or failing that the saved empty-bed reference on disk, which survives restarts and can be *weeks* old. These carry an **age badge** in the corner, amber past ten minutes and red past a day. It isn't decoration: everything else on the screen is current by construction, so a picture that silently wasn't would be the one element quietly lying to you. The badge ticks client-side from a timestamp, so it needs no round trip and can't drag the change-detection key with it.

The live stream is why the **printer row is updated in place and never rebuilt**. Moving an `<iframe>` in the DOM makes the browser reload it, so under a whole-page rebuild the H2D's WebRTC session would tear down and renegotiate every time any printer's percentage ticked - about once a minute, forever. The page is therefore five persistent slots, each with its own change key, and the printer tiles hold references to their own mutable pieces. That's more code than rebuilding them, and it's the price of a stream that stays up.

The clock is ticked by the browser rather than the server, on purpose: if the server stops pushing, a frozen clock is the clearest possible signal that what you're looking at is no longer true. Everything scales off one viewport-derived unit, so the layout grows with the panel instead of stranding small text in the middle of a 4K screen; below 900px it stops pretending to be a wall display and stacks.

`docs/overview-mockup.html` is the standalone mockup this was built from - open it to compare states side by side without waiting for the farm to break.

## Sidebar

**Reorder the menu** by dragging items in the drawer. The order is saved per device in `localStorage` (`bambufarm-nav-order`), keyed by the view's class name, so it survives a page-title reword and a view added in a later release simply appears at the bottom rather than scrambling what you set. "Reset menu order" at the bottom of the drawer puts it back. Drag-and-drop is mouse-only - touch fires no drag events - so on a phone the menu keeps whatever order you set on a desktop.

**Collapse to a rail** with the menu button. The width and the labels animate rather than snapping; the labels fade slightly ahead of the narrowing so the icons aren't briefly sitting under text. A remembered collapse is applied without animation on load, so a page view doesn't start with the sidebar visibly sliding shut, and the whole effect is dropped under `prefers-reduced-motion`.

### Alert buttons

Set `bambu.notifications.base-url` to this app's externally reachable URL and every alert gains **link buttons** — "Open H2D", "AI checks", "Etsy orders" — so a Discord notification is one tap from the page that can act on it, rather than five.

They are links, and only links, for a reason. A Discord incoming webhook is **one-way**: it can render an action button, but Discord only delivers the resulting interaction to *application-owned* webhooks. A "Pause" button here would look real, do nothing, and teach you not to trust the alert. A link button (style 5) fires no interaction at all — it just opens a URL — which is the one button type a plain webhook can send honestly. Real remote actions would need a bot or a public interactions endpoint.

The webhook URL gets `?with_components=true` appended when a message carries buttons. **Without it Discord accepts the message, returns 2xx, and silently drops the components** — no error, no warning, nothing in any log, just a message with no buttons. That is indistinguishable from not having sent any, and it is the single easiest thing to get wrong here.

ntfy gets the same links via its `Actions` header. Unset the base URL and there are simply no buttons: one pointing at `localhost` fails on the one device you're holding, which is worse than none.

## Timezone

Set `TZ` on the `bambuweb` container to your own zone:

```yaml
    bambuweb:
        environment:
            TZ: America/New_York
```

A container with no `TZ` runs in **UTC**, and every calendar-day decision the app makes is then made in UTC: the overview's **Today** counter rolls over mid-evening and reads 0 while the printers are still warm, History groups jobs under the wrong day, and a daily summary scheduled for 07:00 fires at 07:00 UTC. Nothing in the UI hints at any of this - it just quietly counts a different day than you do. Java reads `TZ` directly, so no `tzdata` package is needed in the image. The zone in use is logged at startup, with a warning when it's UTC.

## Deploying, tests, and keeping it up

`deploy.ps1` (repo root) builds and copies the jar into the deployment folder in one step:

```powershell
.\deploy.ps1              # package -Pproduction, run the tests, copy the jar, restart the container
.\deploy.ps1 -Force       # adds -Dvaadin.force.production.build=true, for theme/CSS changes
.\deploy.ps1 -SkipUnitTests   # escape hatch; the tests take about a second, so you rarely want this
```

**Tests.** There is a small unit suite under `bambu/src/test/java`, run on every build. It is plain JUnit 5 against pure logic - no CDI, no Quarkus boot - so the whole thing finishes in about a second and there is no reason to skip it:

- `OllamaVerdictTest` - how a model's reply becomes a verdict. Every case is a real failure: the leading-keyword parse that recorded a 95%-confidence spaghetti detection as OK, the bare `Confidence: 95` that counted as "bed clear", the `Objects: none,` whose comma made every clear bed read as occupied, and the cupholder that got dispatched onto two occupied beds because the model explained a circular object away as a plate feature.
- `OrderProgressTest` - the `n/m printed` counters and the one-shot ready-to-ship signal, including abandoned parts, cancellations, and re-queues. Wrong in one direction and a package ships short; wrong in the other and a finished order never gets a label.

Both cover code paths that decide whether to spend filament or ship a box, which is why they exist and why the suite is not skipped by default.

**Heartbeat.** `heartbeat.ps1` watches the stack from outside and restarts what has stopped answering. Every alert the app sends is generated *by* the app, so a crashed or wedged container is silent - you find out because prints stopped, hours later.

It runs two checks: container state (catches an exit or a container that never came back after a reboot) and an HTTP probe through nginx (catches the case container state cannot see - "running" while the JVM inside is wedged). The response tells it which service is broken: nothing at all means nginx, a 502/503/504 means nginx is fine and bambuweb isn't answering it.

```powershell
.\heartbeat.ps1 -WhatIf     # probe and log, never restart - run this first

# every 5 minutes, from an elevated prompt:
schtasks /create /tn "BambuFarm heartbeat" /sc minute /mo 5 /rl HIGHEST `
    /tr "powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\path\to\heartbeat.ps1"
```

Restraints, because an over-eager watchdog is worse than none: it acts only after **3 consecutive** failed probes (one miss is usually a GC pause), it **won't restart mid-print** unless given `-IgnorePrinting`, and it stops after **6 restarts in a day** and leaves a log entry instead of churning the queue. A restart is not free - it drops in-memory state and can make a finishing job's outcome unknowable. State lives in `heartbeat-state.json` and the log in `heartbeat.log`, both next to your compose file. Task Scheduler must run it as a user in the `docker-users` group.

### Where the deployment folder is

Neither script hardcodes it any more. `bambufarm-common.ps1` resolves it in this order - first hit wins - and then **validates** that `compose.yml` is actually there:

1. `-ProdPath 'D:\bambu-liveview'`
2. `$env:BAMBUFARM_PROD`
3. `prod-path.txt` next to the scripts (one line, just the path)
4. the historical default

If the folder has no `compose.yml` the script stops and prints all four options. That check is the whole point. A stale path fails in one of two ways, and the second is much worse than the first: either nothing exists there and you get an unhelpful missing-file error, or **an old copy still exists and you deploy a new jar into a folder nothing is running from** - the build succeeds, the container restarts, and your change simply isn't in the app. Failing loudly turns an afternoon into ten seconds.

### Moving to another machine

Copying the folder is most of it, but not all of it. In order:

1. **Copy the deployment folder** (`compose.yml`, `Dockerfile`, `mediamtx.yml`, `reverse-proxy.conf`, the certs, `bambu-web.jar`, and **`data/`**). `data/` is the part people forget - it holds `.env` and every JSON file that is the app's memory: order tracking, queue, bed references, stock, OAuth tokens, history.
2. **Point the scripts at the new location** - easiest is `'D:\bambu-liveview' | Set-Content prod-path.txt` in the repo root.
3. **Edit `data/.env` for anything whose IP changed.** Printer IPs and `bambu.ollama.url` are the usual two. If the new box takes the old box's IP, the printer entries need nothing - but check `bambu.ollama.url` separately, because the Ollama host is a *different* machine and may not have moved with it.
4. **Install Ollama on whatever host serves it, and re-pull the model.** Ollama is not in the compose stack - it's a plain host install the app talks to over HTTP. Copying this folder brings neither the server nor the ~8GB of `gemma3:12b` weights. Until `ollama pull gemma3:12b` finishes on the new host, every AI check **fails closed**: no bed-clear approval, so auto-dispatch stops. That is the safe direction, but it is not an obvious one to diagnose - the symptom is "nothing is printing", not an error.
5. **Set `bambu.ollama.fallback-url` to the old host before you start the migration.** Then the cutover costs nothing: checks keep running against the old server while the new one downloads its weights, and you swap `url` and drop `fallback-url` afterwards. Without it there is a hard window where AI checks are simply unavailable.
6. **Re-create the heartbeat scheduled task** - `schtasks` entries are per-machine and do not travel.
7. **Check VPN subnet routes on both boxes.** For a while you will have two machines on `192.168.0.0/24`, and if either advertises that subnet over Tailscale the other can end up tunnelling to printers sitting beside it. See "When a printer stops responding" below - this failure looks exactly like broken printer firmware.
8. **Shut the old instance down before starting the new one.** Two instances against the same five printers both poll, both dispatch, and both hold the same Discord webhook, while keeping *separate* `data/` directories - so order tracking, stock and history silently diverge and a part can be printed twice. An alert arriving from a host you thought was off is the symptom; nothing in the message says which machine sent it.
7. **Verify before trusting it**: `.\heartbeat.ps1 -WhatIf` (probes and logs, never restarts), then confirm the startup log names the right timezone and the right Ollama endpoint.

### When a printer stops responding

Three failures that look identical from the app and are not, in the order they cost the most time to tell apart.

**A VPN silently stealing the route to your own LAN.** Symptom: `PingSucceeded : True` but `TcpTestSucceeded : False`, and the giveaway is in the same output - `InterfaceAlias` naming your VPN adapter and a `SourceAddress` in its range (Tailscale uses `100.64.0.0/10`). Round-trip time is the other tell: a printer on your own subnet should answer in 1-2 ms, so **23 ms means the packets are not going where you think**.

```powershell
Test-NetConnection 192.168.0.182 -Port 322
Get-NetRoute -DestinationPrefix "192.168.0.0/24" | Format-Table -Auto
tailscale status
```

If a node advertises `192.168.0.0/24` as a subnet route and this machine accepts it, traffic to a printer three feet away is tunnelled to whatever holds that address on the far side - which answers pings and refuses everything else. `tailscale set --accept-routes=false` locally, or stop advertising the route on the node doing it. **This bites hardest when two machines share a subnet**, which is precisely the situation during a migration. A VPN that is merely *starting* is just as dangerous: it installs and withdraws those routes each cycle, so the printer appears and disappears and the fault reads as flaky hardware.

**The camera specifically (port 322).** X1/H2/P2 speak RTSPS there, gated behind **LAN Mode Liveview** on the printer - a separate switch from LAN Only mode, so the printer can stay cloud-connected. Toggling it does not start the service; that happens at boot, so **reboot the printer afterwards**. `Connection refused` means nothing is listening; a timeout means you are not reaching the printer at all, which sends you back to the routing check above.

**Every control works and nothing happens.** Newer firmware can require MQTT commands to be cryptographically **signed**. The printer accepts an unsigned command, replies with nothing, and discards it - so Home, fan, light and pause all report success in the log and the machine never moves. The printer advertises this in its `fun` bitfield, bit `0x20000000`:

| `fun` | `& 0x20000000` | meaning |
|---|---|---|
| `3EC1AFFF9CFF` | `0x20000000` | Developer Mode **off** - commands are ignored |
| `3EC18FFF9CFF` | `0x0` | Developer Mode **on** - commands work |

**Enabling Developer Mode on the printer is what clears the bit**, and a firmware update commonly turns Developer Mode back off - which is how a printer that worked yesterday goes deaf today. Nobody has implemented request signing; there is no software workaround, only the toggle.

This app now reads `fun` and **refuses control commands with a logged warning** rather than sending them into a void (`REFUSED a control command...`). Status requests are never gated - a printer in this state still reports normally, and blocking status would hide the very field that explains the problem.

**An H2D with no microSD card cannot be dispatched to at all.** Its FTPS server serves the **microSD slot, not the 8 GB internal eMMC** - so with no card in it, the SD browser connects, authenticates, and correctly shows an empty root. Confirmed with FileZilla on port 990 independently of this app, which is the check worth doing before suspecting the code: if a third-party client sees the same thing, the printer is telling the truth.

This is not just a browsing inconvenience. **Dispatch uploads a `.3mf` over FTP and then commands a print against that path** (the `queue file already on SD card in a subfolder, printing from there` line in the log is that mechanism working on a P1S). No writable FTP target means auto-start and auto-queue can never work on that printer, whatever else is configured. Put a card in the slot and browsing, upload and dispatch all behave like the P1 machines.

**An AMS slot that reports filament it does not have.** `tray_type` is the material a slot is *configured* for and it survives the spool being pulled - the slot still says PETG with nothing on the holder, and both the dispatcher and the overview believed it. Presence comes from the **per-tray `state` flags** instead:

| flag | meaning |
|---|---|
| `0x01` SPOOL | a spool is physically in the slot |
| `0x02` METADATA | filament metadata is populated |
| `0x04` MOTION | mid-load / mid-unload |
| `0x08` STEADY | settled, not moving or scanning |
| `0x10` RFID | tag read |

A slot counts as loaded only when it is both **present and steady** (`state > 3` → `SPOOL && STEADY`), or exactly `3` under the older encoding where values `0-3` mean something different. Requiring STEADY matters: a tray mid-load reports SPOOL while its metadata is still wrong, and trusting it just moves the bug one step later.

Trays that fail this drop out of the filament map entirely, so the dispatcher's existing "does slot N hold PETG?" test fails closed on its own. `tray_exist_bits` remains only as a fallback for firmware that sends the AMS-level mask but no per-tray state - **it was tried as the primary signal and did not work**. The external spool (`vt_tray`) has no state field and is still matched on configured type alone.

## H2D specifics

The H2 series differs from the P1/X1 machines in more places than "it has two nozzles", and most of the differences fail silently rather than loudly.

| Area | H2D behaviour |
|---|---|
| **Control commands** | Require **signed MQTT** unless Developer Mode is on. Unsigned commands are accepted and discarded - see [When a printer stops responding](#when-a-printer-stops-responding) |
| **`project_file` URL** | `ftp:///<name>`, **not** `file:///sdcard/<name>`. Only X1/X1C/X1E/P1P/P1S/A1/A1MINI use the legacy form, and the wrong one fails the print with no useful diagnostic |
| **File storage over FTP** | The FTPS server serves the **microSD slot**, not the 8 GB internal eMMC. With no card fitted, dispatch cannot work at all |
| **FTPS** | Enforces TLS session reuse - needs `bambu.use-bouncy-castle=true` |
| **Chamber lights** | Two (`chamber_light`, `chamber_light2`); both are driven together |
| **Chamber heater** | Active, airduct-coupled, 65 °C ceiling - see [Dashboard](#dashboard) |
| **Camera** | RTSPS on 322, gated behind **LAN Mode Liveview** plus a reboot |
| **Buzzer** | `buzzer_ctrl`: silent / fire-alarm / beeping |
| **Airduct** | `set_airduct` mode + sub-mode |
| **Prompt sound** | `print_option` `sound_enable` |
| **AMS** | `ams_get_rfid` re-reads a spool tag when a tray reports stale filament metadata |

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
| `bambu.notifications.base-url` | - | External URL of this app; adds link buttons to alerts |
| `bambu.ollama.url` | - | Ollama server URL (unset = AI checks skipped) |
| `bambu.ollama.fallback-url` | - | Second server, tried only when the first is unreachable |
| `bambu.ollama.model` | `gemma3:12b` | Vision model for AI checks |
| `bambu.ollama.failure-check-interval` | `5m` | How often actively-printing printers are checked |
| `bambu.ollama.first-layer-delay` | `8m` | Timeout waiting for the printer to report a layer number |
| `bambu.ollama.first-layer-max-layer` | `3` | Highest layer the first-layer check will still judge |
| `bambu.ollama.timeout` | `60s` | Per-request Ollama timeout |
| `bambu.ollama.light-settle` | `10s` | Chamber-light settle before each check snapshot (P1 cameras are slow) |
| `bambu.bed-diff.crop-left` / `-top` / `-right` / `-bottom` | `0.146` / `0.407` / `0.896` / `1.0` | Plate region used by the pixel-diff backstop, as fractions of the camera frame |
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
| `bambu.digest-cron` | `off` | Quartz cron for the daily farm digest notification (e.g. `0 0 7 * * ?`) |

### Files to back up
`bambu-maintenance.json`, `bambu-history.json`, `bambu-history-inflight.json`, `bambu-queue.json`, `bambu-etsy-tokens.json`, `bambu-etsy-mappings.json`, `bambu-ebay-tokens.json`, `bambu-ebay-mappings.json`, `bambu-order-tracking.json`, `bambu-remember-me.json`, `bambu-notification-suppressed.json`, `bambu-ams-dry.json`, `bambu-ams-dry-sessions.json`, `bambu-auto-start.json`, `bambu-auto-queue.json`, `bambu-ai-prompts.json`, `bambu-tasmota-autooff.json`, `bambu-stock.json`, `bambu-bed-reference.json`, the `bambu-bed-refs/` folder, `bambu-spools.json`, `bambu-dispatch.json`, `bambu-bed-diff.json`, the library folder, and `.env` - or use the Backup button (covers maintenance/history/queue/library, not `.env` or the marketplace token/mapping files).

### Browser localStorage keys (per device)
Card order/sizes/sort/view-mode, camera sizes, SD card columns, notification opt-in, sidebar rail state, remember-me token, and **column order + widths for every table** (`bambufarm-grid-*`). "Reset Layout" on the dashboard/cameras clears the relevant ones, including all remembered column layouts.

**Table columns remember where you put them.** Every grid in the app allows dragging columns to reorder and dragging their edges to resize; both now survive a reload, stored per device. Previously the drag worked and was then silently forgotten on the next page load - Vaadin's reordering is client-side only and nothing was persisting it. A saved layout is discarded automatically if that table gains or loses a column in a later release, so an upgrade resets it rather than restoring a scrambled one.

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
# Externally reachable URL of this app - adds link buttons to alerts. Unset = no buttons.
bambu.notifications.base-url=https://bambu.example.com:8081

# AI print/bed monitoring via Ollama - unset url = fully disabled
bambu.ollama.url=http://192.168.1.x:11434
# Optional second server, tried only on a connection failure - run the same model on it
bambu.ollama.fallback-url=http://192.168.1.y:11434
bambu.ollama.model=gemma3:12b
bambu.ollama.failure-check-interval=5m
bambu.ollama.first-layer-delay=8m
bambu.ollama.first-layer-max-layer=3

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
