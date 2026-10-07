# NESTRA Remote Android 0.1.0 (ETAP 8 foundation, not production-ready)

Native Android client (Kotlin, Jetpack Compose, Material 3; dark first). It covers:

- NESTRA account sign-in, including Parent MFA
- the NESTRA Remote token
- the account device list
- pairing a Windows PC (ETAP 6 protocol, unchanged)
- device details and removing (unpairing) a device
- signing out

There is no remote desktop yet: no streaming, input, clipboard, files or audio.

## Modules

| Module | What | Tests |
|---|---|---|
| `core` | Plain Kotlin, no Android API, no third-party library: Parent login + MFA (`auth`), Remote API (`api`), pairing input / QR (`pairing`), sign-in state machine and token refresh (`session`), secret-store contract (`security`), strict JSON, HTTP transport. | `gradlew :core:test` (15 unit tests); **real-backend gate** `tools/run-core-integration.sh` (38 end-to-end checks) |
| `app` | Compose UI + `AppViewModel`; `KeystoreSecretStore` (Android Keystore AES-256-GCM); Google code scanner for the QR; `nestra://pair/<PairingId>` deep link. | built on the laptop |
| `integration` | The Kotlin client driven by `server/tests/.../AndroidCoreIntegrationTests.cs` against the REAL NestraRemote.Server + NestraRemote.AccountBridge + the Parent login/MFA contract host. | |

## Build (laptop, Android Studio / JBR 21)

The toolchain is the same one the NESTRA Parent Android project already builds with on this laptop: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9, compileSdk/targetSdk 35, minSdk 26.

```
cd %USERPROFILE%\NESTRA-BUILD\nestra-remote\android
gradlew.bat :core:test :app:assembleDebug
```

The APK is written to `app\build\outputs\apk\debug\app-debug.apk`. Alternatively, open the `android` folder in Android Studio and run "app". It is a debug build signed with the debug key, for testing only.

## Security model (short)

- **Password and MFA code:** never stored. They go only to NESTRA Parent (`/api/auth/login`, `/api/auth/mfa/verify-login`).
- **MFA challenge:** never a session. Malformed codes are refused locally.
- **When a session counts:** only once Parent's `/api/auth/me` returns 200 for the login's accountId.
- **Parent session:**
  - Keystore-encrypted at rest; re-proven with Parent at app start; wiped on sign-out (Parent logout too).
  - Sent only to NESTRA Parent and, as `Authorization: Bearer`, to `remote.nestraparent.com/v1/auth/token`.
- **Remote token:** ES256, 300 s, kept in memory only. It is re-minted 30 s before expiry and once after a 401.
- **Sign-out:** revokes it (`/v1/auth/logout`).
- **Credentials never travel** in a URL or as a cookie to NESTRA Remote. No redirects are followed. TLS uses system CAs only (user CAs are not trusted), and there is no cleartext.
- **Phone storage:**
  - Backup and device transfer are disabled.
  - `FLAG_SECURE`: no screenshots, screen recording or recents thumbnail.
- **Permissions:** the only permission is INTERNET. The QR is read by the Google code scanner UI, so the app needs no camera permission.
- **QR:** `nestra://pair/<PairingId>` identifies the PC's pairing session only. A QR with anything after the id (for example a code) is refused, and the 6-digit code is always typed.
- **Remote Access:** pairing never enables it. It stays OFF until the person at the PC turns it on there.

## Known gaps (0.1.0)

- The Windows Tray shows the QR payload as text, not as an image, so scanning needs a later Tray update. Pairing with the 6-digit code alone works today.
- English UI only; Polish strings come later.
- No recovery-code sign-in: Parent's MFA login contract as proven live has `challengeToken` + `code` only.
- Not production-ready: no release signing, no minification, launcher icon is a placeholder.
