# Prepare a microSD card for Revive360 (run after camera\build.ps1).
#
#   powershell -ExecutionPolicy Bypass -File camera\tools\prepare_sd.ps1 -Drive E:
#   ... -Drive E: -NewPsk          generate a new passphrase even if one exists
#   ... -Drive E: -LedTest on|off  enable/disable the LED mapping diagnostic
#   ... -Drive E: -SelfTest on|off on-camera API self-test (takes 3 real shots)
#   ... -Drive E: -Normal          card for the modified firmware (camera\fw\README.md):
#                                  no factory trigger files; existing ones are renamed *.off
#   ... -Drive E: -KeepAwake on|off sdb.conf at the root: disables the stock idle
#                                  auto power-off (for tests)
#
# Copies the trigger files (info.tg, cmd.tg; not with -Normal) to the card root and the
# payload to <card>\g360r\. Creates g360r\g360r.conf with a random 8-digit passphrase on
# first use (kept on later runs) and prints it: enter it once in the app.
# Files on the card get LF line endings (the camera's bash rejects CRLF).
param(
    [Parameter(Mandatory = $true)][string]$Drive,
    [switch]$NewPsk,
    [ValidateSet('', 'on', 'off')][string]$LedTest = '',
    [ValidateSet('', 'on', 'off')][string]$SelfTest = '',
    [switch]$Normal,
    [ValidateSet('', 'on', 'off')][string]$KeepAwake = ''
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Join-Path (Split-Path -Parent $here) 'out\sd'
if (-not (Test-Path (Join-Path $src 'g360r\bin\g360rd'))) {
    Write-Error "Build output not found in $src. Run camera\build.ps1 first."
}
$root = $Drive.TrimEnd('\', '/')
if ($root -match '^[A-Za-z]$') { $root += ':' }
$root += '\'
if (-not (Test-Path $root)) { Write-Error "Drive $root not found." }

$utf8 = New-Object System.Text.UTF8Encoding($false)
function Write-Lf([string]$path, [string]$text) {
    [IO.File]::WriteAllText($path, ($text -replace "`r`n", "`n"), $utf8)
}

New-Item -ItemType Directory -Force (Join-Path $root 'g360r\bin') | Out-Null
New-Item -ItemType Directory -Force (Join-Path $root 'g360r\log') | Out-Null
if ($Normal) {
    # info.tg at the root would start the factory-mode hook instead of the normal camera
    foreach ($t in @('info.tg', 'cmd.tg')) {
        $p = Join-Path $root $t
        if (Test-Path $p) { Move-Item $p "$p.off" -Force; Write-Host "  renamed $t -> $t.off" }
    }
} else {
    Copy-Item (Join-Path $src 'info.tg') $root -Force
    Copy-Item (Join-Path $src 'cmd.tg') $root -Force
}
$sdb = Join-Path $root 'sdb.conf'
if ($KeepAwake -eq 'on')  { Write-Lf $sdb '' }
if ($KeepAwake -eq 'off' -and (Test-Path $sdb)) { Remove-Item $sdb -Force }
Get-ChildItem (Join-Path $src 'g360r') -File | ForEach-Object { Copy-Item $_.FullName (Join-Path $root 'g360r') -Force }
Copy-Item (Join-Path $src 'g360r\bin\g360rd') (Join-Path $root 'g360r\bin\g360rd') -Force

$conf = Join-Path $root 'g360r\g360r.conf'
$psk = $null
if ((Test-Path $conf) -and -not $NewPsk) {
    $m = Select-String -Path $conf -Pattern '^\s*psk\s*=\s*(.*)$' | Select-Object -Last 1
    if ($m) { $psk = $m.Matches[0].Groups[1].Value.Trim().Trim('"') }
    if ($psk -eq 'CHANGE_ME' -or $psk.Length -lt 8) { $psk = $null }
    $text = [IO.File]::ReadAllText($conf)
}
if (-not $psk) {
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 8
    $digits = ''
    while ($digits.Length -lt 8) {
        $rng.GetBytes($bytes)
        foreach ($b in $bytes) { if ($b -lt 250 -and $digits.Length -lt 8) { $digits += [string]($b % 10) } }
    }
    $psk = $digits
    $text = [IO.File]::ReadAllText((Join-Path $src 'g360r\g360r.conf.example'))
    $text = [regex]::Replace($text, '(?m)^psk=.*$', "psk=$psk")
}
# Older configs may lack a key: append it so the switches below always apply.
foreach ($k in @('ledtest', 'selftest')) {
    if ($text -notmatch "(?m)^$k=") { $text = $text.TrimEnd() + "`n$k=0`n" }
}
if ($LedTest -eq 'on')  { $text = [regex]::Replace($text, '(?m)^ledtest=.*$', 'ledtest=1') }
if ($LedTest -eq 'off') { $text = [regex]::Replace($text, '(?m)^ledtest=.*$', 'ledtest=0') }
if ($SelfTest -eq 'on')  { $text = [regex]::Replace($text, '(?m)^selftest=.*$', 'selftest=1') }
if ($SelfTest -eq 'off') { $text = [regex]::Replace($text, '(?m)^selftest=.*$', 'selftest=0') }
Write-Lf $conf $text

# Normalize line endings of everything the camera reads as text.
foreach ($f in @('info.tg', 'cmd.tg', 'g360r\start.sh', 'g360r\launcher.sh', 'g360r\netup.sh')) {
    $p = Join-Path $root $f
    if (Test-Path $p) { Write-Lf $p ([IO.File]::ReadAllText($p)) }
}

$ledState = if ($text -match '(?m)^ledtest=1') { 'ON (about 75 s at boot)' } else { 'off' }
Write-Host ''
Write-Host "SD card $root prepared."
Write-Host "  Wi-Fi passphrase : $psk   (enter it once in the app)"
Write-Host "  SSID             : G360R-XXXXXX (last 6 hex digits of the camera's Wi-Fi MAC)"
Write-Host "  LED diagnostic   : $ledState"
$selfState = if ($text -match '(?m)^selftest=1') { 'ON (3 real shots after boot)' } else { 'off' }
Write-Host "  API self-test    : $selfState"
Write-Host "  Mode             : $(if ($Normal) { 'normal (modified firmware)' } else { 'factory SD hook (info.tg)' })"
Write-Host "  Idle power-off   : $(if (Test-Path $sdb) { 'DISABLED (sdb.conf)' } else { 'stock' })"
Write-Host "  Logs after a run : $($root)g360r\log\"
Write-Host 'Eject the card safely before removing it.'
