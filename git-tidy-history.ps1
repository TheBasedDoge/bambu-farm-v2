<#
    Rewrites the four identically-titled commits into one clean commit, then commits the outstanding work as a
    second, and force-pushes both.

    Why: git-repair-commit.ps1 hardcoded a squash message but was re-runnable, so four separate runs each
    produced a commit claiming to be "Automation overview rebuild, bed-diff backstop, and reliability fixes"
    while actually containing different work. Every one of those subjects also carries an invisible UTF-8 BOM,
    because Set-Content -Encoding UTF8 writes one on Windows PowerShell and git kept it. Both are fixed in
    git-repair-commit.ps1 now; this cleans up what already shipped.

    Safe to run: it is your own fork and you are its only consumer. Uses --force-with-lease, so it refuses to
    push if the remote has moved since this script read it.

    Run from the repo root:
        cd C:\Users\Vishal\Desktop\BambuFarm_Project
        .\git-tidy-history.ps1
#>
[CmdletBinding()]
param([switch]$NoPush)

Set-Location $PSScriptRoot
$ErrorActionPreference = 'Continue'
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Fail($text) { Write-Host "`nFAILED: $text" -ForegroundColor Red; exit 1 }
function Step($text) { Write-Host "`n== $text ==" -ForegroundColor Cyan }
function Ok($text)   { Write-Host "   $text" -ForegroundColor Green }

$RESET_TO = 'e96108f2fbbe3cf69c9369ce4eab6cf3bd7bb891'   # last commit that is also upstream's

Step '1. Preflight'
Remove-Item .git\index.lock, .git\HEAD.lock -Force -ErrorAction SilentlyContinue
$head = (git rev-parse HEAD).Trim()
$remote = (git ls-remote fork main 2>$null | ForEach-Object { ($_ -split "\s+")[0] } | Select-Object -First 1)
Write-Host "   local  HEAD: $head"
Write-Host "   fork   main: $remote"
if (-not $remote) { Fail 'could not read the fork - check your network/credentials.' }
if ($head -ne $remote) {
    Fail "local and fork disagree. This script assumes they match before rewriting. Resolve that first."
}
$toSquash = @(git rev-list "$RESET_TO..HEAD")
Ok "$($toSquash.Count) commit(s) will be squashed into one"

# A tag is the cheap undo: if anything here goes wrong, `git reset --hard pre-tidy` puts it all back.
git tag -f pre-tidy $head | Out-Null
Ok 'tagged current state as pre-tidy (undo: git reset --hard pre-tidy)'

Step '2. Squashing into one commit'
# --soft moves HEAD only: the index keeps the current tree, so committing reproduces it exactly, and the
# uncommitted working-tree changes are left alone for the second commit below.
git reset --soft $RESET_TO
if ($LASTEXITCODE -ne 0) { Fail "git reset exited $LASTEXITCODE" }

$msg1 = @'
Automation overview rebuild, bed-diff backstop, and reliability fixes

Replaces the lost c1f9b6c/fcb234d commits and carries forward everything built since.
The git object database lost the trees for both and they were unrecoverable.

Dispatch:
- Global dispatch pool: order jobs float to the first eligible, ready, bed-clear
  printer instead of being pinned at order time.
- Start verification: a command accepted but never started returns the job to the
  pool, holds that printer 20 minutes, and alerts.
- Removing an order job from a printer queue returns it to the pool; the bin on the
  pool cancels it and decrements the order.
- PollFailureReporter: alerts after two consecutive failed order polls.
- Post-print cooldown: a printer that just stopped printing is held before it can
  take a pooled job, because the bed is certainly still occupied. Auto-start already
  had this; the dispatch pool did not.

Bed-clear gate:
- BedDiffService: deterministic pixel-diff backstop against a saved empty-bed
  reference, with a single mean limit. The worst-block channel is display-only - its
  ordering is inverted on real frames.
- Self-refreshing reference, zoom+shift alignment, runtime-editable crop, Measure now
  and a "what differs" heatmap for calibration.
- parseVerdict reads the structured fields first rather than the leading keyword.
  Unparseable replies fail closed.
- Fix: "Objects: none," read as an object being found - trailing punctuation was
  stripped before the nothing-found lookup but commas were not, so clear beds were
  blocked on every comma-separated reply.
- Contradiction override: a "clear" verdict is rejected when the reply's own text
  describes something on the plate. Negation-aware.
- Two-pass verification: a clear verdict must survive a second, independently
  captured snapshot.

Order tracking:
- In-flight state persisted, so a restart mid-print no longer severs the order link.
- A print that ends without producing its part releases the expected job it was
  covering, and is recorded as abandoned so the other parts finishing cannot mark a
  short order ready to ship.
- Stock is only consumed once an order is actually accepted; auto-queue declines for
  five different reasons and each one used to spend the units anyway.
- Optional product code ties Etsy and eBay listings for one physical product to a
  single on-hand stock pool.

UI:
- Automation overview rebuilt around a printer table.
- AI Settings: bed protection table, safety layers, and per-printer cards that lead
  with the reading.
- Market listing cache persisted, so loaded listings survive a restart.
'@
[System.IO.File]::WriteAllText((Join-Path $env:TEMP 'bf-msg1.txt'), $msg1, (New-Object System.Text.UTF8Encoding $false))
git commit -F (Join-Path $env:TEMP 'bf-msg1.txt')
if ($LASTEXITCODE -ne 0) { Fail "the squash commit failed - `git reset --hard pre-tidy` to undo." }
Ok "squashed to $((git rev-parse --short HEAD).Trim())"

Step '3. Committing the outstanding work'
git add -A -- . ':!docs/AGENT-HANDOFF.md'
if ($LASTEXITCODE -ne 0) { Fail "git add exited $LASTEXITCODE" }
$staged = @(git diff --cached --name-only)
if ($staged.Count -eq 0) {
    Ok 'nothing outstanding - skipping the second commit'
} else {
    $msg2 = @'
UI consistency pass, order re-queue alert, and build tooling

- New order_needs_requeue notification. A print for an order that ends without
  producing the part is flagged on the overview, but the only alert said "print
  stopped", which does not read as something to act on - an eBay order sat abandoned
  for five hours. Separate event so it can be routed on its own.
- AI Settings status tab folded into one table: the results grid and the snapshot
  cards below it listed the same printers twice.
- Section headings are sentence case throughout; page titles stay title case to match
  @PageTitle.
- Tooltips cut to one line each. The four longest in the codebase were paragraphs,
  against this project's own documented rule, and duplicated text now visible on the
  page anyway.
- .pt-chip and .bp-chip merged - two near-identical rules that had drifted apart.
- deploy.ps1: pass Maven arguments as quoted literals (PowerShell was splitting
  -Dvaadin.force.production.build=true), clean the target folders directly with
  retries, read startup from docker logs rather than the app's own log file, and stop
  OneDrive for the duration of a build.
- docs: AI Settings mockup, bed-height experiment protocol, crop analysis images.
'@
    [System.IO.File]::WriteAllText((Join-Path $env:TEMP 'bf-msg2.txt'), $msg2, (New-Object System.Text.UTF8Encoding $false))
    git commit -F (Join-Path $env:TEMP 'bf-msg2.txt')
    if ($LASTEXITCODE -ne 0) { Fail "the second commit failed" }
    Ok "committed $($staged.Count) file(s) as $((git rev-parse --short HEAD).Trim())"
}

Step '4. Result'
git log --oneline "$RESET_TO..HEAD"
Write-Host "`n   subjects should have NO leading BOM:" -ForegroundColor DarkGray
git log --format='%s' "$RESET_TO..HEAD" | ForEach-Object { "     [{0}] {1}" -f $_.Length, $_ }

if ($NoPush) { Write-Host "`n-NoPush given. Run: git push --force-with-lease fork main" -ForegroundColor Yellow; exit 0 }

Step '5. Force-pushing to fork'
# Explicit expected value, NOT bare --force-with-lease. The bare form compares against the remote-tracking ref
# refs/remotes/fork/main, and this repo has only ever fetched from origin - so that ref does not exist and the
# push is rejected with "stale info" before it even contacts the server. Naming the SHA we read in preflight
# gives the same protection without depending on tracking refs.
git fetch fork main 2>&1 | Out-Null   # also creates the tracking ref, so the bare form works in future
git push --force-with-lease=main:$remote fork main
if ($LASTEXITCODE -ne 0) {
    Fail ("push rejected. Nothing local is lost - ``git reset --hard pre-tidy`` restores the old history. " +
          "If it says 'stale info' the fork moved since preflight; re-run. Any other error is likely auth.")
}
Ok 'pushed'
git log --oneline -3
Write-Host "`nWhen you're happy, drop the safety tag: git tag -d pre-tidy" -ForegroundColor DarkGray
