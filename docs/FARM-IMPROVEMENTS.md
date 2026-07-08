# Bambu Farm - Fork Improvements & Configuration Guide

This document covers all changes made to this fork on top of upstream [TFyre/bambu-farm](https://github.com/TFyre/bambu-farm), how to configure the new features, and how to deploy with HTTPS.

---

## 1. Building

```bat
mvnw.cmd clean install -Pproduction
```

The runner jar is produced at `bambu\target\bambu-web-<version>-runner.jar`.

> **Important:** Vaadin caches the compiled frontend bundle in `bambu\src\main\bundles\prod.bundle`. When **theme/CSS or index.html** changes, the cache may be reused and your changes silently won't appear. Force a full frontend rebuild with:
>
> ```bat
> mvnw.cmd clean install -Pproduction "-Dvaadin.force.production.build=true"
> ```
>
> (or delete `bambu\src\main\bundles\prod.bundle` before building). Java-only changes never need this.

---

## 2. UI changes

### OLED dark theme
The existing Dark Theme toggle now renders a true-black OLED look: pure black page background, elevated dark card surfaces with hairline borders, high-contrast text, green progress accents. The light theme is unchanged. Theme files: `bambu/frontend/themes/bambu-theme/oled.css`.

### Layout
- **Sidebar** holds all navigation with icons, plus Dark Theme / Notifications / Logout pinned at the bottom. On desktop, the menu button toggles between the full 200px drawer and a 60px icon-only rail (remembered per browser). On mobile it remains an overlay.
- **Top bar** is minimal: menu, title, and centered page controls. On phones (<820px) the title hides and controls become icon-only on a single row.
- **Favicon** at `/favicon.svg`, replaceable at `bambu/src/main/resources/META-INF/resources/favicon.svg`.

### Dashboard
- **Overview bar** at the top: colored status dots (blue printing / green available / grey offline / red errors with printer names), plus "Next available: P1S 31% • 1h 40m (~21:38)".
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

All layout preferences are stored in **browser localStorage** (per browser/device, not per account).

### Cameras page (`/cameras`)
A camera wall with overlays on the image: printer name (click = printer detail), status, progress bar with %, time remaining + clock ETA, and error messages. Cards resize with grid snapping like the dashboard. Click a card for fullscreen (a great single-printer kiosk view).

### SD card browser
- Multi-select checkboxes + "Delete Selected" (single confirmation for all files).
- Sortable Name column; long names get tooltips.
- "Columns" button to show/hide columns; all columns resizable. Choices persist per browser.
- Toolbar wraps on narrow screens.

---

## 3. Batch print: library & queue

### Project library
Uploading a `.3mf` on the Batch Print page saves it permanently to the library folder on the server. The **Library** dropdown reloads any saved project instantly - no re-upload from your PC. The trash button removes a project.

Combined with **Skip if same size** (on by default, skips the printer SD upload when unchanged) repeat batch prints start in seconds.

```properties
bambu.batch-print.library=bambu-library
```

### Print queue
- In Batch Print, select printers (they may be busy - only filament mapping is required) and click **Queue**. The job stores file, plate, options, and per-printer AMS mapping in `bambu-queue.json`.
- When a printer is idle with queued jobs, its dashboard card shows **"Start Next (N queued): file"**. Clicking asks *"Is the bed clear?"* then uploads from the library (skipped when already on SD) and starts the print. Nothing ever auto-starts.
- The queue icon in the card toolbar opens the queue dialog (view/remove entries).

```properties
bambu.queue-file=bambu-queue.json
```

---

## 4. History, stats, charts & cost

A background service records every print (any source - app, Bambu Studio, SD) by watching state transitions: file, start, duration, result (Finished/Failed/Stopped/Offline). Stored in `bambu-history.json` (capped at 1,000 jobs).

The **History** page shows per-printer stat badges (prints, success %, total time), two charts (prints per day over 14 days as stacked finished/failed bars; 7-day utilization % per printer), and a sortable job grid.

### Cost per job
```properties
bambu.cost-per-kg=18.50
bambu.currency-symbol=$
bambu.history-file=bambu-history.json
```
When `cost-per-kg` > 0, History gains Weight and Cost columns and totals in the stat badges. Weights are captured from the plate data when prints start via Batch Print or the queue (prints started elsewhere show `--`).

---

## 5. Maintenance & print hours

Print hours accumulate per printer while the app runs (`bambu-maintenance.json`). The **Maintenance** view shows a Print Hours column and a wrench dialog per printer with maintenance tasks - defaults: carbon rods 200h, lead screws 300h, nozzle/hotend 100h, belts 500h - each showing hours since last done (red when overdue), Done/Remove buttons, and custom task creation.

Set each printer's real starting total in the dialog (tracking starts at zero). Overdue tasks show a red wrench on the dashboard card.

```properties
bambu.maintenance-file=bambu-maintenance.json
```

### Backup
The **Backup** button in Maintenance downloads a zip of all state files (maintenance, history, queue) plus the entire project library. Your `.env` is excluded (it contains access codes) - back it up separately.

---

## 6. Notifications

### Browser notifications
The **Notifications** checkbox in the sidebar enables desktop notifications on print finish/fail (requires an open tab and HTTPS or localhost).

### MQTT (recommended for Home Assistant)
```properties
bambu.notifications.mqtt.url=tcp://192.168.1.10:1883
bambu.notifications.mqtt.username=user
bambu.notifications.mqtt.password=pass
bambu.notifications.mqtt.topic=bambufarm
```
Events publish to `bambufarm/<printer>/<event>` where event is `finish`, `fail`, `stopped`, `error`, or `maintenance`, with JSON payload:

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

---

## 7. Tasmota smart plugs

```properties
bambu.printers.myprinter1.tasmota=http://192.168.1.50
```
Adds a plug button to that printer's dashboard card with Power On / Power Off (confirmed; an extra warning appears if the printer is printing). Uses Tasmota's `/cm?cmnd=Power%20On|Off` HTTP API (no web password support yet).

---

## 8. PWA (install as app)

The app serves a web manifest, service worker, and icon (`bambu/src/main/resources/META-INF/resources/icons/icon.png`). Over **HTTPS**, browsers offer "Install" / "Add to Home Screen" for a standalone fullscreen app - ideal on phones and tablets.

---

## 9. HTTPS setup

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

---

## 10. Quick reference

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
| `bambu.notifications.mqtt.url` | - | Event broker, e.g. `tcp://ip:1883` |
| `bambu.notifications.mqtt.username/password` | - | Broker credentials |
| `bambu.notifications.mqtt.topic` | `bambufarm` | Topic prefix |
| `bambu.notifications.webhook-url` | - | Webhook target |
| `bambu.notifications.webhook-format` | `json` | `json` / `discord` / `ntfy` |

### Files to back up
`bambu-maintenance.json`, `bambu-history.json`, `bambu-queue.json`, the library folder, and `.env` - or use the Backup button (covers everything except `.env`).

### Browser localStorage keys (per device)
Card order/sizes/sort/view-mode, camera sizes, SD card columns, notification opt-in, sidebar rail state. "Reset Layout" on the dashboard/cameras clears the relevant ones.
