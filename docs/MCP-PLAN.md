# BambuFarm MCP server - plan (agreed 2026-11-01; steps 1-3 done 2026-11-02)

Goal: give agents (Claude sessions) structured access to the farm instead of grepping logs over the device
bridge, staging files and running ad-hoc Python against the JSON stores. Tools call the same in-process services
the UI uses, so answers reflect live state.

## Decisions (from Vishal, 2026-11-01)
- **Local only.** No public connector. Claude Desktop on the farm machine runs the MCP server through a small
  local bridge holding the token; cloud sessions reach it through the existing device link.
- **Agents may stop a print, with confirmation.**
- **No clock-offset alert** (the 1 h skew was the Proxmox host clock, now fixed). Report the offset in
  `health_check` output only.
- **Build MCP before the Quarkus/Vaadin upgrade.** The upgrade is a separate job (Quarkus 3.33+/Vaadin 25: frontend
  move, production profile gone, ~60 deprecated StreamResource/Upload call sites). MCP tools will help verify it.

## Dependency / version
- App: Quarkus **3.30.5**, Vaadin 24.9.5, Java 21.
- Use `io.quarkiverse.mcp:quarkus-mcp-server-http` **1.10.4** (built on Quarkus 3.27.2, March 2026; includes
  Streamable HTTP + legacy SSE). Every release since 1.10.5/1.11 targets Quarkus 3.33+.
- Running an extension built on an older Quarkus minor is how the app already works (vaadin-quarkus 2.2.x was
  built on 3.20). If 1.10.4 fails at build or boot on 3.30: fallback = narrow upgrade to Quarkus 3.33 LTS only
  (keep Vaadin 24), then MCP 2.0.x (2.0.4 = current stable, built on 3.33.3.1).
- After the eventual upgrade: move to 2.0.x; tool code (@Tool/@ToolArg methods) carries over largely unchanged.
- Docs: https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html (check the 1.10 branch docs for exact
  config keys: endpoint root path, Streamable HTTP enable flag, content/return types for images).

## Architecture
- New package `com.tfyre.bambu.mcp`: `FarmTools`, `OrderTools`, `AiTools`, `ControlTools`, `McpAuth`,
  `McpAudit`, `Redactor`. Each tool = `@Tool(description=...)` method with `@ToolArg` params, `@Inject`ing
  existing services. Return compact JSON-ish text (records → Jackson) or image content for frames.
- Endpoint: Streamable HTTP at `/mcp` (confirm the extension's default root path; set it explicitly).
- Auth: bearer token. `bambu.mcp.read-token` and `bambu.mcp.control-token` in `data/.env` (generate long random
  values; never log them). A filter (Vert.x route filter or JAX-RS/servlet filter, whichever the extension's HTTP
  layer goes through - check) rejects requests to `/mcp` without a valid token; the control token is required for
  ControlTools (check per tool via an injected request-scoped "caller role").
- **Long polling note:** push was moved off websockets because the Vert.x event loop deadlocked (2026-10-17). MCP
  HTTP runs on Vert.x too - make tool methods blocking-safe (`@Blocking` / run on worker threads) and keep each
  call bounded (timeouts).
- nginx (`reverse-proxy.conf`, prod + repo copy): `location /mcp { proxy_pass $bambuweb_upstream;
  proxy_http_version 1.1; proxy_buffering off; proxy_read_timeout 5m; }` (streaming responses must not buffer).
- Audit: every call appended to `data/mcp-audit.jsonl` (time, role, tool, args with redaction, outcome, ms).
  Control actions also raise a notification event `mcp_action` (add to NotificationSettingsView event list).
- Redactor: central, unit-tested. Strip printer access codes, OAuth tokens, webhook URLs/tokens, anything from
  `.env` whose key contains code|token|secret|password|key; never return `.env` contents. Apply to log search
  output and any free text.

## Tools - Phase 1 (read-only, read token)
| Tool | Args | Returns / source |
|---|---|---|
| `farm_status` | – | per printer: state, job name, %, ETA (min + clock), layer n/N, nozzle/bed/chamber temps, AMS trays (type/colour per slot, AMS HT), active HMS/print_error, bed-cleared flag, own queue size, auto-start opt-in, last AI verdict. `BambuPrinters`, `BambuPrinter`, `BedClearService`, `PrintQueueService`, `AutoStartService`, `PrintAiService.getLastResult` |
| `health_check` | `since` (default boot) | uptime/boot time, ERROR/WARN counts grouped by message class, BlockedThreadChecker count, clock offset vs an HTTP Date header (report only), TLS cert notAfter (read the pem path from config or let it be passed), disk free on data dir, last nightly backup file, last successful Etsy/eBay poll + last error, Ollama reachable, printers offline. Logs: read `data/application.log` (rotated files too) |
| `search_logs` | `pattern` (regex), `level`, `since`, `limit` (≤200) | matching lines, redacted |
| `orders` | `include_closed=false` | open Etsy/eBay orders with board stage (OrderBoardService), lines, mapped parts, skip reason if not queued, stock coverage |
| `dispatch_pool` | – | pooled jobs (part, order), holds/backoffs and reasons, cooldowns, next free printer (DispatchQueueService) |
| `print_history` | `printer?`, `since`, `limit` | finished/failed/cancelled prints with duration, file, order (PrintHistoryService) |
| `ai_history` | `printer?`, `check_type?`, `verdict?`, `since`, `limit` | records from `PrintAiService.getHistory()` incl. description, pixelDiff, frame id |
| `ai_frame` | `frame_id` | the saved JPEG as MCP image content (`PrintAiService.frameOf` / `data/ai-checks/<frame>`); optional `crop=bed` |
| `mappings` | `query` | listing → parts (file, plate, slot, filament, extra slots, H2D variant), market, hidden flag |
| `library_file` | `path` | LibraryFileInfo: sliced-for family, plates, filaments per plate, grams, time |
| `notifications` | `since`, `event?`, `limit` | notification log entries (what NotificationsView shows) |

## Tools - Phase 2 (control token, audited; `destructiveHint`/confirm)
- Low risk (no confirm arg): `bed_cleared(printer)`, `retry_auto_queue(market, order_id)`,
  `return_to_pool(printer, entry)`, `set_light(printer, on)`.
- Medium (`confirm=true` required): `pause`, `resume`, `start_next(printer)`, `set_auto_start(printer|global, on)`,
  `ams_dry(printer, ams_id, filament, temp, hours | stop)`.
- High (`confirm=true` required): `stop_print(printer)` (agreed), `remove_job(printer|pool, id)`.
- Reuse the exact code paths the UI buttons use (DashboardPrinter / OverviewView / OrdersView handlers) - do not
  re-implement printer commands.

## Tools - Phase 3 (AI evaluation)
- `eval_prompt(prompt|prompt_key, frame_ids[], mode=single|compare)` → per-frame YES/NO + parsed verdict via the
  real `OllamaService.parseVerdict`, plus accuracy vs labels. Mirrors the ad-hoc harness in the device VM at
  `~/aieval` (eval.py, cmp.py, prompt_current.txt, cmp_prompt.txt).
- `label_frame(frame_id, label=failure|ok|false_alarm, note)` → `data/ai-labels.json`.
- Shadow-mode comparison check (the "what changed in the last 5 min" prompt on a zoomed print region; 3/3 failing
  pairs flagged, 2/2 normal passed on the 2026-10-30 H2D sample, full-frame version unreliable): runs alongside
  the failure check, records to AI history with trigger `shadow`, never alerts. Needs print localisation
  (e.g. diff vs the job's first frame) - design when we get there.

## Local connection (route A)
- On the farm machine, Claude Desktop `claude_desktop_config.json` (stdio bridge to the HTTP endpoint), e.g.:
  `{"mcpServers":{"bambufarm":{"command":"npx","args":["-y","mcp-remote","https://backup.lockoncomputer.com:8081/mcp","--header","Authorization:${BAMBU_MCP_AUTH}"],"env":{"BAMBU_MCP_AUTH":"Bearer <read or control token>"}}}}`
  (verify mcp-remote's current flags; the hostname must match the wildcard cert - use the public name, which
  resolves on the LAN, not localhost/IP).
- The device bridge proxies MCP servers installed on that machine to cloud sessions.

## Build order
1. Add dependency (1.10.4), `/mcp` endpoint config, token filter, audit log, Redactor + tests, nginx route.
   Implement `health_check` and `farm_status` first. Deploy, smoke-test with curl (initialize + tools/list +
   tools/call) using the token.
2. Remaining Phase 1 tools; redaction tests over real log lines.
3. Wire route A; use it for health checks for a week.
4. Phase 2 actions behind the control token.
5. Phase 3 eval tools, then the shadow comparison check.
Status 2026-11-02: steps 1-3 done - all 11 Phase 1 tools built and smoke-tested, Claude Desktop on vishal-pc2 and the farm PC connected (the farm PC's Desktop passes `bambufarm` through to cloud sessions as mcp__remote-devices__bambufarm__*; run RefreshMcpTools if they are missing). Phase 2 (step 4) built 2026-11-02: 9 control tools in ControlTools + ControlToolFilter; no start_next or ams_dry yet (start_next deliberately left out - bed_cleared + dispatcher covers it with the camera check). Phase 3 partly built 2026-11-02: label_frame + eval_prompt (EvalTools, AiLabels -> data/bambu-ai-labels.json). Shadow comparison check NOT built: first label a set of real failures/false alarms (P1S-3 spaghetti 11-01, the H2D small-part miss 10-30 if its frames still exist), then design print localisation and judge the shadow prompt with eval_prompt before wiring it in. Step 1 notes (classes in `com.tfyre.bambu.mcp`: McpAccessFilter, Redactor, McpAudit, LogScan,
FarmTools). Access level reaches tools via `CurrentVertxRequest` (the extension sets it when it activates the request
context) - `McpAudit.caller()`; phase 2 control tools should check it there, and a `ToolFilter` can hide them from
read-token sessions. Boot logs a harmless CORS warning from the extension (no browser clients, CORS left off).
Estimate: steps 1-2 about a day; touches no existing behaviour (new classes, one dependency, one nginx route).

## Watch-outs
- Do not put tokens in compose.yml or any tracked file; `.env` only.
- The app clock was 1 h slow 10-22 → ~10-31 (Proxmox host); logs/history from that window are 1 h early.
- Git: nothing committed since 6fd7d93 as of 2026-10-30 unless Vishal has since run the commit command
  (commit-msg.txt covers the batch). Check `git log` first.
- Repo editing rules from AGENT-HANDOFF.md apply (CRLF files list, no git writes from the sandbox, push only to
  `fork`).
