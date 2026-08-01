# One-off correction for the two orders whose expected-job count is wrong on disk.
# The code fix stops this happening again but does NOT repair existing entries.
#
# Run from the PROD folder, with the container STOPPED - the app rewrites this file on
# every mutation, so edits made while it's running will be overwritten:
#
#   cd C:\Users\Vishal\Downloads\bambu-farm-web\bambu-liveview
#   docker compose stop bambuweb
#   .\fix-stuck-orders.ps1
#   docker compose start bambuweb

$ErrorActionPreference = 'Stop'
$file = Join-Path $PSScriptRoot 'data\bambu-order-tracking.json'
if (-not (Test-Path $file)) { throw "Not found: $file - run this from the prod bambu-liveview folder." }

Copy-Item $file "$file.bak" -Force
Write-Host "backed up to $file.bak" -ForegroundColor DarkGray

$j = Get-Content $file -Raw | ConvertFrom-Json
$p = $j.etsy.progress

# 4130857746 - "1x Upgraded Cupholder Insert For G35/G37". One item, one mapping part,
# copiesPerUnit=1, so it needs exactly ONE print. It reads 2 because the stopped P1S print
# never released its expectation and the re-queue to P1P registered a second one.
if ($p.'4130857746') {
    Write-Host ("4130857746: expected {0} -> 1 (printed {1})" -f $p.'4130857746'.expected, $p.'4130857746'.printed)
    $p.'4130857746'.expected = 1
    $p.'4130857746'.notified = $false
}

# 4130024299 - sitting at 2/4. Its auto-queue line has rotated out of the logs, so the true
# part count is unknown. CHECK THE ORDER ON ETSY FIRST, then either:
#   - set expected to the real number of parts (edit the 4 below), or
#   - if you've already shipped it, just mark it shipped on Etsy: pruneClosed drops the
#     entry automatically on the next successful poll and you can delete this block.
if ($p.'4130024299') {
    Write-Host ("4130024299: expected {0}, printed {1} - left alone, set it by hand if needed" -f `
        $p.'4130024299'.expected, $p.'4130024299'.printed) -ForegroundColor Yellow
    # $p.'4130024299'.expected = 4
}

$j | ConvertTo-Json -Depth 12 | Set-Content $file -Encoding UTF8
Write-Host "written. Start the container and check the Automation overview." -ForegroundColor Green
