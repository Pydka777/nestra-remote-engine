# Publishing the public AGPL repository and running the native builds (owner steps)

Run on the laptop in PowerShell (needs git + GitHub CLI `gh`, signed in to the GitHub account that will own the
public repository). Nothing here touches the VPS, the installed NESTRA Remote agent or NESTRA Parent.

```powershell
cd "$HOME\NESTRA-BUILD\nestra-remote-engine"
# 1. upstream LICENCE byte-for-byte at the pinned commit (CI verifies it with cmp)
Invoke-WebRequest https://raw.githubusercontent.com/rustdesk/rustdesk/fada664df7a294d1d1a9ca3e7cd3637069122f17/LICENCE -OutFile LICENCE
# 2. local check of the patch script (Python 3)
python nestra\tests\test_apply_patches.py
# 3. repository (public: it is the AGPL Corresponding Source) + first push
git init -b main
git add -A
git commit -m "NESTRA Remote Engine: RustDesk 1.5.0 @ fada664d + NESTRA patch set (ETAP 9A)"
gh repo create nestra-remote-engine --public --source . --push
# 4. native builds on GitHub-hosted runners
gh workflow run engine-windows.yml
gh workflow run android-viewer.yml
gh run list --limit 4
gh run watch            # pick each run
# 5. download the artifacts (replace <run-id>)
gh run download <run-id-engine>  -n nestra-remote-engine-windows-x86 -D "$HOME\NESTRA-BUILD\ci\engine"
gh run download <run-id-android> -n nestra-viewer-android            -D "$HOME\NESTRA-BUILD\ci\android"
git rev-parse HEAD      # = the NESTRA patch commit recorded in BUILD-INFO.txt
```

Then (laptop, still no installation):
```powershell
# Android: import + full build (BUILD SUCCESSFUL expected)
$sha = (Get-Content "$HOME\NESTRA-BUILD\ci\android\nestra-viewer-android.zip.sha256").Split(' ')[0]
powershell -ExecutionPolicy Bypass -File "$HOME\NESTRA-BUILD\nestra-remote\android\tools\import-viewer-core.ps1" -Zip "$HOME\NESTRA-BUILD\ci\android\nestra-viewer-android.zip" -Sha256 $sha
cd "$HOME\NESTRA-BUILD\nestra-remote\android"
.\gradlew.bat clean :core:test :app:assembleDebug -PnestraSourceUrl=https://github.com/<owner>/nestra-remote-engine/tree/<commit>
# Windows engine: static package check only (nothing installed, nothing executed)
$esha = (Get-Content "$HOME\NESTRA-BUILD\ci\engine\nestra-remote-engine-windows-x86.zip.sha256").Split(' ')[0]
powershell -ExecutionPolicy Bypass -File "$HOME\NESTRA-BUILD\nestra-remote\deploy\scripts\etap9-install-engine.ps1" -Zip "$HOME\NESTRA-BUILD\ci\engine\nestra-remote-engine-windows-x86.zip" -Sha256 $esha
```
If a workflow fails, keep the run URL / log: the patch script and the checks name the exact file and anchor.
