# Changes from upstream RustDesk (AGPL-3.0 section 5a)

Upstream base: rustdesk/rustdesk tag 1.5.0 = commit fada664df7a294d1d1a9ca3e7cd3637069122f17 (2026-09-30),
hbb_common = 229b904508364c8997aad0fb5af57effac859f60. Applied by `nestra/apply_patches.py`; each build ships the
exact diff as `nestra-changes.diff`.

| Date | File | Change |
|---|---|---|
| 2026-10-08 | libs/hbb_common/src/config.rs | APP_NAME `nestra-remote-engine`; built-in rendezvous/relay server `remote.nestraparent.com` and the NESTRA server public key replace the RustDesk public server and key; `get_rendezvous_server(s)` always return the NESTRA host |
| 2026-10-08 | libs/hbb_common/src/password_security.rs | `set_temporary_password_exact()` |
| 2026-10-08 | src/common.rs | `get_key()` always returns the NESTRA key |
| 2026-10-08 | src/lib.rs | new modules `nestra_config`, `nestra_session` (Windows), `nestra_viewer` (Android) |
| 2026-10-08 | src/core_main.rs | `nestra_config::enforce()` in every process; CLI modes `--nestra-session`, `--nestra-close` |
| 2026-10-08 | src/ipc.rs | engine-local IPC keys `nestra-authed` (get), `nestra-grant`, `nestra-close` (set) |
| 2026-10-08 | src/server.rs, src/server/connection.rs | `NESTRA_CLOSE` flag closes authorised connections on the next 1 s tick; `nestra_authed_count()` |
| 2026-10-08 | src/flutter.rs | Android only: decoded frames and UI events are handed to `nestra_viewer` (Surface rendering for the NESTRA app) |
| 2026-10-08 | settings (nestra_config) | enforced via OVERWRITE_SETTINGS: temporary password only, password approval, no hidden connection manager, audio / file transfer / clipboard / tunnel / terminal / camera / printer / remote restart / recording / remote config / direct IP / LAN discovery OFF |
| (later) | branding | name and icons -> NESTRA Remote; "based on RustDesk" kept in About |
