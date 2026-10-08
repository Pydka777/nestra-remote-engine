//! NESTRA Remote Engine (AGPL-3.0, part of the public engine fork): fixed server, fixed key, enforced settings.
//!
//! `enforce()` runs at the start of every engine process (patched core_main) and puts the values below into
//! hbb_common's OVERWRITE_SETTINGS, which take priority over the config file, the UI and IPC writes and are never
//! saved. So no user setting, config file, custom-client file or IPC client can point this build at another server,
//! enable a permanent password, click-free approval, tunnels, terminal or direct IP access. ETAP 10 intentionally
//! allows only clipboard, file transfer and audio inside an authenticated owner session.

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
    ("enable-audio", "Y"),
    ("enable-file-transfer", "Y"),
    ("enable-clipboard", "Y"),
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

/// NESTRA Remote secret-handoff diagnostics (ETAP 9): a one-time secret is NEVER logged; only its UTF-8 byte length,
/// the first 8 hex characters of SHA-256 over its UTF-8 bytes (32 of 256 bits: identifies the value, reveals nothing
/// usable) and its character class. The same line format and fingerprint are produced by the API (C#), the Windows
/// agent (C#), the Android app (Kotlin) and this engine, so one grant can be followed through every stage.
/// Test vector: "NESTRA_test-vector_0123456789abcdefghijklmn" -> len=43 fp=4ee44f50 enc=base64url-ascii
pub fn fingerprint(secret: &str) -> String {
    use hbb_common::sha2::{Digest, Sha256};
    let d = Sha256::digest(secret.as_bytes());
    d[..4].iter().map(|b| format!("{b:02x}")).collect()
}

/// `secret stage=<stage> len=<utf8 bytes> fp=<8 hex> enc=<base64url-ascii|ascii-other|non-ascii>`
pub fn secret_diag(stage: &str, secret: &str) -> String {
    let enc = if !secret.is_empty() && secret.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') {
        "base64url-ascii"
    } else if secret.is_ascii() {
        "ascii-other"
    } else {
        "non-ascii"
    };
    format!("secret stage={stage} len={} fp={} enc={enc}", secret.len(), fingerprint(secret))
}

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

#[cfg(test)]
mod tests {
    // the same vector is asserted by the API/agent (C# SecretDiag) and the Android app (Kotlin SecretDiag)
    #[test]
    fn secret_diag_matches_the_cross_language_vector() {
        assert_eq!(
            super::secret_diag("t", "NESTRA_test-vector_0123456789abcdefghijklmn"),
            "secret stage=t len=43 fp=4ee44f50 enc=base64url-ascii"
        );
        assert_eq!(
            super::secret_diag("t", "NESTRA_test-vector_0123456789abcdefghijklmn\n"),
            "secret stage=t len=44 fp=4909e47f enc=ascii-other"
        );
        assert_eq!(super::secret_diag("t", "\u{e9}"), "secret stage=t len=2 fp=4a99557e enc=non-ascii");
    }
}
