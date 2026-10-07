# NESTRA Remote Android - import the native viewer core (libnestra_viewer.so, RustDesk core, AGPL-3.0) built by the
# PUBLIC nestra-remote-engine workflow "android-viewer". Verifies the SHA-256 you copy from the workflow run, then puts
# the libraries into app\src\main\jniLibs\<abi> (arm64-v8a, armeabi-v7a, x86_64). Without it the app offers no session (no fake screen).
#   powershell -ExecutionPolicy Bypass -File import-viewer-core.ps1 -Zip <path>\nestra-viewer-android.zip -Sha256 <hex>
param([Parameter(Mandatory)][string]$Zip, [Parameter(Mandatory)][string]$Sha256)
$ErrorActionPreference = "Stop"
$app = Join-Path (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)) "app"
$got = (Get-FileHash -Algorithm SHA256 $Zip).Hash.ToLowerInvariant()
if ($got -ne $Sha256.Trim().ToLowerInvariant()) { Write-Host "SHA-256 MISMATCH ($got) - nothing imported"; exit 1 }
$tmp = Join-Path $env:TEMP ("nrviewer-" + [guid]::NewGuid().ToString("N"))
Expand-Archive -Path $Zip -DestinationPath $tmp
$abis = @(Get-ChildItem (Join-Path $tmp "jniLibs") -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.Name })
if (-not ($abis -contains "arm64-v8a")) { Write-Host "jniLibs\arm64-v8a missing in the zip - nothing imported"; Remove-Item -Recurse -Force $tmp; exit 1 }
# every .so must match the SHA256SUMS shipped by the workflow
foreach ($line in Get-Content (Join-Path $tmp "SHA256SUMS")) {
  $parts = $line -split '\s+', 2; $f = Join-Path $tmp ($parts[1].Trim().TrimStart('*') -replace '/', '\')
  if ((Get-FileHash -Algorithm SHA256 $f).Hash.ToLowerInvariant() -ne $parts[0]) { Write-Host "checksum mismatch: $($parts[1])"; Remove-Item -Recurse -Force $tmp; exit 1 }
}
foreach ($abi in $abis) {
  $src = Join-Path $tmp "jniLibs\$abi"
  if (-not (Test-Path (Join-Path $src "libnestra_viewer.so")) -or -not (Test-Path (Join-Path $src "libc++_shared.so"))) { Write-Host "$abi incomplete - nothing imported"; Remove-Item -Recurse -Force $tmp; exit 1 }
  $dst = Join-Path $app "src\main\jniLibs\$abi"
  New-Item -ItemType Directory -Force -Path $dst | Out-Null
  Copy-Item (Join-Path $src "libnestra_viewer.so"), (Join-Path $src "libc++_shared.so") $dst -Force
  Write-Host "  $abi : libnestra_viewer.so + libc++_shared.so"
}
$assets = Join-Path $app "src\main\assets\licenses"
New-Item -ItemType Directory -Force -Path $assets | Out-Null
foreach ($f in "AGPL-3.0.txt", "NOTICE", "SOURCE.md", "BUILD-INFO.txt", "UPSTREAM.lock", "CHANGES-FROM-UPSTREAM.md", "SHA256SUMS") { if (Test-Path (Join-Path $tmp $f)) { Copy-Item (Join-Path $tmp $f) $assets -Force } }
Remove-Item -Recurse -Force $tmp
Write-Host "Imported the native viewer core for $($abis -join ', ') (zip sha256 $got) + licence files. Build: gradlew :app:assembleDebug -PnestraSourceUrl=<public repo URL>"
