<#
.SYNOPSIS
    Build BambuFarm and stage the jar in dist\ - no prod folder touched, no container restarted.
.DESCRIPTION
    For the setup where the repo lives on one machine and the farm runs on another: this builds exactly the
    way deploy.ps1 does (same Maven profile, same forced-frontend-rebuild detection, same unit tests), then
    copies the jar to dist\bambu-web.jar next to a dist\BUILD.txt that records the commit, the time and the
    sha256. Copy dist\bambu-web.jar to the prod box, then there:

        docker compose stop bambuweb; docker compose up -d bambuweb

    (stop before copying over the mounted jar - see deploy.ps1 for the phantom-directory footgun.)
    BUILD.txt is the answer to "which build is the prod box running" - keep it next to the jar there too.
.PARAMETER Force
    Force a full clean frontend rebuild (needed after any CSS/theme/frontend change; also detected automatically
    when a file under bambu\frontend is newer than the last jar).
.PARAMETER SkipUnitTests
    Skip the JUnit suite. It runs in about a second; there is rarely a good reason.
.PARAMETER Out
    Where to put the jar. Default: dist\ in the repo root (gitignored).
.EXAMPLE
    .\build.ps1
.EXAMPLE
    .\build.ps1 -Force
#>
[CmdletBinding()]
param(
    [switch]$Force,
    [switch]$SkipUnitTests,
    [string]$Out
)

$ErrorActionPreference = 'Stop'
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}
Set-Location $PSScriptRoot

if (-not $Out) { $Out = Join-Path $PSScriptRoot 'dist' }
$jarSource = Join-Path $PSScriptRoot 'bambu\target\bambu-web.jar'
$jarOut    = Join-Path $Out 'bambu-web.jar'
$started   = Get-Date

function Step($msg) { Write-Host "`n== $msg ==" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "   $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "   $msg" -ForegroundColor Yellow }

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
                    throw ("Could not delete $t after 5 attempts - something is holding a handle on it " +
                           "(antivirus, Search indexer, an open jar). Last error: $($_.Exception.Message)")
                }
                Warn "locked, retrying in 2s ($try/5): $(Split-Path $t -Leaf)"
                Start-Sleep -Seconds 2
            }
        }
    }
    Ok "target folders cleared"
}

Step 'Build'
$forced = $Force.IsPresent
if (-not $forced -and (Test-Path $jarSource)) {
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

$mvn = if (Get-Command mvn -ErrorAction SilentlyContinue) { 'mvn' }
       elseif (Test-Path (Join-Path $PSScriptRoot 'mvnw.cmd')) { Join-Path $PSScriptRoot 'mvnw.cmd' }
       else { throw 'Neither mvn nor mvnw.cmd found.' }

if ($forced) { Clear-Target }
$mvnArgs = if ($forced) {
    @('install', '-Pproduction', '-Dvaadin.force.production.build=true')
} else {
    @('package', '-Pproduction')
}
if ($SkipUnitTests) { $mvnArgs += '-DskipTests' }
Write-Host "   $mvn $($mvnArgs -join ' ')"
& $mvn @mvnArgs
$mvnExit = $LASTEXITCODE
if ($mvnExit -ne 0) {
    if (-not $SkipUnitTests) {
        Warn 'If this is a test failure, fix the test rather than passing -SkipUnitTests: the suite covers'
        Warn 'the bed-clear verdict, the order counters and the AMS-retry codes - a red one is a real wrong answer.'
    }
    throw "Maven failed with exit code $mvnExit - nothing was staged."
}
if (-not (Test-Path $jarSource)) { throw "Build reported success but $jarSource is missing." }
if ((Get-Item $jarSource).LastWriteTime -lt $started) {
    throw "$jarSource wasn't rewritten by this build - refusing to stage a stale jar."
}
Ok ("built {0:N0} MB" -f ((Get-Item $jarSource).Length / 1MB))

Step 'Staging'
New-Item -ItemType Directory -Force -Path $Out | Out-Null
if (Test-Path $jarOut -PathType Leaf) {
    Copy-Item $jarOut "$jarOut.prev" -Force
    Ok 'previous staged jar kept as bambu-web.jar.prev'
}
Copy-Item $jarSource $jarOut -Force
$srcLen = (Get-Item $jarSource).Length
$dstLen = (Get-Item $jarOut).Length
if ($srcLen -ne $dstLen) { throw "Copy verification FAILED: source $srcLen bytes, staged $dstLen bytes." }

$commit = & {
    $ErrorActionPreference = 'Continue'
    (git -C $PSScriptRoot log -1 --format='%h %s' 2>$null)
}
$dirty = & {
    $ErrorActionPreference = 'Continue'
    (git -C $PSScriptRoot status --porcelain --untracked-files=no 2>$null)
}
$sha = (Get-FileHash $jarOut -Algorithm SHA256).Hash.ToLower()
$info = @(
    "built:   $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss zzz'))"
    "commit:  $commit$(if ($dirty) { '  (+ uncommitted changes)' })"
    "forced:  $forced"
    "tests:   $(if ($SkipUnitTests) { 'SKIPPED' } else { 'run' })"
    "size:    $dstLen bytes"
    "sha256:  $sha"
)
Set-Content -Path (Join-Path $Out 'BUILD.txt') -Value $info -Encoding UTF8
$info | ForEach-Object { Ok $_ }
Ok ("staged -> {0}" -f $jarOut)
if ($forced) { Warn 'this build included the forced frontend rebuild - the jar carries the new CSS' }

Write-Host ''
Write-Host 'Next, on the prod box (from its bambu-liveview folder):' -ForegroundColor Cyan
Write-Host '   docker compose stop bambuweb'
Write-Host '   copy bambu-web.jar (and BUILD.txt) over the one there'
Write-Host '   docker compose up -d bambuweb'
Write-Host "   docker compose logs -f bambuweb   # wait for 'bambu-web ... started in'"
