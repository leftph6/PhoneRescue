param([string]$Serial, [int]$WaitSeconds = 45, [switch]$Watch)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $PSScriptRoot 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw 'Missing platform-tools/adb.exe' }
$deadline = (Get-Date).AddSeconds($WaitSeconds)
Write-Host 'Waiting for an Android phone. Unlock it, enable USB debugging, and accept the USB authorization dialog.'
do {
    $deviceRows = & $adb devices
    $deviceIds = @($deviceRows | ForEach-Object { if ($_ -match '^(?!emulator-)(\S+)\s+device$') { $Matches[1] } })
    if ($Serial -or $deviceIds.Count -gt 0) { break }
    if ($deviceRows -match '\sunauthorized') { Write-Host 'Phone detected: please accept USB debugging authorization on the phone.' }
    if ((Get-Date) -ge $deadline) { break }
    Start-Sleep -Seconds 3
} while ($true)
$deviceRows | Write-Host
if (-not $Serial) {
    if ($deviceIds.Count -eq 0) {
        $usbIssues = Get-CimInstance Win32_PnPEntity | Where-Object { $_.Name -match 'Android|ADB' -and $_.ConfigManagerErrorCode -ne 0 }
        $usbIssues | Select-Object Name,ConfigManagerErrorCode | Format-Table | Out-Host
        throw 'No authorized physical phone detected. Check data cable, USB debugging and USB driver. If using spacedesk USB, exit its USB mode and reconnect the phone. An offline emulator is not your phone.'
    }
    if ($deviceIds.Count -gt 1) { throw 'Multiple phones detected. Run connect-usb.ps1 -Serial DEVICE_ID.' }
    $Serial = $deviceIds[0]
}
& $adb -s $Serial reverse tcp:18765 tcp:18765
if ($LASTEXITCODE -ne 0) { throw 'USB reverse failed.' }
Write-Host 'USB connected.'
$sessionPath = Join-Path $env:LOCALAPPDATA 'PhoneRescue\session.json'
if (Test-Path -LiteralPath $sessionPath) {
    $session = Get-Content -LiteralPath $sessionPath -Raw | ConvertFrom-Json
    if (-not (Get-Process -Id $session.processId -ErrorAction SilentlyContinue)) { throw 'Pairing session has expired. Start start-usb.cmd first.' }
    if ($session.url -match '^http://127\.0\.0\.1:18765/#([0-9]{4}|[A-F0-9]{48})$') {
        & $adb -s $Serial shell am start --user 0 -n local.phonerescue.app/.MainActivity --es pairing $session.url | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Cannot launch the app. Install PhoneRescue-0.3.apk on this phone first.' }
        Write-Host 'Pairing sent. PhoneRescue 0.3 connects automatically.'
    }
} else {
    throw 'No active pairing session. Start start-usb.cmd first, then run this script again.'
}
if ($Watch) {
    while (Get-Process -Id $session.processId -ErrorAction SilentlyContinue) {
        Start-Sleep -Seconds 3
        try { $state = & $adb -s $Serial get-state 2>$null } catch { continue }
        if ($state -ne 'device') { continue }
        $reverse = & $adb -s $Serial reverse --list 2>$null
        if (-not ($reverse -match 'tcp:18765 tcp:18765')) {
            & $adb -s $Serial reverse tcp:18765 tcp:18765 | Out-Null
        }
    }
}
