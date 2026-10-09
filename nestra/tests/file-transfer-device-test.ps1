# NESTRA Remote - physical file-transfer test (laptop + phone over ADB). Changes nothing outside its own test folders.
#
#   1. powershell -ExecutionPolicy Bypass -File file-transfer-device-test.ps1 -Prepare
#        creates <Desktop>\NESTRA-FT-TEST\ with the 5 test files + manifest.sha256 and pushes the same files to the
#        phone (/sdcard/Download/NESTRA-FT-UP/) so they can be picked for the upload test
#   2. on the phone: TOOLS -> Files -> open Desktop\NESTRA-FT-TEST, download each of the 5 files;
#      then open Desktop\NESTRA-FT-TEST\uploads and upload the 5 files from Download/NESTRA-FT-UP
#   3. powershell -ExecutionPolicy Bypass -File file-transfer-device-test.ps1 -Verify
#        pulls the phone's downloaded copies and compares SHA-256 of all 10 transfers with the originals
# Report: <Desktop>\NESTRA-FT-TEST\ft-report.txt ; exit 0 = GREEN, 1 = RED
param([switch]$Prepare, [switch]$Verify,
      [string]$PhoneDownloads = "/sdcard/Download/NESTRA",
      [string]$PhoneUploadSource = "/sdcard/Download/NESTRA-FT-UP")
$ErrorActionPreference = "Stop"
$root = Join-Path ([Environment]::GetFolderPath("Desktop")) "NESTRA-FT-TEST"
$up = Join-Path $root "uploads"
$names = @("test-1KB.txt", "zdjecie-2MB.jpg", "archiwum-20MB.zip", "Zażółć gęślą jaźń.txt", "plik ze spacjami w nazwie.txt")
$sizes = @(1024, 2MB, 20MB, 4096, 8192)
function Sha([string]$p) { (Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash.ToLowerInvariant() }
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe" }

if ($Prepare) {
  New-Item -ItemType Directory -Force -Path $root, $up | Out-Null
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  $lines = @()
  for ($i = 0; $i -lt $names.Count; $i++) {
    $b = New-Object byte[] $sizes[$i]; $rng.GetBytes($b)
    if ($names[$i].EndsWith(".jpg")) { $b[0] = 0xFF; $b[1] = 0xD8; $b[2] = 0xFF; $b[$b.Length - 2] = 0xFF; $b[$b.Length - 1] = 0xD9 }
    if ($names[$i].EndsWith(".txt")) { $txt = [Text.Encoding]::UTF8.GetBytes(("NESTRA Remote file-transfer test - zażółć gęślą jaźń 0123456789`r`n" * 200)); [Array]::Copy($txt, $b, [Math]::Min($txt.Length, $b.Length)) }
    $p = Join-Path $root $names[$i]
    [IO.File]::WriteAllBytes($p, $b)
    $lines += "$(Sha $p)  $($names[$i])"
  }
  [IO.File]::WriteAllLines((Join-Path $root "manifest.sha256"), [string[]]$lines, (New-Object Text.UTF8Encoding($false)))
  & $adb shell mkdir -p $PhoneUploadSource | Out-Null
  foreach ($n in $names) { & $adb push (Join-Path $root $n) "$PhoneUploadSource/$n" | Out-Null; if ($LASTEXITCODE) { throw "adb push failed: $n" } }
  Write-Host "Prepared $($names.Count) files in $root (manifest.sha256) and on the phone in $PhoneUploadSource"
  exit 0
}

if ($Verify) {
  $manifest = @{}
  foreach ($l in [IO.File]::ReadAllLines((Join-Path $root "manifest.sha256"), [Text.Encoding]::UTF8)) { $h, $n = $l -split '  ', 2; $manifest[$n] = $h }
  $tmp = Join-Path $env:TEMP ("nestra-ft-" + [guid]::NewGuid().ToString("N")); New-Item -ItemType Directory -Path $tmp | Out-Null
  $results = New-Object System.Collections.Generic.List[string]
  foreach ($n in $names) {
    $dst = Join-Path $tmp $n
    & $adb pull "$PhoneDownloads/$n" $dst 2>&1 | Out-Null
    $ok = (Test-Path -LiteralPath $dst) -and ((Sha $dst) -eq $manifest[$n])
    $results.Add("{0,-5} download PC->phone  {1}" -f $(if ($ok) { "PASS" } else { "FAIL" }), $n)
    $u = Join-Path $up $n
    $ok = (Test-Path -LiteralPath $u) -and ((Sha $u) -eq $manifest[$n])
    $results.Add("{0,-5} upload   phone->PC  {1}" -f $(if ($ok) { "PASS" } else { "FAIL" }), $n)
  }
  Remove-Item -Recurse -Force $tmp
  $nf = @($results | Where-Object { $_ -like "FAIL*" }).Count
  $results.Add("FAIL=$nf"); $results.Add($(if ($nf -eq 0) { "FILE TRANSFER DEVICE TEST: GREEN" } else { "FILE TRANSFER DEVICE TEST: RED" }))
  [IO.File]::WriteAllLines((Join-Path $root "ft-report.txt"), [string[]]$results, (New-Object Text.UTF8Encoding($false)))
  $results | ForEach-Object { Write-Host $_ }
  exit $(if ($nf -eq 0) { 0 } else { 1 })
}
Write-Host "Use -Prepare or -Verify"; exit 2
