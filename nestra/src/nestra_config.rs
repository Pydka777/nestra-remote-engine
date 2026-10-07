//! NESTRA Remote Engine (AGPL-3.0, part of the public engine fork): fixed server, fixed key, enforced settings.
//!
//! `enforce()` runs at the start of every engine process (patched core_main) and puts the values below into
//! hbb_common's OVERWRITE_SETTINGS, which take priority over the config file, the UI and IPC writes and are never
//! saved. So no user setting, config file, custom-client file or IPC client can point this build at another server,
//! enable a permanent password, click-free approval, audio, file transfer, clipboard, tunnels, terminal or direct
//! IP access.

use hbb_common::config::{NESTRA_RENDEZVOUS_SERVER, OVERWRITE_SETTINGS, RS_PUB_KEY};

pub const HOST: &str = NESTRA_RENDEZVOUS_SERVER;
pub const KEY: &str = RS_PUB_KEY;

/// Server and key: the same for the Windows engine and the Android viewer core.
const SERVER: &[(&str, &str)] = &[
    ("custom-rendezvous-server", NESTRA_RENDEZVOUS_SERVER),
    ("relay-server", NESTRA_RENDEZVOUS_SERVER),
    ("key", RS_PUB_KEY),
];

/// The controlled PC (Windows engine): only the one-time temporary password (= the NESTRA session grant).
#[cfg(not(any(target_os = "android", target_os = "ios")))]
const ENGINE: &[(&str, &str)] = &[
    ("verification-method", "use-temporary-password"),
    ("approve-mode", "password"),
    ("allow-hide-cm", "N"),
    ("enable-audio", "N"),
    ("enable-file-transfer", "N"),
    ("enable-clipboard", "N"),
    ("enable-tunnel", "N"),
    ("enable-terminal", "N"),
    ("enable-camera", "N"),
    ("enable-remote-printer", "N"),
    ("enable-remote-restart", "N"),
    ("enable-record-session", "N"),
    ("allow-remote-config-modification", "N"),
    ("direct-server", "N"),
    ("enable-lan-discovery", "N"),
];

pub fn enforce() {
    let mut o = OVERWRITE_SETTINGS.write().unwrap();
    for (k, v) in SERVER {
        o.insert((*k).to_owned(), (*v).to_owned());
    }
    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    for (k, v) in ENGINE {
        o.insert((*k).to_owned(), (*v).to_owned());
    }
}
