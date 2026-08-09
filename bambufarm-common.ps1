<#
.SYNOPSIS
    Shared helpers for deploy.ps1 and heartbeat.ps1.

.DESCRIPTION
    Dot-source it:  . "$PSScriptRoot\bambufarm-common.ps1"
#>

<#
.SYNOPSIS
    Works out where the deployment folder is, and refuses to guess.

.DESCRIPTION
    Resolution order, first hit wins:

      1. An explicit -ProdPath argument
      2. $env:BAMBUFARM_PROD
      3. prod-path.txt sitting next to these scripts (one line, the path)
      4. The historical default

    Then it VALIDATES: the folder must exist and contain compose.yml. That check is the point of this
    function. A hardcoded default is fine right up until the instance moves to another machine, at which
    point the old path either doesn't exist - and every command fails with something unhelpful about a
    missing file - or, far worse, still exists as a stale copy and you deploy a new jar into a folder
    nothing is running from, then spend an afternoon wondering why your change didn't take effect.

    Failing loudly with the resolution order printed turns that into a ten-second fix.
#>
function Resolve-ProdPath {
    param(
        [string]$Explicit,
        [string]$ScriptRoot = $PSScriptRoot
    )

    $fallback = 'C:\Users\Vishal\Downloads\bambu-farm-web\bambu-liveview'
    $fileHint = Join-Path $ScriptRoot 'prod-path.txt'

    $path, $source =
        if ($Explicit)                          { $Explicit, '-ProdPath argument' }
        elseif ($env:BAMBUFARM_PROD)            { $env:BAMBUFARM_PROD, '$env:BAMBUFARM_PROD' }
        elseif (Test-Path -LiteralPath $fileHint) {
            (Get-Content -LiteralPath $fileHint -TotalCount 1).Trim(), 'prod-path.txt'
        }
        else                                    { $fallback, 'built-in default' }

    if (-not $path) {
        throw "Deployment folder is empty (from $source)."
    }
    $path = $path.Trim().TrimEnd('\')

    if (-not (Test-Path -LiteralPath (Join-Path $path 'compose.yml'))) {
        throw @"
Deployment folder not usable: $path
  (resolved from: $source)

No compose.yml there, so this is not the folder the farm runs from. Set it one of these ways:

  1. Pass it:        -ProdPath 'D:\bambu-liveview'
  2. Environment:    setx BAMBUFARM_PROD "D:\bambu-liveview"     (new shell required)
  3. Write a file:   'D:\bambu-liveview' | Set-Content '$fileHint'

Option 3 is the one to reach for when moving machines: it travels with the repo, so the new box needs a
one-line edit rather than a hunt through two scripts - and until you make that edit you land here, which
is the point. A wrong path that announces itself beats a wrong path that quietly works.
"@
    }

    Write-Host "   prod folder: $path  (from $source)" -ForegroundColor DarkGray
    return $path
}
