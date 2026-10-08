# Corresponding Source

Every binary NESTRA distributes that contains RustDesk code is built by the workflows in `.github/workflows/` of
this repository from:
- the upstream commit in `UPSTREAM.lock` (rustdesk/rustdesk + its hbb_common submodule commit),
- `nestra/apply_patches.py` + `nestra/src/*.rs` (the NESTRA modifications), applied in the workflow,
- for the Android app: `android/` (the complete app source: Kotlin app, core library, Gradle build files),
- the workflow files themselves (build scripts are part of the Corresponding Source).

Each artifact carries BUILD-INFO.txt (upstream commit, this repository's commit, workflow run) and
nestra-changes.diff (the exact applied change set). The source of a shipped binary is this repository at the commit
named in its BUILD-INFO.txt; keep that commit (tag it vX.Y.Z) available for as long as the binary is offered and at
least three years after.

To rebuild: fork this repository, run the two workflows, import the Android core with
`android/tools/import-viewer-core.ps1`, build with `gradlew :app:assembleDebug -PnestraSourceUrl=<your fork URL>`.

Not included: release signing keys (Android keystore, Authenticode certificate) and the hbbs private key.
