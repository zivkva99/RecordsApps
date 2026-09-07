# install_collection.ps1
# Replaces the app's on-device database and cover images with the staged
# collection built from examples/recognition_results.json (run
# build_install_staging.py first, or ask Claude to do it again if the
# dataset changes). Requires a connected, debuggable device/emulator with
# the app already installed at least once.
#
# Backs up whatever is currently on the phone (database + cover images)
# into a timestamped backup/ folder before touching anything, since this
# is a full replace, not a merge.
#
# Usage: .\install_collection.ps1

$staging = "$PSScriptRoot\install_staging"
$dbSrc = "$staging\records_database"
$filesSrc = "$staging\files"
$pkg = "com.recordsapp"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

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

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$backupDir = "$PSScriptRoot\backup\$stamp"
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null

Write-Host "Backing up current on-device database and covers to $backupDir ..."
& $adb shell "run-as $pkg sh -c 'cp databases/records_database /sdcard/records_database_backup_tmp'" 2>&1 | Out-Null
& $adb pull /sdcard/records_database_backup_tmp "$backupDir\records_database" 2>&1 | Out-Null
& $adb shell "rm /sdcard/records_database_backup_tmp" 2>&1 | Out-Null
& $adb shell "run-as $pkg sh -c 'mkdir -p /sdcard/cover_backup_tmp && cp files/cover_*.jpg /sdcard/cover_backup_tmp/ 2>/dev/null'" 2>&1 | Out-Null
New-Item -ItemType Directory -Path "$backupDir\files" -Force | Out-Null
& $adb pull /sdcard/cover_backup_tmp "$backupDir\files" 2>&1 | Out-Null
& $adb shell "rm -rf /sdcard/cover_backup_tmp" 2>&1 | Out-Null

if (Test-Path "$backupDir\records_database") {
    Write-Host "Backup saved: $backupDir"
} else {
    Write-Warning "Could not confirm the backup database was pulled — check $backupDir before proceeding if this matters to you."
}

Write-Host "Stopping the app..."
& $adb shell "am force-stop $pkg" 2>&1 | Out-Null

Write-Host "Clearing existing on-device database and cover images..."
& $adb shell "run-as $pkg sh -c 'rm -f databases/records_database databases/records_database-wal databases/records_database-shm'" 2>&1 | Out-Null
& $adb shell "run-as $pkg sh -c 'rm -f files/cover_*.jpg'" 2>&1 | Out-Null

Write-Host "Pushing new database..."
& $adb push $dbSrc /sdcard/records_database_new_tmp 2>&1 | Out-Null
& $adb shell "run-as $pkg sh -c 'cp /sdcard/records_database_new_tmp databases/records_database'" 2>&1 | Out-Null
& $adb shell "rm /sdcard/records_database_new_tmp" 2>&1 | Out-Null

Write-Host "Pushing cover images..."
$coverFiles = Get-ChildItem $filesSrc -Filter "*.jpg"
& $adb shell "mkdir -p /sdcard/cover_push_tmp" 2>&1 | Out-Null
& $adb push "$filesSrc/." /sdcard/cover_push_tmp 2>&1 | Out-Null
& $adb shell "run-as $pkg sh -c 'cp /sdcard/cover_push_tmp/*.jpg files/'" 2>&1 | Out-Null
& $adb shell "rm -rf /sdcard/cover_push_tmp" 2>&1 | Out-Null
Write-Host "Pushed $($coverFiles.Count) cover images."

Write-Host "Done. Open the app on your phone to see the new collection."
Write-Host "If something looks wrong, your previous data is safe in: $backupDir"
