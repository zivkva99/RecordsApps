# sync_examples.ps1
# Run this when your phone is connected via USB to pull new QA images and merge the dataset.
# Usage: .\sync_examples.ps1

$pcExamples  = "$PSScriptRoot\examples"
$devicePath  = "/sdcard/Android/data/com.recordsapp/files/examples"
$tempDir     = "$env:TEMP\recordsapp_sync"

Write-Host "Checking ADB device..."
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$devices = & $adb devices 2>&1 | Select-String "device$"
if (-not $devices) {
    Write-Error "No device found. Make sure USB debugging is enabled and the phone is connected."
    exit 1
}

Write-Host "Pulling files from device..."
if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
New-Item -ItemType Directory -Path $tempDir | Out-Null
& $adb pull "$devicePath/." $tempDir 2>&1 | Out-Null

$pulledImages = Get-ChildItem $tempDir -Filter "*.jpg" -ErrorAction SilentlyContinue
$deviceDataset = "$tempDir\qa_dataset.json"

if ($pulledImages.Count -eq 0 -and -not (Test-Path $deviceDataset)) {
    Write-Host "Nothing new on device."
    Remove-Item $tempDir -Recurse -Force
    exit 0
}

# Copy new images (skip ones already in examples/)
$copied = 0
foreach ($img in $pulledImages) {
    $dest = Join-Path $pcExamples $img.Name
    if (-not (Test-Path $dest)) {
        Copy-Item $img.FullName $dest
        $copied++
    }
}
Write-Host "Copied $copied new image(s)."

# Merge qa_dataset.json
if (Test-Path $deviceDataset) {
    $pcDatasetPath = Join-Path $pcExamples "qa_dataset.json"
    $pcEntries     = if (Test-Path $pcDatasetPath) { Get-Content $pcDatasetPath -Raw | ConvertFrom-Json } else { @() }
    $deviceEntries = Get-Content $deviceDataset -Raw | ConvertFrom-Json

    $existingFiles = $pcEntries | ForEach-Object { $_.filename }
    $newEntries    = $deviceEntries | Where-Object { $_.filename -notin $existingFiles }

    if ($newEntries) {
        $merged = @($pcEntries) + @($newEntries)
        $merged | ConvertTo-Json -Depth 5 | Out-File $pcDatasetPath -Encoding utf8
        Write-Host "Merged $($newEntries.Count) new dataset entry(s)."
    } else {
        Write-Host "Dataset already up to date."
    }
}

Remove-Item $tempDir -Recurse -Force

# Pull Room database
Write-Host "Pulling database..."
$dbDest = "$PSScriptRoot\records_database.db"
$dbTmp  = "/sdcard/Android/data/com.recordsapp/files/records_database_tmp"
& $adb shell "run-as com.recordsapp sh -c 'cp databases/records_database $dbTmp'" 2>&1 | Out-Null
& $adb pull $dbTmp $dbDest 2>&1 | Out-Null
& $adb shell "rm $dbTmp" 2>&1 | Out-Null
if (Test-Path $dbDest) {
    $sizeKB = [math]::Round((Get-Item $dbDest).Length / 1KB, 1)
    Write-Host "Database pulled: records_database.db ($sizeKB KB)"
} else {
    Write-Host "Database pull failed (app may not be installed or debuggable)."
}

Write-Host "Done."
