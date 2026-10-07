# nestra-remote-engine (public, AGPL-3.0)

Corresponding Source of everything NESTRA distributes that contains RustDesk code:
- **NESTRA Remote Engine** for Windows (`nestra-remote-engine.exe`, a modified RustDesk), and
- **NESTRA Remote for Android** (`android/`, Kotlin app + the RustDesk client core `libnestra_viewer.so`).

Upstream is pinned to an exact commit (not a branch, not only a tag): see `UPSTREAM.lock`.
The NESTRA changes are applied by `nestra/apply_patches.py` (every edit anchored and counted against that commit)
plus the new modules in `nestra/src/`. See NOTICE, SOURCE.md, CHANGES-FROM-UPSTREAM.md.

## Build (GitHub-hosted Actions only)
| Workflow | Output | Recipe |
|---|---|---|
| `engine-windows` | `nestra-remote-engine-windows-x86.zip` (+ `.sha256`, `files.sha256`, `nestra-changes.diff`) | upstream "build-for-windows-sciter" job (i686, nightly-2023-10-13, LLVM 15, vcpkg x86-windows-static) |
| `android-viewer` | `nestra-viewer-android.zip` = `jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libnestra_viewer.so` + `libc++_shared.so`, `SHA256SUMS` | upstream "generate-bridge" + "build-rustdesk-android" jobs (Rust 1.75, NDK r28c, cargo-ndk 3.1.2) |

Both are `workflow_dispatch`. They fetch the pinned commit, verify it and the hbb_common submodule commit, apply the
patches, run static checks, build, check the binaries (fixed NESTRA host/key present, upstream public server/key
absent, documented CLI / JNI symbols present) and upload the artifacts with SHA-256 values.

## Interfaces (documented, used by independent NESTRA programs)
- Windows: `nestra-remote-engine.exe --get-id` (upstream), `--nestra-session` (one-time grant on stdin, status lines
  on stdout), `--nestra-close` (close every remote connection), `--silent-install` (upstream installer mode).
- Android: JNI class `com.nestra.remote.viewer.NativeViewer`, ABI 2 (see `nestra/src/nestra_viewer.rs`).
