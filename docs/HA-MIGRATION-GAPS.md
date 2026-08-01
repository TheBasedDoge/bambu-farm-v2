# Home Assistant → BambuFarm migration: feature gap analysis

Comparison of the working HA automation set (`etsy_automation_v3.7.yaml`, `bambu_scripts.yaml`,
`bed_diff.py`, `etsy_automation_handoff.md`) against BambuFarm as of the dispatch-pool + AI-check work.
Goal: nothing the HA setup does well gets lost in the migration.

> **Section 0 is the notification-by-notification parity audit** — every alert the HA system can send,
> mapped to ours. Sections 1-4 are the deeper feature analysis.

**Verdict:** BambuFarm's architecture is ahead almost everywhere (real APIs, persistence, restart
safety, parallel dispatch, order linkage). But the HA **bed check** is meaningfully more defensive
than ours, and it earned that the hard way — several of its layers exist because a single-pass vision
check demonstrably failed. Those are the things to port.

---

## 0. Notification parity audit

Every alert the HA system can fire, mapped to a BambuFarm event. **20 of 21 are covered**, several more
richly. Three of the four original gaps were closed on 2026-07-24 (0.2.1-0.2.3); two preference-level
items remain.

### 0.1 Covered

| HA alert | BambuFarm event | Notes |
|---|---|---|
| ✅ Order received + itemisation | `new_order` | Includes the variation, per line item |
| ⚠️ Unknown mapping (some items) | `auto_queue_skipped` | See "behaviour difference" below |
| ❌ All items unknown | `auto_queue_skipped` | Names the reason |
| ⏳ No idle printers with X loaded | `dispatch_blocked` (WAITING) | **Richer** — breaks down *why* each printer is unavailable, and names the missing filament when that's the cause |
| ⚠️ Bed NOT clear, trying next printer | `dispatch_blocked` (bed-dirty) | Camera frame attached, same as HA |
| 🖨️ Print started on `<printer>` | `auto_start` | Frame attached, plus the bed-check reasoning and pixel diff |
| 🚨 Printer never started (SD file missing) | `dispatch_blocked` (start-failed) | Job returns to the pool, printer held 20 min so the retry routes elsewhere |
| 🚨 Order parse / API failure (Discord + mobile) | `poll_failed` | Fires after 2 consecutive failed polls, repeats 6-hourly, plus a recovery message |
| 📥 Print queued for later | `auto_queue` | Jobs stay in the pool rather than a todo list |
| 📋 Order processing complete | `order_printed` | Ours is fulfilment-complete ("ready to ship"), HA's is a dispatch summary. Different but not worse — we also show live "N/M printed" on the overview |
| Print finished + photo | `finish` | Plus `fail` / `stopped`, which HA doesn't split |
| Printer error + photo | `error` | |
| First-layer issue | `first_layer_issue` | Only fires on a problem — see 0.2.4 |
| Ongoing quality check WARN/FAIL | `failure_detected` | |
| Queue processor: nothing passed the bed check | `dispatch_blocked` | |
| Duplicate-order protection (`imap.seen`) | `OrderTrackingService` seen-set | Persisted, survives restarts |

**Events BambuFarm has that HA has no equivalent for:** `maintenance`, `spool_low`, `tasmota_off`,
`digest` (daily summary), `order_from_stock`, `auto_requeue`, `stopped`, `auto_start_blocked`.

### 0.2 Gaps (3 fixed, 2 remaining)

**0.2.1 — Order-poll / API failure was never notified. ✅ FIXED 2026-07-24** — `PollFailureReporter` +
`poll_failed` event: alerts after 2 consecutive failed polls, repeats every 6h, recovery message on
success. Original finding, kept because it explains the severity:

`EtsyOrderPollingService` / `EbayOrderPollingService` catch poll exceptions, store the message in
`lastError`, and log it — but **fire no notification**. If OAuth expires, the token refresh fails, or
the marketplace API errors, the farm simply stops receiving orders and nothing tells you. You'd find
out by happening to open the Sales Orders page.

HA treats this as the most serious failure class it has: a parse of 0 items sends Discord **and** a
mobile push, explicitly because it's order-losing.

**0.2.2 — No dispatch start-verification alert. ✅ FIXED 2026-07-24** — 90s post-dispatch check; on a
non-start the job goes back into the pool, the printer is held 20 minutes so the retry routes to a
different one, and a `dispatch_blocked` alert names the printer and file. HA waits 60s for the printer to actually leave idle
and alerts "🚨 did not start within 1 minute — the file may be missing from its SD card", then tries
the next printer. We never verify, so a command that's accepted but never starts is silent. (Same item
as 1.6 below.)

**0.2.3 — `auto_start` didn't include what the AI actually said. ✅ FIXED 2026-07-24** — it now carries
the bed-check reasoning plus the pixel diff. HA's dispatch message carries
"Bed confidence: 94% <full AI reasoning>" alongside the photo, so the Discord history doubles as an
audit trail of every bed decision. Ours attaches the frame but only says which file went where. Cheap
to add — `PrintAiService.getLastCheck(printer)` already holds the description.

**0.2.4 — First-layer check only notifies on failure.** HA *always* posts the first-layer photo and
assessment, good or bad, so you get a routine "here's how it started" for every print. Ours stays
silent unless there's a problem. Defensible, but it's a deliberate behaviour difference worth a toggle
rather than an accident.

**0.2.5 — No severity routing (minor).** HA sends routine traffic to Discord and escalates
order-losing failures to a mobile push as well. We deliver every event to every configured channel.
Per-event channel selection would be the equivalent; the existing per-event suppression is close but
can't route.

### 0.3 Deliberate behaviour differences (not gaps)

- **Partial-unknown orders.** HA prints the items it recognises and alerts about the rest. We skip the
  whole order (`auto_queue_skipped`) — all-or-nothing. Ours is safer for a mixed order but does mean a
  single unmapped line blocks the known ones. Worth knowing when the alert fires.
- **Per-order fleet-status dump.** HA posts a printer/status/tray table with every order. We have the
  live dashboard and the optional daily `digest` instead.
- **Dispatch summary.** HA can say "dispatched 3/5, 2 queued" because it dispatches synchronously
  inside the order run. Ours is asynchronous — the pool reports continuously instead.

---

## 1. Port these — HA is genuinely safer/better

### 1.1 Deterministic pixel-diff backstop (`bed_diff.py`) — highest value
The single most important idea in the HA setup, and we have no equivalent.

It computes a **numeric structural difference** between the current frame and a reference photo of the
empty bed, and that number can **veto the AI**. Pipeline: greyscale → resize 96×54 → crop to the plate
→ high-pass (subtract a 6×3 blur, kills lighting *gradients*) → normalise to unit RMS contrast (kills
lighting *gain*) → mean absolute difference × 10. Default threshold 8, script called with 6.

The docstring states the motivation directly: it "catches large leftover parts the vision model cannot
reliably see (**dark ring on dark plate**)". That is exactly the failure mode where our fail-closed
gate silently fails *open* — the model says clear, we print onto a part.

**What we have instead:** `BedReferenceService` + `checkBedClearWithReference()` sends reference and
current as two images to the *same vision model*. That's still the model judging, with the same blind
spot. It is not a backstop.

**Port as:** a deterministic pre-gate in `PrintAiService.runCheck` for `bed-clear`. Pure Java (no
Python, no ffmpeg dependency) — decode both JPEGs with `ImageIO`, run the same maths, ~40 lines.
Config: `bambu.bed-diff-threshold` (default 6), and it must **fail open** (see 1.4).

### 1.2 Self-refreshing reference
After a check passes **both** layers, HA adopts the current snapshot as the new reference
(`shell_command.bambu_update_reference`). Handles glue drift, plate rotation and seasonal light
without the baseline going stale.

Safe by construction, as its comment notes: any adopted image already measured *within* the threshold
of the previous reference, while a leftover part jumps the diff far past the limit in one step — so
drift can creep but a part can never be adopted.

**We have:** a manually captured reference that never updates. Port this; it's what makes 1.1
maintainable rather than a thing you re-capture every few weeks.

### 1.3 Two-pass verification on approve
A `Clear: YES` must survive a **second independent snapshot** taken 3s later; final confidence is
`min(pass1, pass2)`. Crucially, pass 2 **only runs when pass 1 wants to approve and pixels don't
already block** — so it costs nothing on the common "bed dirty" path and only spends a second
inference on the one decision that can waste filament.

**We do:** one pass. Cheap insurance against a single hallucination; port it with the same gating.

### 1.4 Structured output + keyword safety override + numeric confidence
The HA prompt demands a fixed shape:

```
Objects: [...]        Clear: [YES/NO]        Confidence: [0-100]       Reason: [...]
```

Then it **distrusts its own verdict**: a `Clear: YES` is forced to confidence 0 if the response text
contains any of ~14 object phrases (`object on the bed`, `sitting on the bed`, `is not empty`, …) or
if the `Objects:` line matches `rings?|donut|trays?`. Approval needs `confidence > 89`.

**We do:** `text.toUpperCase().startsWith(positiveKeyword)` — a boolean, no confidence, no
contradiction check. A model that answers "YES, clear — though there is a ring sitting on the bed"
passes our parser and fails theirs.

**Port as:** extend `AiPromptService`/`OllamaService.AiResult` with a parsed `confidence` int plus a
contradiction scan, and gate on a configurable threshold. This also makes the AI Settings history far
more useful for prompt tuning (you see 92% vs 61%, not just a tick).

> **✅ DONE.** Confidence parsing + a floor of 90 on the bed gate shipped with the parser rewrite;
> `OllamaService.findContradiction` completed it on 2026-07-31. Two deviations from HA, both deliberate:
> the scan runs over the **whole reply**, not just the `Objects:` line, because the excuse arrives in the
> Reason field with `Objects: none` sitting innocently above it; and it is **negation-aware**, looking
> only *behind* the phrase and only within its own field, since the documented failure negates
> *afterwards* ("…is a gridded plate feature, **not** a printed object"). Without that, either every
> reply mentioning "no rings" would block, or the one reply that mattered would not.

Note the deliberate **asymmetry** in HA's failure handling, which we should copy exactly:
- AI unreachable → defaults to `Clear: NO Confidence: 0` → **fails closed** (matches us today).
- Pixel diff unavailable / no reference → prints `-1` → check skipped → **fails open**.

That's correct: a missing reference must not deadlock the farm, but a missing AI answer must never
start a print.

### 1.5 Light settle time
HA waits **10000 ms** with the comment *"The P1 camera is slow to adjust exposure — give it several
frames."* We use `LIGHT_SETTLE_MS = 4500`, hardcoded.

You run P1S units. 4.5s is plausibly too short, and a dim mid-adaptation frame is exactly what
`bed_diff.py` v2.1's contrast normalisation was written to survive ("measured 15+ on v2 despite
identical bed contents"). **Make it configurable, default 10s.** Cheap, and it de-risks 1.1.

### 1.6 Verify the print actually started
HA waits up to 60s for the printer to leave `idle/finish/failed`; if it doesn't, it alerts
("the file may be missing from its SD card") and **moves to the next printer**.

**We don't verify at all.** `DispatchQueueService` consumes the job out of the pool, calls
`startNext`, and its error callback only logs. A command that's accepted but never starts leaves the
job sitting in that printer's local queue — no retry, no alert, and if per-printer auto-start is off
it waits for a human. Worth closing; it's the same class of silent stall the new `dispatch_blocked`
event was added for.

---

## 2. Functional gap — nothing to do with AI

### 2.1 Batch / multi-quantity plate packing (biggest throughput gap)
HA maintains a `batch_files` map from a single part to **pre-sliced multi-up plates**, then packs
greedily largest-first:

```jinja
{% for batch_size in sorted_sizes %}
  {% for _ in range(remaining.count // batch_size) %}   → one job, batch_qty = batch_size
```

An order for 5 becomes a 4-up plate + 1 single: **2 prints, 2 bed clears** instead of 5 of each.

**We have:** `MappingPart.copiesPerUnit`, which just multiplies job count. We have no concept of a
multi-up plate. For a business where every print also costs a bed-clear cycle and an AI check, this
is probably the highest-value non-safety feature in the whole HA setup.

**Port as:** an optional list of `(quantity, file, plate)` alternatives per mapping part; the pool
enqueue step packs greedily before creating jobs. Fits the existing `MappingPart` model without
disturbing dispatch.

### 2.2 End-to-end test mode
`input_boolean.etsy_test_mode` + `script.etsy_test_order` fires a **synthetic order** through the
entire pipeline — parsing, mapping, printer selection, bed check, notifications — while skipping the
actual print command.

**We have:** `AutoQueueService.dryRun()` (the flask Test button), which simulates eligibility only. It
doesn't exercise dispatch, the AI gate, or notifications. A global "simulate" flag that makes
`queuePart`/`startNext` no-op would let you rehearse the whole live pipeline safely — valuable
precisely during this migration.

---

## 3. Where BambuFarm is already ahead — do not port

- **Order source.** HA regex-parses **Etsy sale emails** and maps on literal listing titles with HTML
  entities baked in (`6.5&quot; Door Speaker…`). One listing retitle breaks it silently, and eBay
  isn't covered at all. We use the real Etsy/eBay APIs with per-variation mapping.
- **Filament matching.** HA hardcodes PETG=tray 1 / ASA=tray 2 across every printer. Our
  `resolveSlot()` reads live AMS tray telemetry per printer and matches material to whichever tray
  actually holds it. Keep ours — but note the migration must preserve the *intent*: mark parts
  `PETG`/`ASA` in the mapping editor.
- **Dispatch model.** HA picks a printer inline at order time, waits 5 minutes once, then dumps the
  rest into a `todo.` list of `file|ams|batch_qty` strings needing a manual "Process Print Queue"
  button. Our pool re-checks every eligible printer every minute, indefinitely, and floats jobs to
  whoever clears first.
- **State & restart safety.** HA state lives in counters, `input_text`, and todo-list strings. We have
  JSON stores, order↔job linkage, "N/M printed" progress, and ready-to-ship notification.
- **Concurrency.** The HA automation is `mode: queued` — orders serialise. We dispatch in parallel
  across the farm.

---

## 4. Suggested order of work

**Done (2026-07-24):** pixel-diff backstop (1.1), self-refreshing reference (1.2), configurable
10s settle (1.5), and the dispatch-hold reporting that covers HA's ⏳ "no idle printers" alert.

> **Correction (2026-07-30).** 1.1 was ported and then *embellished*, and the embellishments were the
> bug. A second "worst block" reading (max over a 6×4 grid) was added with its own limit, and the mean
> limit was set to 2.0 instead of the 6 this document specifies. On real fleet frames the worst block
> rated an **occupied** bed (19.91) as cleaner than two **empty** ones (21.24, 23.15) — its ordering is
> inverted, so no threshold fixes it — and it caused essentially every false block. It is now
> display-only, and the mean limit is 6.0, i.e. what §1.1 said all along and what
> `bambu_scripts.yaml` actually passed (`diff_threshold | default(6)`; the script *docstring* says 8,
> which is too loose — it passes a cupholder that measured 6.91).
>
> 1.2's "safe by construction" claim (line 123 above) is **wrong as written**, and it cost two poisoned
> references. It holds only if a part cannot measure within the threshold, and with an uncalibrated
> region one did. It was additionally unguarded because `OllamaService.parseVerdict` only accepted a
> verdict *leading* with the keyword — which gemma3 never does — so the model half of "passes **both**
> layers" had never once fired. Both are fixed; adoption now also requires ≤ half the limit and logs at
> INFO.
>
> Lesson for whoever ports the next item: **this document's numbers came from a system that worked in
> production. Deviate from them only with measurements, not reasoning.**

Also done: poll-failure alerts (0.2.1), start verification (1.6 / 0.2.2), AI verdict in `auto_start`
(0.2.3).

Also done: poll-failure alerts (0.2.1), start verification (1.6 / 0.2.2), AI verdict in `auto_start`
(0.2.3), and **1.4 in full** (2026-07-31 — the confidence half shipped earlier with the parser rewrite;
the contradiction override completes it).

> **Note from finishing 1.4.** Porting it turned up a live defect that had nothing to do with the
> override. The findings value was stripped of trailing `.` `*` `_` `[` `]` before the "nothing found"
> lookup, **but not `,` or `;`** — and gemma3 on this fleet answers
> `Objects: none, Confidence: 100, …`. So `"none,"` did not match `"none"`, the gate read a clear bed as
> *an object was found*, and blocked it. Every comma-separated reply was affected, which is most of them.
> This is the likely source of the repeated `bed is not clear` holds in the logs.
>
> It was only visible because the headline test case — the real cupholder reply — passed *before* the new
> code was wired in. A test that passes for the wrong reason is worth as much as one that fails.
> **Check what is actually making a case pass, not just that it does.**

Remaining, in the order I'd do them:

1. **First-layer always-notify toggle** (0.2.4) — trivial, purely a preference.
2. **Two-pass verify** (1.3) — cheap, but least valuable now the pixel backstop is in.
3. **Severity / channel routing** (0.2.5) — mobile push for order-losing events only.
4. **Batch plate packing** (2.1) — biggest throughput win, largest change; its own batch.
5. **Simulate mode** (2.2) — most useful *before* fully cutting over from HA.

Items 1-2 are inside `PrintAiService` and independent of each other.
