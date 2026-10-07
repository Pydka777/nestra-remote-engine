//! NESTRA Remote Engine (AGPL-3.0, part of the public engine fork): `--nestra-session` (Windows).
//!
//! The documented interface used by the NESTRA Remote Windows service (a separate, independent program):
//!   stdin  line 1: {"v":1,"sessionId":"…","grant":"…","expiresUtc":"…","server":"remote.nestraparent.com","key":"…"}
//!          later:  "end" (or stdin closed) = end the session now
//!   stdout:        NESTRA-SESSION ready <engineId> | NESTRA-SESSION connected | NESTRA-SESSION ended <reason>
//!
//! It talks to the engine's own running service (upstream service model: SYSTEM service + capture server in the
//! console session, so UAC and the lock screen stay visible and controllable) over the engine's local IPC:
//!   "nestra-grant"   the grant becomes the ONE temporary password (verification is temporary-password only, see
//!                    nestra_config). Upstream rotates it right after a successful login: one connection per grant.
//!   "nestra-authed"  number of authorised remote-desktop connections
//!   "nestra-close"   "1" closes every remote connection on its next tick, "0" re-arms
//! Second documented mode: `--nestra-close` (no input) closes every remote connection now and prints
//! "NESTRA-SESSION closed".
//! The grant is never printed, logged or written to disk, and never on a command line.

use hbb_common::log;
use std::io::{BufRead, Write};
use std::sync::mpsc;
use std::time::{Duration, Instant};

fn out(line: &str) {
    println!("NESTRA-SESSION {line}");
    let _ = std::io::stdout().flush();
}

fn field(v: &serde_json::Value, k: &str) -> String {
    v.get(k).and_then(|x| x.as_str()).unwrap_or("").to_owned()
}

fn ipc_set(name: &str, value: &str) -> bool {
    crate::ipc::set_config(name, value.to_owned()).is_ok()
}

fn ipc_get(name: &str) -> Option<String> {
    crate::ipc::get_config(name).ok().flatten()
}

fn valid_grant(g: &str) -> bool {
    g.len() == 43 && g.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

fn valid_session_id(s: &str) -> bool {
    s.len() == 26 && s.bytes().all(|b| b"0123456789abcdefghjkmnpqrstvwxyz".contains(&b))
}

/// Rotates the temporary password away and closes every authorised remote connection (idempotent).
/// Also the documented CLI `--nestra-close`, used by the NESTRA Remote service at start-up, on Remote Access OFF,
/// on service stop and after a crashed session process, so no connection can outlive its authorisation.
pub fn close_all() {
    let _ = crate::ipc::update_temporary_password();
    let _ = ipc_set("nestra-close", "1");
    std::thread::sleep(Duration::from_millis(2500)); // connections close on their next 1 s tick
    let _ = ipc_set("nestra-close", "0");
}

/// `--nestra-close`
pub fn run_close() {
    close_all();
    out("closed");
}

/// Ends the session: grant rotated away, every connection closed, then the end line.
fn finish(reason: &str) -> ! {
    close_all();
    log::info!("NESTRA session ended ({reason})");
    out(&format!("ended {reason}"));
    std::process::exit(0)
}

pub fn run() {
    let mut first = String::new();
    if std::io::stdin().lock().read_line(&mut first).is_err() {
        out("ended engine_failed");
        return;
    }
    let v: serde_json::Value = match serde_json::from_str(first.trim()) {
        Ok(v) => v,
        Err(_) => {
            out("ended engine_failed");
            return;
        }
    };
    first.clear();
    let sid = field(&v, "sessionId");
    let mut grant = field(&v, "grant");
    let expires = chrono::DateTime::parse_from_rfc3339(&field(&v, "expiresUtc")).ok();
    if field(&v, "server") != crate::nestra_config::HOST
        || field(&v, "key") != crate::nestra_config::KEY
        || hbb_common::config::Config::get_rendezvous_server() != crate::nestra_config::HOST
        || !valid_grant(&grant)
        || !valid_session_id(&sid)
        || expires.is_none()
    {
        log::error!("NESTRA session refused: invalid request or server/key mismatch"); // never the grant
        out("ended engine_failed");
        return;
    }
    let left = expires
        .unwrap()
        .signed_duration_since(chrono::Utc::now())
        .to_std()
        .unwrap_or_default();
    if left.is_zero() || left > Duration::from_secs(150) {
        out("ended expired");
        return;
    }
    let deadline = Instant::now() + left;
    let engine_id = crate::ipc::get_id();
    if engine_id.is_empty() || !ipc_set("nestra-close", "0") || !ipc_set("nestra-grant", &grant) {
        grant.replace_range(.., &"0".repeat(grant.len()));
        out("ended engine_failed");
        return;
    }
    grant.replace_range(.., &"0".repeat(grant.len()));
    drop(grant);
    log::info!("NESTRA session {sid}: waiting for one viewer");
    out(&format!("ready {engine_id}"));

    // "end" or EOF on stdin -> end now
    let (tx, rx) = mpsc::channel::<()>();
    std::thread::spawn(move || {
        for line in std::io::stdin().lock().lines() {
            match line {
                Ok(l) if l.trim() != "end" => continue,
                _ => break,
            }
        }
        let _ = tx.send(());
    });

    let mut connected = false;
    loop {
        if rx.recv_timeout(Duration::from_millis(500)).is_ok() {
            finish("local_disconnect");
        }
        let authed: usize = ipc_get("nestra-authed").and_then(|s| s.parse().ok()).unwrap_or(0);
        if !connected && authed > 0 {
            connected = true;
            out("connected");
        } else if connected && authed == 0 {
            finish("viewer_disconnect");
        } else if !connected && Instant::now() >= deadline {
            finish("expired");
        }
    }
}
