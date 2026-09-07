# install_collection.ps1
# Replaces the app's on-device database and cover images with the staged
# collection built from examples/recognition_results.json (run
# build_install_staging.py first, or ask Claude to do it again if the
# dataset changes). Requires a connected, debuggable device/emulator with
# the app already installed at least once.
#
# Backs up whatever is currently on the phone (database + cover images)
# into a timestamped backup/ folder before touching anything, since this
# is a full replace, not a merge. Aborts if the backup can't be verified,
# unless -Force is passed.
#
# Note: run-as can only read/write files under the app's own external
# files dir (/sdcard/Android/data/<pkg>/files/) -- under scoped storage
# it gets "Permission denied" on arbitrary /sdcard paths, even though
# `adb push`/`pull` themselves succeed there. All intermediate copies
# below go through that directory for this reason.
#
# Usage: .\install_collection.ps1 [-Force]

param(
    [switch]$Force
)

$staging = "$PSScriptRoot\install_staging"
$dbSrc = "$staging\records_database"
$filesSrc = "$staging\files"
$pkg = "com.recordsapp"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$extDir = "/sdcard/Android/data/$pkg/files"

function Invoke-Adb {
    param([string[]]$AdbArgs)
    $out = & $adb @AdbArgs 2>&1
    if ($out) { Write-Host "  $out" }
    return $out
}

if (-not (Test-Path $dbSrc)) {
    Write-Error "No staged database found at $dbSrc. Run build_install_staging.py first."
    exit 1
}

Write-Host "Checking ADB device..."
$devices = & $adb devices 2>&1 | Select-String "device$"
if (-not $devices) {
    Write-Error "No device found. Make sure USB debugging is enabled and the phone is connected."
    exit 1
}

Invoke-Adb @("shell", "mkdir -p $extDir")

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$backupDir = "$PSScriptRoot\backup\$stamp"
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
New-Item -ItemType Directory -Path "$backupDir\files" -Force | Out-Null

Write-Host "Backing up current on-device database and covers to $backupDir ..."
Invoke-Adb @("shell", "run-as $pkg sh -c 'cp databases/records_database $extDir/records_database_backup_tmp'")
Invoke-Adb @("pull", "$extDir/records_database_backup_tmp", "$backupDir\records_database")
Invoke-Adb @("shell", "rm -f $extDir/records_database_backup_tmp")
Invoke-Adb @("shell", "run-as $pkg sh -c 'mkdir -p $extDir/cover_backup_tmp && cp files/cover_*.jpg $extDir/cover_backup_tmp/ 2>/dev/null'")
Invoke-Adb @("pull", "$extDir/cover_backup_tmp", "$backupDir\files")
Invoke-Adb @("shell", "rm -rf $extDir/cover_backup_tmp")

$backupOk = Test-Path "$backupDir\records_database"
if ($backupOk) {
    Write-Host "Backup saved: $backupDir"
} else {
    Write-Warning "Could not confirm the backup database was pulled - check $backupDir."
    if (-not $Force) {
        Write-Error "Aborting before touching the device. Re-run with -Force to proceed anyway (not recommended)."
        exit 1
    }
    Write-Warning "Continuing anyway because -Force was passed."
}

Write-Host "Stopping the app..."
Invoke-Adb @("shell", "am force-stop $pkg")

Write-Host "Clearing existing on-device database and cover images..."
Invoke-Adb @("shell", "run-as $pkg sh -c 'rm -f databases/records_database databases/records_database-wal databases/records_database-shm'")
Invoke-Adb @("shell", "run-as $pkg sh -c 'rm -f files/cover_*.jpg'")

Write-Host "Pushing new database..."
Invoke-Adb @("push", $dbSrc, "$extDir/records_database_new_tmp")
Invoke-Adb @("shell", "run-as $pkg sh -c 'cp $extDir/records_database_new_tmp databases/records_database'")
Invoke-Adb @("shell", "rm -f $extDir/records_database_new_tmp")

Write-Host "Pushing cover images..."
$coverFiles = Get-ChildItem $filesSrc -Filter "*.jpg"
Invoke-Adb @("shell", "mkdir -p $extDir/cover_push_tmp")
Invoke-Adb @("push", "$filesSrc\.", "$extDir/cover_push_tmp")
Invoke-Adb @("shell", "run-as $pkg sh -c 'cp $extDir/cover_push_tmp/*.jpg files/'")
Invoke-Adb @("shell", "rm -rf $extDir/cover_push_tmp")

$onDeviceCount = (& $adb shell "run-as $pkg sh -c 'ls files/ | grep cover_ | wc -l'" 2>&1).Trim()
Write-Host "Staged $($coverFiles.Count) cover images; app now has $onDeviceCount cover_*.jpg files."
if ("$onDeviceCount" -ne "$($coverFiles.Count)") {
    Write-Warning "Count mismatch -- verify manually before trusting this run."
}

Write-Host "Done. Open the app on your phone to see the new collection."
Write-Host "If something looks wrong, your previous data is in: $backupDir"
