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
//!                    ACKNOWLEDGED: the engine service answers with the fingerprint of the password it now holds; no
//!                    answer or a different fingerprint = the grant is NOT installed -> "ended engine_failed".
//!   "nestra-authed"  number of authorised remote-desktop connections
//!   "nestra-close"   "1" rotates the temporary password away and closes every remote connection on its next tick,
//!                    "0" re-arms; ACKNOWLEDGED (the value is echoed back)
//! Second documented mode: `--nestra-close` (no input) closes every remote connection now and prints
//! "NESTRA-SESSION closed" only when the engine service confirmed it ("NESTRA-SESSION close_failed" otherwise).
//! The grant is never printed, logged or written to disk, and never on a command line. Diagnostics carry only
//! `secret stage=… len=… fp=… enc=…` (nestra_config::secret_diag) as "NESTRA-SESSION diag …" lines.
//!
//! The engine service only answers IPC from the SAME executable (upstream 1.5.0 ipc/auth.rs: peer executable path
//! must match), so this mode must be started from the installed engine (the engine service's own exe), not a copy.
//! Before v0.2.1-engine the writes were fire-and-forget: from a copy they were silently dropped, "ready" was still
//! printed, and every viewer got "Wrong Password" (ETAP 9 device test, 2026-10-08).

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

/// Writes an engine IPC key and waits for the engine service's answer (None = not delivered / not answered).
fn ipc_set_ack(name: &str, value: &str) -> Option<String> {
    let rt = hbb_common::tokio::runtime::Builder::new_current_thread().enable_all().build().ok()?;
    rt.block_on(async {
        let mut c = crate::ipc::connect(1000, "").await.ok()?;
        c.send(&crate::ipc::Data::Config((name.to_owned(), Some(value.to_owned())))).await.ok()?;
        match c.next_timeout(3000).await {
            Ok(Some(crate::ipc::Data::Config((n, Some(v))))) if n == name => Some(v),
            _ => None,
        }
    })
}

fn diag(line: &str) {
    log::info!("NESTRA {line}");
    out(&format!("diag {line}"));
}

fn ipc_get(name: &str) -> Option<String> {
    crate::ipc::get_config(name).ok().flatten()
}

/// An answer is shown only if it looks like a fingerprint (8 hex).
fn redact_fp(s: &str) -> &str {
    if s.len() == 8 && s.bytes().all(|b| b.is_ascii_hexdigit()) { s } else { "(invalid)" }
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
/// True only when the engine service confirmed both steps.
pub fn close_all() -> bool {
    let closed = ipc_set_ack("nestra-close", "1").as_deref() == Some("1"); // rotates + closes (engine service side)
    std::thread::sleep(Duration::from_millis(2500)); // connections close on their next 1 s tick
    let rearmed = ipc_set_ack("nestra-close", "0").as_deref() == Some("0");
    if !(closed && rearmed) {
        log::error!("NESTRA close NOT confirmed by the engine service (closed={closed}, rearmed={rearmed})");
    }
    closed && rearmed
}

/// `--nestra-close`
pub fn run_close() {
    if close_all() {
        out("closed");
    } else {
        diag("ipc stage=engine_close result=unanswered hint=not_engine_service_exe");
        out("close_failed");
    }
}

/// Ends the session: grant rotated away, every connection closed, then the end line.
fn finish(reason: &str) -> ! {
    if !close_all() {
        diag("ipc stage=engine_close result=unanswered");
    }
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
    diag(&crate::nestra_config::secret_diag("engine_stdin", &grant));
    let expected = crate::nestra_config::fingerprint(&grant);
    // the engine ID from the engine service itself (no fallback to this process's own config)
    let engine_id = ipc_get("id").unwrap_or_default();
    let rearmed = ipc_set_ack("nestra-close", "0").as_deref() == Some("0");
    let installed = if rearmed { ipc_set_ack("nestra-grant", &grant) } else { None };
    grant.replace_range(.., &"0".repeat(grant.len()));
    drop(grant);
    match installed.as_deref() {
        Some(fp) if fp == expected => diag(&format!("secret stage=engine_installed fp={fp} result=match")),
        Some(fp) => {
            diag(&format!("secret stage=engine_installed fp={} expected={expected} result=MISMATCH", redact_fp(fp)));
            finish("engine_failed");
        }
        None => {
            diag("secret stage=engine_installed result=unanswered hint=not_engine_service_exe");
            out("ended engine_failed");
            return;
        }
    }
    if engine_id.is_empty() || !engine_id.bytes().all(|b| b.is_ascii_digit()) {
        diag("ipc stage=engine_id result=unanswered");
        finish("engine_failed");
    }
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
