<#
    Commit the working tree and push it to the fork.

    The previous version of this script staged your files but never reached the commit.
    Two likely reasons, both now handled:

      1. $ErrorActionPreference = 'Stop' combined with PowerShell 7.3+'s
         $PSNativeCommandUseErrorActionPreference (which defaults to $true) turns ANY
         non-zero exit from a native command into a terminating error. `git fsck` on this
         repo can exit non-zero over harmless dangling objects, which would abort the
         script silently-looking, several steps before the commit. Now disabled, with an
         explicit $LASTEXITCODE check after every git call instead.

      2. A stale .git\index.lock makes `git commit` fail instantly with
         "Unable to create index.lock: File exists". Cleared up front.

    The commit message is passed via -F (a file) rather than -m, so nothing in it can be
    misread as an argument.
#>
[CmdletBinding()]
param(
    [switch]$NoPush,
    # Message for THIS commit. The long squash message below is only correct for the first run, when HEAD is
    # still at the reset point - it describes the whole backlog. Re-running without this produced four commits
    # all claiming to be "Automation overview rebuild…", each actually containing something different.
    [string]$Message
)

Set-Location $PSScriptRoot
$ErrorActionPreference = 'Continue'
# Do NOT let native exit codes throw - we check them ourselves, where we can be specific.
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Fail($text) { Write-Host "`nFAILED: $text" -ForegroundColor Red; exit 1 }
function Step($msg) { Write-Host "`n== $msg ==" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "   $msg" -ForegroundColor Green }

Step '1. Clearing stale locks and orphaned temp objects'
# The Cowork sandbox can create files under .git but cannot delete them, so agent-run git
# commands leave locks behind that block git on this machine. Windows can remove them.
Remove-Item .git\index.lock, .git\HEAD.lock, .git\ORIG_HEAD.lock -Force -ErrorAction SilentlyContinue
Get-ChildItem .git -Filter '*.lock.*' -File -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
Remove-Item .git\index.old-20260715 -Force -ErrorAction SilentlyContinue
Remove-Item .git\objects\maintenance.lock -Force -ErrorAction SilentlyContinue
Get-ChildItem .git\objects -Recurse -Filter 'tmp_obj*' -ErrorAction SilentlyContinue |
    Remove-Item -Force -ErrorAction SilentlyContinue
Ok 'locks cleared'

Step '2. Current state'
$before = (git rev-parse HEAD).Trim()
Write-Host "   HEAD is $before"
git log --oneline -1

Step '3. Staging'
git add -u
if ($LASTEXITCODE -ne 0) { Fail "git add -u exited $LASTEXITCODE" }

# New files are DISCOVERED, never listed. This used to be a hardcoded array, and it silently rotted: two
# commits titled "Overview wall display" and "colour filter" shipped without OverviewView.java or
# FilamentColor.java, because `git add -u` only stages files git already knows about and neither was on the
# list. Everything still built locally - the files were on disk - so nothing looked wrong. What was actually
# committed was a tree referencing four classes that did not exist in it.
$skip = @(
    'docs/AGENT-HANDOFF.md'   # working notes, not product docs - excluded on purpose
    'commit-msg.txt'          # scratch pad for -Message; never part of the change it describes
)
$untracked = @(git ls-files --others --exclude-standard)
if ($LASTEXITCODE -ne 0) { Fail "git ls-files exited $LASTEXITCODE" }

$newFiles = @($untracked | Where-Object { $_ -notin $skip })
if ($newFiles) {
    Write-Host "   adding $($newFiles.Count) new file(s):" -ForegroundColor DarkGray
    $newFiles | ForEach-Object { Write-Host "     + $_" -ForegroundColor DarkGray }
    git add -- $newFiles
    if ($LASTEXITCODE -ne 0) { Fail "git add of new files exited $LASTEXITCODE" }
}
$untracked | Where-Object { $_ -in $skip } | ForEach-Object {
    Write-Host "   skipping (deliberate): $_" -ForegroundColor DarkGray
}

$staged = @(git diff --cached --name-only)
if (-not $staged) { Fail 'nothing is staged - there is nothing to commit.' }
Ok "$($staged.Count) file(s) staged"

# The invariant that would have caught the above. In a Java project an untracked, non-ignored source file is
# always a mistake - it compiles for you and for nobody else. Cheap to check, and it fails before the commit
# rather than being discovered by a clean clone weeks later.
$orphans = @(git ls-files --others --exclude-standard -- '*.java')
if ($orphans) {
    Fail ("these .java files would be left out of the commit:`n  " + ($orphans -join "`n  ") +
          "`nAdd them, or add them to .gitignore if they are genuinely scratch.")
}

Step '4. Whitespace sanity check'
# These two totals should be close. A large gap means CRLF churn crept back in - stop and
# re-normalise rather than committing a diff that is mostly invisible whitespace.
Write-Host "   raw:       " -NoNewline; git diff --cached --shortstat
Write-Host "   ignore-ws: " -NoNewline; git diff --cached -b --shortstat

Step '5. Committing'
$msgFile = Join-Path $env:TEMP "bambufarm-commit-$PID.txt"
# The long message below only describes the first commit off the reset point. Anything after that needs its own.
$atResetPoint = (git rev-parse HEAD).Trim().StartsWith('e96108f')
if (-not $Message -and -not $atResetPoint) {
    Fail ("HEAD has moved on from the reset point, so the built-in squash message would be wrong - it is what " +
          "produced four commits all titled the same thing. Re-run with -Message ""what this change is"".")
}
$msg = @'
Automation overview rebuild, bed-diff backstop, and reliability fixes

Squashed replacement for the lost c1f9b6c/fcb234d commits plus all work since. The
git object database lost the trees for both and they were unrecoverable; this commit
carries their content forward unchanged along with everything built after.

Dispatch:
- Global dispatch pool: order jobs float to the first eligible, ready, bed-clear
  printer instead of being pinned to one at order time (DispatchQueueService).
- Start verification: a print command accepted but never started returns the job to
  the pool, holds that printer 20 minutes so the retry routes elsewhere, and alerts.
- Removing an order job from a printer queue returns it to the pool; the bin on the
  pool itself cancels it and decrements the order. QueueEntry carries MappingPart so
  a returned job keeps its filament requirement.
- PollFailureReporter: alerts after two consecutive failed order polls, repeats
  6-hourly, sends a recovery message.

Bed-clear gate:
- BedDiffService: deterministic pixel-diff backstop against a saved empty-bed
  reference. Single mean limit of 6.0, matching the HA script that ran in
  production. The worst-block reading is display-only: on real fleet frames its
  ordering is inverted, rating an occupied bed cleaner than two empty ones, which
  was the source of essentially every false block.
- Self-refreshing reference, on by default, requiring both model agreement and a
  reading at or below half the limit. Bootstrapping stays manual by design.
- Zoom+shift alignment search, runtime-editable crop, "Measure now", and a
  "What differs" heatmap for calibration.
- parseVerdict reads the structured fields first rather than the leading keyword,
  which gemma3 never emits. Unparseable replies fail closed at the gate instead of
  falling back to the confidence figure, which measures how sure the model is of its
  answer, not the answer itself.
- Fix: "Objects: none," read as an object being found. Trailing punctuation was
  stripped before the nothing-found lookup but commas and semicolons were not, and
  gemma3 answers "Objects: none, Confidence: 100, ..." on this fleet. Clear beds were
  blocked on every comma-separated reply.
- Contradiction override (HA gap 1.4): a "clear" verdict is rejected when the reply's
  own text describes something on the plate, including any round shape.
  Negation-aware, looking only behind the phrase and within its own field, so "there
  are no rings" still passes while "the circular shape is a plate feature, not a
  printed object" does not. parseVerdict returns a Verdict record carrying why a
  verdict was overridden, surfaced in the check history.
- First-layer check fires on the reported layer number rather than a fixed delay.

Order tracking:
- In-flight running/pending state persisted to bambu-history-inflight.json. A restart
  mid-print used to sever the order link, leaving orders stuck at 0/N forever.
- Fix: a print that ends failed or stopped and is not auto-requeued releases the
  expected job it was covering. Without this, re-queueing the part by hand registered
  a second expected job for the same physical part, so a single-item order read 0/2
  and could never reach ready-to-ship.
- Released parts are recorded as abandoned and block completion until the order is
  queued again, so the other parts finishing cannot mark a short order ready to ship.
  The overview shows them red rather than green, and no longer hides an order whose
  only part was abandoned.
- Orders in progress filtered to genuinely open orders; titles persisted so a closed
  order still renders as an item name. pruneClosed on successful polls only.

UI:
- Automation overview rebuilt: KPI strip, full-width printer table with live camera
  thumbnails, expandable rows, fault banners, filament chips, next-printer-free.
- Progress and remaining minutes added to the change-detection key; the overview's
  live data was frozen for the duration of a print.
- AI Settings split into Status / Bed reference / History / Prompts sub-tabs.
- Library projects: a project is a library subfolder, multi-file upload, per-file
  management, and listings mappable to a whole project.
- Sticky telemetry on BambuPrinterImpl (percent, remaining minutes, subtask name,
  layer number) so polled views survive Bambu's partial delta pushes.

Tooling:
- deploy.ps1: production build, jar copy and container restart, with the stale
  frontend bundle and the phantom bind-mount directory both handled.
- docs/verdict-parser-cases.py: case table for the verdict parser, which has now been
  the source of three separate production bugs.
'@
if ($Message) { $msg = $Message }
# WriteAllText with UTF8Encoding($false), NOT Set-Content -Encoding UTF8: on Windows PowerShell the latter
# writes a byte-order mark, and git keeps it - every commit made by this script so far has an invisible
# EF BB BF at the start of its subject line.
[System.IO.File]::WriteAllText($msgFile, $msg, (New-Object System.Text.UTF8Encoding $false))

git commit -F $msgFile
$commitExit = $LASTEXITCODE
Remove-Item $msgFile -Force -ErrorAction SilentlyContinue
if ($commitExit -ne 0) { Fail "git commit exited $commitExit - see the message above. Nothing was pushed." }

$after = (git rev-parse HEAD).Trim()
if ($after -eq $before) { Fail 'HEAD did not move - the commit did not happen.' }
Ok "committed $after"
git log --oneline -1

if ($NoPush) { Write-Host "`n-NoPush given. Run: git push fork main" -ForegroundColor Yellow; exit 0 }

Step '6. Pushing to fork'
git push fork main
if ($LASTEXITCODE -ne 0) { Fail "git push exited $LASTEXITCODE - the commit is safe locally, just not pushed." }

Step 'Result'
git log --oneline -3
Write-Host "`nLocal and fork should now match:" -ForegroundColor DarkGray
git rev-parse HEAD
git ls-remote fork main
