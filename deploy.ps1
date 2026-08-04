<#
.SYNOPSIS
    Build BambuFarm and deploy the jar to the production folder.

.DESCRIPTION
    Runs the Maven production build, copies bambu/target/bambu-web.jar into the prod
    bambu-liveview folder, and restarts the bambuweb container.

    Handles two things that have bitten this project before:

    1. Stale frontend bundle. Vaadin caches the compiled bundle and will silently reuse
       old CSS after a theme change. The script checks whether anything under
       bambu/frontend is newer than the last jar and switches to a forced production
       build automatically. Use -Force to demand one regardless.

    2. The phantom-directory footgun. compose.yml bind-mounts ./bambu-web.jar. If that
       file is missing when the container starts, Docker silently creates an empty
       DIRECTORY at that path instead of erroring, and every subsequent start recreates
       it. Symptom is "Invalid or corrupt jarfile". The script stops the container before
       copying and removes any such directory it finds.

.PARAMETER Force
    Force a full clean frontend rebuild even if no frontend file looks changed.

.PARAMETER SkipBuild
    Deploy the jar already sitting in bambu/target without rebuilding.

.PARAMETER NoRestart
    Copy the jar but leave the container alone.

.PARAMETER SkipUnitTests
    Skip the JUnit suite. It runs in about a second and covers the verdict parser and the order counters -
    the two places where being wrong costs filament or ships a short package - so there is rarely a good
    reason to pass this.

.PARAMETER ProdPath
    Override the production folder.

.EXAMPLE
    .\deploy.ps1
    Java-only change: builds, copies, restarts.

.EXAMPLE
    .\deploy.ps1 -Force
    After a CSS or theme change, when you want the forced frontend rebuild.
#>
[CmdletBinding()]
param(
    [switch]$Force,
    [switch]$SkipBuild,
    [switch]$NoRestart,
    [switch]$NoPauseSync,
    # Tests run by default now that there are some. A deploy that silently skips its own suite reads as
    # passing, which is worse than having none.
    [switch]$SkipUnitTests,
    [string]$ProdPath = 'C:\Users\Vishal\Downloads\bambu-farm-web\bambu-liveview'
)

$ErrorActionPreference = 'Stop'
# PowerShell 7.3+ turns any non-zero native exit code into a terminating error when the
# preference above is 'Stop'. We check $LASTEXITCODE explicitly instead, where we can say
# which command failed and why.
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}
Set-Location $PSScriptRoot

$jarSource = Join-Path $PSScriptRoot 'bambu\target\bambu-web.jar'
$jarTarget = Join-Path $ProdPath 'bambu-web.jar'
$started   = Get-Date

function Step($msg) { Write-Host "`n== $msg ==" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "   $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "   $msg" -ForegroundColor Yellow }

<#
    Deletes the module target folders, retrying through transient locks.

    maven-clean-plugin aborts the whole build the moment one file or folder won't delete, and this repo lives in
    a OneDrive-synced directory where target/ is ~190MB of constantly-rewritten build output. OneDrive holds
    handles on files while it syncs them, so a clean can fail on a directory that is merely being uploaded -
    observed on an EMPTY folder created seconds earlier by the build itself. Retrying a few times clears it,
    where Maven simply gives up.
#>
<#
    OneDrive and a Java build fight over the same thousands of small files, and OneDrive wins - it holds
    handles on files while it uploads them. Observed three separate ways in one day:
      - maven-clean-plugin could not delete an EMPTY folder the build had just created;
      - vaadin-maven-plugin could not read BambuPrinter.class, which Maven had written seconds earlier;
      - and, before any of that, the .git object database lost objects during a move.
    There is no supported way to pause OneDrive from a script, so this stops the process outright and starts
    it again afterwards. That is safe: OneDrive picks up where it left off and syncs the finished output.
#>
function Suspend-OneDrive {
    $proc = Get-Process OneDrive -ErrorAction SilentlyContinue
    if (-not $proc) { return $null }
    $exe = $proc[0].Path
    Warn 'pausing OneDrive for the build (it holds file handles that break Maven)'
    Stop-Process -Name OneDrive -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    return $exe
}

function Resume-OneDrive($exe) {
    if (-not $exe) { return }
    if (Get-Process OneDrive -ErrorAction SilentlyContinue) { return }
    Start-Process $exe -ErrorAction SilentlyContinue
    Ok 'OneDrive restarted'
}

function Clear-Target {
    $targets = @('bambu\target', 'common\target', 'server\target', 'target') |
               ForEach-Object { Join-Path $PSScriptRoot $_ } |
               Where-Object { Test-Path $_ }
    foreach ($t in $targets) {
        for ($try = 1; $try -le 5; $try++) {
            try {
                Remove-Item $t -Recurse -Force -ErrorAction Stop
                break
            } catch {
                if ($try -eq 5) {
                    throw ("Could not delete $t after 5 attempts - something is holding a handle on it. " +
                           "This folder is inside OneDrive; pause syncing (taskbar icon -> Pause) and re-run. " +
                           "Last error: $($_.Exception.Message)")
                }
                Warn "locked, retrying in 2s ($try/5): $(Split-Path $t -Leaf)"
                Start-Sleep -Seconds 2
            }
        }
    }
    Ok "target folders cleared"
}

# --- sanity ------------------------------------------------------------------
if (-not (Test-Path $ProdPath)) { throw "Production folder not found: $ProdPath" }
if (-not (Test-Path (Join-Path $ProdPath 'compose.yml'))) { throw "No compose.yml in $ProdPath - is that the right folder?" }

# --- 1. decide which build we need -------------------------------------------
Step 'Build'
if ($SkipBuild) {
    if (-not (Test-Path $jarSource)) { throw "-SkipBuild given but no jar at $jarSource" }
    Warn 'skipping build, deploying the existing jar'
} else {
    $forced = $Force.IsPresent
    if (-not $forced -and (Test-Path $jarSource)) {
        # Anything under bambu/frontend newer than the last jar means the theme/bundle
        # changed since it was built, so the cached bundle would be stale.
        $jarTime = (Get-Item $jarSource).LastWriteTime
        $newer = Get-ChildItem (Join-Path $PSScriptRoot 'bambu\frontend') -Recurse -File -ErrorAction SilentlyContinue |
                 Where-Object { $_.LastWriteTime -gt $jarTime -and $_.FullName -notmatch '\\generated\\' } |
                 Select-Object -First 3
        if ($newer) {
            $forced = $true
            Warn 'frontend changes detected since the last build - forcing a full frontend rebuild:'
            $newer | ForEach-Object { Warn ("   " + $_.FullName.Replace($PSScriptRoot, '.')) }
        }
    } elseif (-not $forced) {
        Warn 'no previous jar to compare against - forcing a full build'
        $forced = $true
    }

    # Prefer the wrapper if Maven isn't on PATH.
    $mvn = if (Get-Command mvn -ErrorAction SilentlyContinue) { 'mvn' }
           elseif (Test-Path (Join-Path $PSScriptRoot 'mvnw.cmd')) { Join-Path $PSScriptRoot 'mvnw.cmd' }
           else { throw 'Neither mvn nor mvnw.cmd found.' }

    # Arguments as an array of single-quoted literals, splatted. Passed bare, PowerShell split
    # -Dvaadin.force.production.build=true and handed Maven ".force.production.build=true" as its own token,
    # which it then read as a lifecycle phase ("Unknown lifecycle phase"). Quoting each element stops
    # PowerShell interpreting anything inside it - the dots are what it was reacting to.
    # Stop OneDrive for the duration. The repo lives inside a synced folder, and OneDrive's file handles have
    # broken this build in three different places. Restarted in the finally below, whatever happens.
    $oneDriveExe = $null
    if (-not $NoPauseSync -and $PSScriptRoot -match 'OneDrive') {
        $oneDriveExe = Suspend-OneDrive
    }
    try {
        # We do the clean ourselves (with retries) and then run WITHOUT maven's `clean` phase, so a file lock
        # can't abort the build before a single class is compiled.
        if ($forced) {
            Clear-Target
        }
        $mvnArgs = if ($forced) {
            @('install', '-Pproduction', '-Dvaadin.force.production.build=true')
        } else {
            @('package', '-Pproduction')
        }
        if ($SkipUnitTests) { $mvnArgs += '-DskipTests' }
        Write-Host "   $mvn $($mvnArgs -join ' ')"
        & $mvn @mvnArgs
        $mvnExit = $LASTEXITCODE
    } finally {
        Resume-OneDrive $oneDriveExe
    }
    # $ErrorActionPreference does NOT catch a non-zero exit from a native command.
    if ($mvnExit -ne 0) {
        if ($oneDriveExe -or $NoPauseSync) {
            Warn 'If this mentions "used by another process", something still holds a handle on target\ -'
            Warn 'antivirus and Windows Search index it too. Building outside OneDrive is the durable fix.'
        }
        if (-not $SkipUnitTests) {
            Warn 'If this is a test failure, fix the test rather than passing -SkipUnitTests: the suite covers'
            Warn 'the bed-clear verdict and the order counters, and a red one there means a real wrong answer.'
        }
        throw "Maven failed with exit code $mvnExit - nothing was copied."
    }

    if (-not (Test-Path $jarSource)) { throw "Build reported success but $jarSource is missing." }
    if ((Get-Item $jarSource).LastWriteTime -lt $started) {
        throw "$jarSource wasn't rewritten by this build - refusing to deploy a stale jar."
    }
    Ok ("built {0:N0} MB" -f ((Get-Item $jarSource).Length / 1MB))
}

# --- 2. stop the container ----------------------------------------------------
Push-Location $ProdPath
try {
    if (-not $NoRestart) {
        Step 'Stopping bambuweb'
        docker compose stop bambuweb
        if ($LASTEXITCODE -ne 0) { Warn "docker compose stop returned $LASTEXITCODE - continuing" }
    }

    # --- 3. clear the phantom directory --------------------------------------
    if (Test-Path $jarTarget -PathType Container) {
        Step 'Removing phantom directory'
        Warn "$jarTarget is a DIRECTORY, not a file - Docker created it because the jar was missing."
        Remove-Item $jarTarget -Recurse -Force
        Ok 'removed'
    }

    # --- 4. copy, keeping the previous jar for rollback -----------------------
    Step 'Deploying'
    if (Test-Path $jarTarget -PathType Leaf) {
        Copy-Item $jarTarget "$jarTarget.prev" -Force
        Ok 'previous jar kept as bambu-web.jar.prev'
    }
    Copy-Item $jarSource $jarTarget -Force

    $srcLen = (Get-Item $jarSource).Length
    $dstLen = (Get-Item $jarTarget).Length
    if ($srcLen -ne $dstLen) { throw "Copy verification FAILED: source $srcLen bytes, target $dstLen bytes." }
    Ok ("copied and verified {0:N0} MB -> {1}" -f ($dstLen / 1MB), $jarTarget)

    # --- 5. restart -----------------------------------------------------------
    if ($NoRestart) {
        Warn 'container left stopped (-NoRestart)'
        return
    }
    # Timestamp the restart so we only read log lines produced by THIS boot. Docker's own
    # log is used rather than data\application.log: that file is written from inside the
    # container across a bind mount, the app rotates it on startup, and reading it from
    # the host while it's held open gave a false "never came up" on a container that was
    # in fact already serving.
    $since = (Get-Date).ToUniversalTime().AddSeconds(-5).ToString("yyyy-MM-ddTHH:mm:ssZ")

    Step 'Starting bambuweb'
    docker compose up -d bambuweb
    if ($LASTEXITCODE -ne 0) { throw "docker compose up failed with exit code $LASTEXITCODE" }

    Step 'Waiting for startup'
    $deadline = (Get-Date).AddSeconds(120)
    $startLine = $null
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        # Run the capture in a child scope with a relaxed preference. `docker compose` writes its
        # own warnings (the obsolete `version:` attribute, for one) to stderr, and merging stderr
        # with 2>&1 turns each line into an ErrorRecord - which under 'Stop' is terminating, and
        # killed this step even though the container was already serving. stderr is still merged
        # deliberately: a JVM "Invalid or corrupt jarfile" arrives that way and must not be missed.
        $out = & {
            $ErrorActionPreference = 'Continue'
            docker compose logs --no-color --no-log-prefix --since $since bambuweb 2>&1
        } | ForEach-Object { $_.ToString() }
        if ($out -match 'Invalid or corrupt jarfile|Unable to access jarfile') {
            throw 'Container reports a corrupt or missing jarfile - check that bambu-web.jar is a FILE, not a directory, in the prod folder.'
        }
        $startLine = $out | Select-String 'bambu-web .* started in' | Select-Object -Last 1
        if ($startLine) { break }
    }
    if ($startLine) {
        Ok ($startLine.ToString() -replace '^.*\(main\)\s*', '')
    } else {
        # Not necessarily a failure - fall back to asking Docker whether it's running.
        $state = (docker compose ps --format '{{.State}}' bambuweb 2>$null | Select-Object -First 1)
        if ($state -match 'running') {
            Warn "no startup line seen in 120s, but the container reports '$state'."
            Warn 'It has most likely started - confirm with: docker compose logs -f bambuweb'
        } else {
            Warn "container state is '$state' - check: docker compose logs -f bambuweb"
        }
    }
} finally {
    Pop-Location
}

Write-Host "`nDone in $([int]((Get-Date) - $started).TotalSeconds)s." -ForegroundColor Green
Write-Host "Rollback if needed:" -ForegroundColor DarkGray
Write-Host "  cd `"$ProdPath`"; docker compose stop bambuweb; Copy-Item bambu-web.jar.prev bambu-web.jar -Force; docker compose up -d bambuweb" -ForegroundColor DarkGray
