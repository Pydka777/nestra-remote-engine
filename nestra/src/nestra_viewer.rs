//! NESTRA Remote viewer core for Android (AGPL-3.0, part of librustdesk in the public engine fork).
//!
//! JNI contract with com.nestra.remote.viewer.NativeViewer (NESTRA Remote Android app; ABI 3):
//!   int  nativeAbiVersion()
//!   void nativeInit(String appDir)                     // once per process: upstream main_init + NESTRA settings
//!   long nativeConnect(String engineId, String host, String key, char[] grant, Callback cb)   // 0 = refused
//!   void nativeSetSurface(long h, Surface s)          // frames of display 0 are drawn here (RGBA_8888)
//!   void nativeMouse(long h, int kind, int x, int y, int button, int delta)   // kind: 0 move 1 down 2 up 3 wheel
//!   void nativeSwitchDisplay(long h, int display)
//!   void nativeSendClipboard(long h, String text)
//!   void nativeToggleAudio(long h)
//!   void nativeReadRemoteDir(long h, String path)
//!   int  nativeTransferFile(long h, String from, String to, boolean remoteToLocal)
//!   void nativeKey(long h, int androidKeyCode, boolean down)
//!   void nativeText(long h, String text)
//!   void nativeClose(long h)
//!   Callback: onConnected, onResolution, onDisplays, onClipboard, onClosed
//!
//! Built on the upstream Flutter session (FlutterHandler implements the UI trait): session_add + io_loop, with two
//! hooks in flutter.rs - decoded frames come to `on_frame` (drawn into the app's Surface, never stored), UI events
//! to `on_event`. Both hooks carry the FlutterHandler's owner token, so events of an older (closing) session can never
//! act on the current one. The grant is the session password: used once, zeroed here, never logged. Host and key are
//! checked against the compiled-in NESTRA values (no server override). One session at a time.
//!
//! v0.2.1 (ETAP 9 Android disconnect diagnosis):
//!  - every step is written to logcat with the tag NESTRA-VIEWER (and to the RustDesk log file); IDs are shortened, long
//!    tokens are redacted; the grant / temporary password, account tokens and keys are never logged;
//!  - a session generation guards every close: the io_loop thread of an older session can no longer close a newer one;
//!  - connection establishment errors are handled like upstream's own UI: a retryable "Connection Error" (hasRetry) or a
//!    relay hint before login is retried (bounded, MAX_RETRIES; relay hint -> forced relay) instead of ending the session
//!    at the first transient failure; login failures and non-retryable errors still close at once;
//!  - an insecure (non end-to-end encrypted) connection is never accepted: closed with reason insecure_connection.
//!  - a missing Surface (recreated SurfaceView, app in background, configuration change) never closes the session.
//! v0.2.2 (ETAP 9 secret handoff): `secret stage=jni_receive|jni_login len= fp= enc=` (+ last_pw=) lines (fingerprint =
//!    first 8 hex of SHA-256, nestra_config::secret_diag) so the grant can be matched with the API, agent and engine.

use crate::client::FileManager;
use crate::flutter_ffi::SessionID;
use crate::input::{MOUSE_BUTTON_LEFT, MOUSE_BUTTON_RIGHT, MOUSE_TYPE_DOWN, MOUSE_TYPE_UP, MOUSE_TYPE_WHEEL};
use base::message_proto::*;
use hbb_common::log;
use jni::objects::{GlobalRef, JCharArray, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong};
use jni::{JNIEnv, JavaVM};
use std::os::raw::{c_char, c_int, c_void};
use std::sync::atomic::{AtomicI32, AtomicU64, Ordering};
use std::sync::{Mutex, Once};
use std::time::Duration;

const ABI: jint = 3;
const HANDLE_BASE: jlong = 0x4e52_0000_0000; // | generation: a stale handle never reaches a newer session
const MAX_RETRIES: u32 = 2;
const ERROR_GRACE: Duration = Duration::from_secs(8); // an error msgbox that does not end io_loop closes after this

#[repr(C)]
struct ANativeWindowBuffer {
    width: i32,
    height: i32,
    stride: i32,
    format: i32,
    bits: *mut c_void,
    reserved: [u32; 6],
}

#[link(name = "android")]
extern "C" {
    fn ANativeWindow_fromSurface(env: *mut jni::sys::JNIEnv, surface: jni::sys::jobject) -> *mut c_void;
    fn ANativeWindow_release(window: *mut c_void);
    fn ANativeWindow_setBuffersGeometry(window: *mut c_void, w: i32, h: i32, format: i32) -> i32;
    fn ANativeWindow_lock(window: *mut c_void, buffer: *mut ANativeWindowBuffer, dirty: *mut c_void) -> i32;
    fn ANativeWindow_unlockAndPost(window: *mut c_void) -> i32;
}
#[link(name = "log")]
extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}
const WINDOW_FORMAT_RGBA_8888: i32 = 1;
const LOG_INFO: c_int = 4;
const LOG_WARN: c_int = 5;
const LOG_ERROR: c_int = 6;

// ------------------------------------------------------------------------------------------------ diagnostics
/// Shortens IDs (digit runs of 8+ -> "...123") and redacts every long token-like run (24+ base64/base64url/hex
/// characters: grants, temporary passwords, tokens, keys). Applied to EVERY diagnostic line.
fn redact(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut run = String::new();
    let flush = |run: &mut String, out: &mut String| {
        if run.len() >= 24 {
            out.push_str("<redacted>");
        } else if out.ends_with("fp=") || out.ends_with("expected=") {
            out.push_str(run); // a fingerprint (8 hex) is shown as is, even when it happens to be all digits
        } else if run.len() >= 8 && run.bytes().all(|b| b.is_ascii_digit()) {
            out.push_str("...");
            out.push_str(&run[run.len() - 3..]);
        } else {
            out.push_str(run);
        }
        run.clear();
    };
    for c in s.chars() {
        if c.is_ascii_alphanumeric() || c == '+' || c == '/' || c == '=' || c == '_' || c == '-' {
            run.push(c);
        } else {
            flush(&mut run, &mut out);
            out.push(c);
        }
    }
    flush(&mut run, &mut out);
    out.chars().take(400).collect()
}

fn diag(prio: c_int, msg: &str) {
    let line = redact(msg);
    log::info!("NESTRA-VIEWER {line}");
    let tag = b"NESTRA-VIEWER\0";
    if let Ok(text) = std::ffi::CString::new(line.replace('\0', " ")) {
        unsafe { __android_log_write(prio, tag.as_ptr() as *const c_char, text.as_ptr()) };
    }
}
fn info(msg: &str) {
    diag(LOG_INFO, msg)
}
fn warn(msg: &str) {
    diag(LOG_WARN, msg)
}

// ------------------------------------------------------------------------------------------------ state
struct Viewer {
    gen: u64,
    token: usize, // the FlutterHandler owner token of THIS session (see flutter.rs nestra_token)
    session_id: SessionID,
    session: crate::flutter::FlutterSession,
    window: usize, // *mut ANativeWindow
    cb: GlobalRef,
    vm: usize, // *mut jni::sys::JavaVM
    size: (usize, usize),
    current_display: usize,
    display_count: usize, // last count delivered to Android; suppress duplicate sync_peer_info storms
    connected: bool, // first frame drawn
    logged_in: bool, // peer_info received (the PC accepted the grant)
    closing: bool,   // the app asked to close: no onClosed callback
    attempts: u32,
    retry: Option<bool>, // Some(force_relay): io_loop ended with a retryable error, reconnect
    last_error: Option<&'static str>,
}

lazy_static::lazy_static! {
    static ref VIEWER: Mutex<Option<Viewer>> = Mutex::new(None);
}
static INIT: Once = Once::new();
static GEN: AtomicU64 = AtomicU64::new(0);
static FILE_JOB_ID: AtomicI32 = AtomicI32::new(1000);

fn jvm(ptr: usize) -> Option<JavaVM> {
    unsafe { JavaVM::from_raw(ptr as *mut jni::sys::JavaVM) }.ok()
}

fn callback(vm: usize, cb: &GlobalRef, name: &str, sig: &str, args: &[JValue]) {
    let Some(vm) = jvm(vm) else { return };
    if let Ok(mut env) = vm.attach_current_thread_as_daemon() {
        let _ = env.call_method(cb.as_obj(), name, sig, args);
    }
}

/// Ends the viewer of generation `gen` (and only that one). Idempotent.
fn closed(gen: u64, reason: &str) {
    let v = {
        let mut lock = VIEWER.lock().unwrap();
        match lock.as_ref() {
            Some(v) if v.gen == gen => lock.take(),
            _ => None,
        }
    };
    let Some(v) = v else {
        info(&format!("close({reason}) for gen {gen} ignored: not the current session (already closed or newer session)"));
        return;
    };
    info(&format!(
        "native close: gen {gen} reason={reason} by_app={} logged_in={} first_frame={} attempts={}",
        v.closing, v.logged_in, v.connected, v.attempts
    ));
    if v.window != 0 {
        unsafe { ANativeWindow_release(v.window as *mut c_void) };
    }
    crate::flutter_ffi::session_close(v.session_id);
    if !v.closing {
        info(&format!("onClosed({reason}) -> app"));
        if let Some(vm) = jvm(v.vm) {
            if let Ok(mut env) = vm.attach_current_thread_as_daemon() {
                if let Ok(r) = env.new_string(reason) {
                    let _ = env.call_method(v.cb.as_obj(), "onClosed", "(Ljava/lang/String;)V", &[JValue::Object(&*r)]);
                }
            }
        }
    }
}

/// io_loop driver of one generation: runs, and on a retryable establishment error runs again (bounded).
fn drive(gen: u64) {
    loop {
        let (s, attempt, force_relay) = {
            let lock = VIEWER.lock().unwrap();
            match lock.as_ref() {
                Some(v) if v.gen == gen && !v.closing => {
                    ((*v.session).clone(), v.attempts, v.session.lc.read().unwrap().force_relay)
                }
                _ => return,
            }
        };
        let round = s.connection_round_state.lock().unwrap().new_round();
        info(&format!("io_loop start: gen {gen} attempt {attempt} round {round} force_relay={force_relay} (rendezvous + punch hole / relay)"));
        crate::ui_session_interface::io_loop(s, round);
        let next: Result<bool, &'static str> = {
            let mut lock = VIEWER.lock().unwrap();
            match lock.as_mut() {
                Some(v) if v.gen == gen && !v.closing => match v.retry.take() {
                    Some(fr) if v.attempts < MAX_RETRIES && !v.logged_in => {
                        v.attempts += 1;
                        v.last_error = None;
                        Ok(fr)
                    }
                    _ => Err(v.last_error.unwrap_or("engine_closed")),
                },
                _ => {
                    info(&format!("io_loop ended: gen {gen} (session already closed or replaced)"));
                    return;
                }
            }
        };
        match next {
            Ok(fr) => {
                if fr {
                    let lock = VIEWER.lock().unwrap();
                    if let Some(v) = lock.as_ref().filter(|v| v.gen == gen) {
                        let mut lc = v.session.lc.write().unwrap();
                        lc.force_relay = true;
                        lc.policy_relay = true;
                        lc.peer_relay = true;
                    }
                }
                warn(&format!("io_loop ended with a retryable error: gen {gen} retrying (force_relay={fr})"));
                std::thread::sleep(Duration::from_secs(1));
            }
            Err(reason) => {
                info(&format!("io_loop ended: gen {gen} -> {reason}"));
                closed(gen, reason);
                return;
            }
        }
    }
}

/// Hook from FlutterHandler::on_rgba_soft_render. true = frame consumed by the NESTRA viewer.
pub fn on_frame(token: usize, display: usize, rgba: &scrap::ImageRgb) -> bool {
    let mut lock = VIEWER.lock().unwrap();
    let Some(v) = lock.as_mut() else { return false };
    if v.token != token {
        return false; // not our session's handler
    }
    if display != v.current_display || rgba.w == 0 || rgba.h == 0 {
        return true;
    }
    let (w, h) = (rgba.w, rgba.h);
    let first = !v.connected;
    let resized = v.size != (w, h);
    v.connected = true;
    v.size = (w, h);
    if v.window != 0 {
        let win = v.window as *mut c_void;
        let src_stride = (w * 4 + rgba.align.max(1) - 1) & !(rgba.align.max(1) - 1);
        // upstream on Android decodes to ImageFormat::ARGB = B,G,R,A in memory; ABGR = R,G,B,A
        let swap = matches!(rgba.fmt, scrap::ImageFormat::ARGB);
        unsafe {
            ANativeWindow_setBuffersGeometry(win, w as i32, h as i32, WINDOW_FORMAT_RGBA_8888);
            let mut buf: ANativeWindowBuffer = std::mem::zeroed();
            if ANativeWindow_lock(win, &mut buf, std::ptr::null_mut()) == 0 {
                let dst_stride = buf.stride as usize * 4;
                let rows = h.min(buf.height as usize);
                let cols = w.min(buf.width as usize);
                let dst = buf.bits as *mut u8;
                for y in 0..rows {
                    let s = &rgba.raw[y * src_stride..y * src_stride + cols * 4];
                    let d = std::slice::from_raw_parts_mut(dst.add(y * dst_stride), cols * 4);
                    if swap {
                        for (dp, sp) in d.chunks_exact_mut(4).zip(s.chunks_exact(4)) {
                            dp[0] = sp[2];
                            dp[1] = sp[1];
                            dp[2] = sp[0];
                            dp[3] = 255;
                        }
                    } else {
                        d.copy_from_slice(s);
                    }
                }
                ANativeWindow_unlockAndPost(win);
            }
        }
    }
    let (vm, cb, gen, has_window) = (v.vm, v.cb.clone(), v.gen, v.window != 0);
    drop(lock);
    if first {
        info(&format!("first video frame: gen {gen} {w}x{h} surface={has_window} -> onConnected"));
        callback(vm, &cb, "onConnected", "(II)V", &[JValue::Int(w as i32), JValue::Int(h as i32)]);
    } else if resized {
        info(&format!("resolution changed: {w}x{h}"));
        callback(vm, &cb, "onResolution", "(II)V", &[JValue::Int(w as i32), JValue::Int(h as i32)]);
    }
    true
}

fn field<'a>(e: &'a serde_json::Value, k: &str) -> &'a str {
    e.get(k).and_then(|x| x.as_str()).unwrap_or("")
}

/// Hook from FlutterHandler::push_event_ (JSON with "name").
/// Called by the patched client.rs handle_hash (Android) right before the login hash is built from the preset
/// password (= the grant): fingerprint only. `last_password_set` = an older "last password" that would take
/// precedence over the grant in upstream's application order (never expected for a fresh NESTRA session).
pub fn on_login_secret(preset: &str, last_password_set: bool) {
    info(&format!(
        "{} last_pw={last_password_set}",
        crate::nestra_config::secret_diag("jni_login", preset)
    ));
}

pub fn on_event(token: usize, json: &str) {
    let Ok(e) = serde_json::from_str::<serde_json::Value>(json) else { return };
    let name = field(&e, "name");
    let (session, gen) = match VIEWER.lock().unwrap().as_ref() {
        Some(v) if v.token == token => (v.session.clone(), v.gen),
        Some(_) => {
            info(&format!("event '{name}' from an older session ignored"));
            return;
        }
        None => return,
    };
    match name {
        // the PC accepted the grant (login ok): ask for display 0 (what upstream's UI does on start)
        "peer_info" => {
            let first_login = {
                let mut lock = VIEWER.lock().unwrap();
                match lock.as_mut().filter(|v| v.gen == gen) {
                    Some(v) if !v.logged_in => { v.logged_in = true; true }
                    Some(_) => false,
                    None => return,
                }
            };
            if !first_login {
                // Upstream may emit peer_info again after switch_display/refresh_video. Re-running those calls here
                // creates a feedback loop (peer_info -> switch_display -> peer_info) and can starve the first frame.
                info(&format!("duplicate peer_info ignored for gen {gen}"));
                return;
            }
            info(&format!("peer_info: gen {gen} logged in (grant accepted by the PC); requesting display 0 once"));
            let displays = field(&e, "displays");
            if let Ok(list) = serde_json::from_str::<Vec<serde_json::Value>>(displays) {
                let count = list.len().max(1);
                let target = {
                    let mut lock = VIEWER.lock().unwrap();
                    lock.as_mut().filter(|v| v.gen == gen).map(|v| {
                        v.display_count = count;
                        (v.vm, v.cb.clone())
                    })
                };
                if let Some((vm, cb)) = target {
                    callback(vm, &cb, "onDisplays", "(I)V", &[JValue::Int(count as jint)]);
                }
                info(&format!("initial display list: {count} display(s)"));
            }
            if session.get_toggle_option("disable-audio".to_owned()) {
                session.toggle_option("disable-audio".to_owned());
                info("audio restored ON for NESTRA owner session");
            }
            session.switch_display(0);
            session.refresh_video(0);
        }
        "sync_peer_info" => {
            let displays = field(&e, "displays");
            if let Ok(list) = serde_json::from_str::<Vec<serde_json::Value>>(displays) {
                let count = list.len().max(1);
                let target = {
                    let mut lock = VIEWER.lock().unwrap();
                    match lock.as_mut().filter(|v| v.gen == gen) {
                        Some(v) if v.display_count != count => {
                            v.display_count = count;
                            Some((v.vm, v.cb.clone()))
                        }
                        _ => None,
                    }
                };
                if let Some((vm, cb)) = target {
                    callback(vm, &cb, "onDisplays", "(I)V", &[JValue::Int(count as jint)]);
                    info(&format!("display list changed: {count} display(s)"));
                }
            }
        }
        "clipboard" => {
            let content = field(&e, "content");
            if content.len() <= 1_000_000 {
                let target = VIEWER.lock().unwrap().as_ref().filter(|v| v.gen == gen).map(|v| (v.vm, v.cb.clone()));
                if let Some((vm, cb)) = target {
                    if let Some(jvm) = jvm(vm) {
                        if let Ok(mut env) = jvm.attach_current_thread_as_daemon() {
                            if let Ok(s) = env.new_string(content) {
                                let _ = env.call_method(cb.as_obj(), "onClipboard", "(Ljava/lang/String;)V", &[JValue::Object(&*s)]);
                            }
                        }
                    }
                }
                info(&format!("clipboard received: {} UTF-8 bytes", content.len()));
            } else {
                warn("clipboard received but refused: content too large");
            }
        }
        "file_dir" | "empty_dirs" | "job_progress" | "job_done" | "job_error" => {
            let target = VIEWER.lock().unwrap().as_ref().filter(|v| v.gen == gen).map(|v| (v.vm, v.cb.clone()));
            if let Some((vm, cb)) = target {
                if let Some(jvm) = jvm(vm) {
                    if let Ok(mut env) = jvm.attach_current_thread_as_daemon() {
                        if let (Ok(jname), Ok(jjson)) = (env.new_string(name), env.new_string(json)) {
                            let _ = env.call_method(
                                cb.as_obj(),
                                "onFileEvent",
                                "(Ljava/lang/String;Ljava/lang/String;)V",
                                &[JValue::Object(&*jname), JValue::Object(&*jjson)],
                            );
                        }
                    }
                }
            }
            info(&format!("file event {name}"));
        }
        "connection_ready" => {
            // transport chosen by the client: secure (E2EE), direct (P2P) or relay, stream type (TCP/UDP/WebRTC)
            info(&format!("connection_ready: {}", json.chars().take(200).collect::<String>()));
        }
        "msgbox" => {
            let (t, title, text, retry) = (field(&e, "type"), field(&e, "title"), field(&e, "text"), field(&e, "hasRetry") == "true");
            warn(&format!("msgbox type='{t}' title='{title}' text='{text}' hasRetry={retry} (gen {gen})"));
            let login = matches!(
                t,
                "input-password" | "re-input-password" | "session-login" | "session-re-login" | "session-login-password"
                    | "session-login-other-password"
            );
            if login {
                std::thread::spawn(move || closed(gen, "login_failed"));
            } else if t.starts_with("insecure-connection") {
                // no end-to-end encryption: never accepted for a NESTRA session
                std::thread::spawn(move || closed(gen, "insecure_connection"));
            } else if t == "error" || t == "relay-hint" || t == "relay-hint2" {
                let relay = t != "error";
                let a0 = {
                    let mut lock = VIEWER.lock().unwrap();
                    let Some(v) = lock.as_mut().filter(|v| v.gen == gen) else { return };
                    v.last_error = Some("connection_error");
                    if (retry || relay) && !v.logged_in && !v.connected && v.attempts < MAX_RETRIES {
                        v.retry = Some(relay);
                        info(&format!("establishment error is retryable: will reconnect (attempt {} of {MAX_RETRIES}, relay={relay})", v.attempts + 1));
                    } else {
                        v.retry = None;
                    }
                    v.attempts
                };
                // an error that does not end io_loop (e.g. "No displays") must still end the session
                std::thread::spawn(move || {
                    std::thread::sleep(ERROR_GRACE);
                    let stuck = matches!(VIEWER.lock().unwrap().as_ref(),
                        Some(v) if v.gen == gen && v.attempts == a0 && v.retry.is_none() && v.last_error.is_some() && !v.connected);
                    if stuck {
                        warn(&format!("error msgbox did not end the connection within {}s: closing", ERROR_GRACE.as_secs()));
                        closed(gen, "connection_error");
                    }
                });
            }
        }
        "close" | "connection_closed" => info(&format!("event '{name}' (gen {gen})")),
        n if n.contains("cursor") || n.contains("quality") || n.contains("rgba") || n.contains("texture") => {}
        n => info(&format!("event '{n}' (gen {gen})")),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nestra_remote_viewer_NativeViewer_nativeAbiVersion(_e: JNIEnv, _c: JClass) -> jint {
    ABI
}

#[no_mangle]
pub extern "system" fn Java_com_nestra_remote_viewer_NativeViewer_nativeInit(mut env: JNIEnv, _c: JClass, dir: JString) {
    let dir: String = env.get_string(&dir).map(Into::into).unwrap_or_default();
    INIT.call_once(|| {
        crate::flutter_ffi::main_init(dir, "".to_owned());
        crate::nestra_config::enforce();
        info("nativeInit: RustDesk core initialised, NESTRA server/key enforced");
    });
}

#[no_mangle]
pub extern "system" fn Java_com_nestra_remote_viewer_NativeViewer_nativeConnect(
    mut env: JNIEnv,
    _c: JClass,
    engine: JString,
    host: JString,
    key: JString,
    grant: JCharArray,
    cb: JObject,
) -> jlong {
    let engine: String = env.get_string(&engine).map(Into::into).unwrap_or_default();
    let host: String = env.get_string(&host).map(Into::into).unwrap_or_default();
    let key: String = env.get_string(&key).map(Into::into).unwrap_or_default();
    let refuse = |why: &str| {
        warn(&format!("nativeConnect refused: {why}"));
        0
    };
    if !INIT.is_completed() {
        return refuse("nativeInit not done");
    }
    if host != crate::nestra_config::HOST || key != crate::nestra_config::KEY {
        return refuse("rendezvous host or server key is not NESTRA's");
    }
    if engine.is_empty() || !engine.bytes().all(|b| b.is_ascii_digit()) {
        return refuse("engine ID is not numeric");
    }
    if let Some(v) = VIEWER.lock().unwrap().as_ref() {
        return refuse(&format!("a session is still open (gen {}, closing={})", v.gen, v.closing));
    }
    let len = env.get_array_length(&grant).unwrap_or(0).max(0) as usize;
    let mut buf = vec![0u16; len];
    if len == 0 || env.get_char_array_region(&grant, 0, &mut buf).is_err() {
        return refuse("no grant");
    }
    let mut password = String::from_utf16_lossy(&buf);
    buf.iter_mut().for_each(|c| *c = 0);
    let (Ok(vm), Ok(cb)) = (env.get_java_vm(), env.new_global_ref(cb)) else { return refuse("JNI references") };
    let vm = vm.get_java_vm_pointer() as usize;
    let session_id = uuid::Uuid::new_v4();
    info(&format!("nativeConnect: engine {engine}, grant in memory (not logged); session_add"));
    info(&crate::nestra_config::secret_diag("jni_receive", &password));
    let added = crate::flutter::session_add(
        &session_id, &engine, false, false, false, false, false, "", false, password.clone(), false, None,
    );
    // zero our copies of the grant (the session keeps it only until login; upstream rotates it on the PC side)
    unsafe { password.as_bytes_mut().iter_mut().for_each(|b| *b = b'0') };
    let session = match added {
        Ok(s) => s,
        Err(e) => return refuse(&format!("session_add failed: {e}")),
    };
    let gen = GEN.fetch_add(1, Ordering::SeqCst) + 1;
    let token = session.ui_handler.nestra_token();
    *VIEWER.lock().unwrap() = Some(Viewer {
        gen,
        token,
        session_id,
        session,
        window: 0,
        cb,
        vm,
        size: (0, 0),
        current_display: 0,
        display_count: 0,
        connected: false,
        logged_in: false,
        closing: false,
        attempts: 0,
        retry: None,
        last_error: None,
    });
    std::thread::spawn(move || drive(gen));
    info(&format!("session_add ok: gen {gen}; io_loop thread started"));
    HANDLE_BASE | gen as jlong
}

fn gen_of(h: jlong) -> Option<u64> {
    if h & HANDLE_BASE == HANDLE_BASE && h != HANDLE_BASE {
        Some((h & !HANDLE_BASE) as u64)
    } else {
        None
    }
}

fn with_session(h: jlong, f: impl FnOnce(&crate::flutter::FlutterSession)) -> bool {
    let Some(gen) = gen_of(h) else { return false };
    let s = VIEWER.lock().unwrap().as_ref().filter(|v| v.gen == gen && !v.closing).map(|v| v.session.clone());
    if let Some(s) = s {
        f(&s);
        true
    } else {
        false
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nestra_remote_viewer_NativeViewer_nativeSetSurface(env: JNIEnv, _c: JClass, h: jlong, surface: JObject) {
    let Some(gen) = gen_of(h) else { return };
    let new = if surface.is_null() {
        0
    } else {
        unsafe { ANativeWindow_fromSurface(env.get_raw(), surface.as_raw()) as usize }
    };
    let mut lock = VIEWER.lock().unwrap();
    match lock.as_mut().filter(|v| v.gen == gen) {
        Some(v) => {
            if v.window != 0 {
                unsafe { ANativeWindow_release(v.window as *mut c_void) };
            }
            v.window = new;
            // a missing Surface only pauses drawing: the session stays open (never a disconnect)
            info(&format!("surface {} (gen {gen}); session stays open", if new != 0 { "attached" } else { "detached" }));
        }
        None => {
            if new != 0 {
                unsafe { ANativeWindow_release(new as *mut c_void) };
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nestra_remote_viewer_NativeViewer_nativeMouse(
    _e: JNIEnv,
    _c: JClass,
    h: jlong,
    kind: jint,
    x: jint,
    y: jint,
    button: jint,
    delta: jint,
) {
    let btn = if button == 2 { MOUSE_BUTTON_RIGHT } else { MOUSE_BUTTON_LEFT };
    with_session(h, |s| match kind {
        0 => s.send_mouse(0, x, y, false, false, false, false),
        1 => s.send_mouse(MOUSE_TYPE_DOWN | (btn << 3), x, y, false, false, false, false),
        2 => s.send_mouse(MOUSE_TYPE_UP | (btn << 3), x, y, false, false, false, false),
        3 => s.send_mouse(MOUSE_TYPE_WHEEL, 0, delta, false, false, false, false),
        _ => {}
    });
}

#[no_mangle]
pub extern "system" fn Java_com_nestra_remote_viewer_NativeViewer_nativeSwitchDisplay(_e: JNIEnv, _c: JClass, h: jlong, display: jint) {
    if !(0..16).contains(&display) { return; }
    let Some(gen) = gen_of(h) else { return };
    let session = {
        let mut lock = VIEWER.lock().unwrap();
        let Some(v) = lock.as_mut().filter(|v| v.gen == gen) else { return };
        v.current_display = display as usize;
        v.size = (0, 0); // force onResolution for the selected monitor's first frame
        v.session.clone()
    };
    session.switch_display(display);
    session.refresh_video(display);
    info(&format!("switch display -> {display}"));
}

#[no_mangle]
pub extern "system" fn Java_com_nestra_remote_viewer_NativeViewer_nativeSendClipboard(mut env: JNIEnv, _c: JClass, h: jlong, text: JString) {
    let Some(gen) = gen_of(h) else { return };
    if !matches!(VIEWER.lock().unwrap().as_ref(), Some(v) if v.gen == gen && !v.closing) {
        return; // stale handle must never inject clipboard into a newer session via the global upstream helper
    }
    let Ok(t) = env.get_string(&text) else { return };
    let t: String = t.into();
    if t.is_empty() || t.len() > 1_000_000 { return; }
    let mut msg = Message::new();
    msg.set_clipboard(Clipboard { content: t.as_bytes().to_vec().into(), ..Default::default() });
    crate::flutter::send_clipboard_msg(msg, false);
    info(&format!("clipboard sent: {} UTF-8 bytes", t.len()));
}

#[no_mangle]
pub extern "system" fn Java_com_nestra_remote_viewer_NativeViewer_nativeToggleAudio(_e: JNIEnv, _c: JClass, h: jlong) {
    with_session(h, |s| s.toggle_option("disable-audio".to_owned()));
    info("audio option toggled");
}

#[no_mangle]
pub extern "system" fn Java_com_nestra_remote_viewer_NativeViewer_nativeReadRemoteDir(mut env: JNIEnv, _c: JClass, h: jlong, path: JString) {
    let Ok(path) = env.get_string(&path) else { return };
    let path: String = path.into();
    let safe_path = if path.is_empty() { "<PC home>" } else { "<selected directory>" };
    info(&format!("file directory request: {safe_path}"));
    if with_session(h, |s| s.read_remote_dir(path, false)) {
        info("file directory request handed to RustDesk session");
    } else {
        warn("file directory request rejected: no active native session");
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nestra_remote_viewer_NativeViewer_nativeTransferFile(
    mut env: JNIEnv,
    _c: JClass,
    h: jlong,
    from: JString,
    to: JString,
    remote_to_local: jboolean,
) -> jint {
    let Ok(from) = env.get_string(&from) else { return -1 };
    let Ok(to) = env.get_string(&to) else { return -1 };
    let from: String = from.into();
    let to: String = to.into();
    if from.is_empty() || to.is_empty() { return -1; }
    let id = FILE_JOB_ID.fetch_add(1, Ordering::Relaxed).max(1000);
    if !with_session(h, |s| s.send_files(id, 0, from, to, 0, false, remote_to_local != 0)) {
        return -1;
    }
    info(&format!("file transfer started: job={id} direction={}", if remote_to_local != 0 { "download" } else { "upload" }));
    id
}

#[no_mangle]
pub extern "system" fn Java_com_nestra_remote_viewer_NativeViewer_nativeKey(_e: JNIEnv, _c: JClass, h: jlong, code: jint, down: jboolean) {
    // android.view.KeyEvent codes -> upstream key names (as the upstream mobile UI sends them)
    let name = match code {
        67 => "VK_BACK",
        66 => "VK_RETURN",
        111 => "VK_ESCAPE",
        61 => "VK_TAB",
        21 => "VK_LEFT",
        22 => "VK_RIGHT",
        19 => "VK_UP",
        20 => "VK_DOWN",
        112 => "VK_DELETE",
        _ => return,
    };
    if down == 0 {
        return;
    }
    with_session(h, |s| s.input_key(name, true, true, false, false, false, false));
}

#[no_mangle]
pub extern "system" fn Java_com_nestra_remote_viewer_NativeViewer_nativeText(mut env: JNIEnv, _c: JClass, h: jlong, text: JString) {
    let Ok(t) = env.get_string(&text) else { return };
    let t: String = t.into();
    with_session(h, |s| s.input_string(&t));
}

#[no_mangle]
pub extern "system" fn Java_com_nestra_remote_viewer_NativeViewer_nativeClose(_e: JNIEnv, _c: JClass, h: jlong) {
    let Some(gen) = gen_of(h) else { return };
    if let Some(v) = VIEWER.lock().unwrap().as_mut().filter(|v| v.gen == gen) {
        v.closing = true; // the app asked: no onClosed callback
    }
    info(&format!("nativeClose called by the app (gen {gen})"));
    closed(gen, "viewer_disconnect");
}

#[allow(dead_code)]
fn log_error(msg: &str) {
    diag(LOG_ERROR, msg)
}
