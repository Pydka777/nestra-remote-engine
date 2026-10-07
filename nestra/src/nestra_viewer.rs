//! NESTRA Remote viewer core for Android (AGPL-3.0, part of librustdesk in the public engine fork).
//!
//! JNI contract with com.nestra.remote.viewer.NativeViewer (NESTRA Remote Android app; ABI 2):
//!   int  nativeAbiVersion()
//!   void nativeInit(String appDir)                     // once per process: upstream main_init + NESTRA settings
//!   long nativeConnect(String engineId, String host, String key, char[] grant, Callback cb)   // 0 = refused
//!   void nativeSetSurface(long h, Surface s)          // frames of display 0 are drawn here (RGBA_8888)
//!   void nativeMouse(long h, int kind, int x, int y, int button, int delta)   // kind: 0 move 1 down 2 up 3 wheel
//!   void nativeKey(long h, int androidKeyCode, boolean down)
//!   void nativeText(long h, String text)
//!   void nativeClose(long h)
//!   Callback: onConnected(int w, int h), onResolution(int w, int h), onClosed(String reason)
//!
//! Built on the upstream Flutter session (FlutterHandler implements the UI trait): session_add + io_loop, with two
//! hooks in flutter.rs - decoded frames come to `on_frame` (drawn into the app's Surface, never stored), UI events
//! to `on_event`. The grant is the session password: used once, zeroed here, never logged. Host and key are checked
//! against the compiled-in NESTRA values (no server override). One session at a time.

use crate::flutter_ffi::SessionID;
use crate::input::{MOUSE_BUTTON_LEFT, MOUSE_BUTTON_RIGHT, MOUSE_TYPE_DOWN, MOUSE_TYPE_UP, MOUSE_TYPE_WHEEL};
use hbb_common::log;
use jni::objects::{GlobalRef, JCharArray, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong};
use jni::{JNIEnv, JavaVM};
use std::os::raw::c_void;
use std::sync::{Mutex, Once};

const ABI: jint = 2;
const HANDLE: jlong = 0x4e52; // one session at a time; the handle only guards against stale calls

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
const WINDOW_FORMAT_RGBA_8888: i32 = 1;

struct Viewer {
    session_id: SessionID,
    session: crate::flutter::FlutterSession,
    window: usize, // *mut ANativeWindow
    cb: GlobalRef,
    vm: usize, // *mut jni::sys::JavaVM
    size: (usize, usize),
    connected: bool,
    closing: bool,
}

lazy_static::lazy_static! {
    static ref VIEWER: Mutex<Option<Viewer>> = Mutex::new(None);
}
static INIT: Once = Once::new();

fn jvm(ptr: usize) -> Option<JavaVM> {
    unsafe { JavaVM::from_raw(ptr as *mut jni::sys::JavaVM) }.ok()
}

fn callback(vm: usize, cb: &GlobalRef, name: &str, sig: &str, args: &[JValue]) {
    let Some(vm) = jvm(vm) else { return };
    if let Ok(mut env) = vm.attach_current_thread_as_daemon() {
        let _ = env.call_method(cb.as_obj(), name, sig, args);
    }
}

fn closed(reason: &str) {
    let v = VIEWER.lock().unwrap().take();
    if let Some(v) = v {
        if v.window != 0 {
            unsafe { ANativeWindow_release(v.window as *mut c_void) };
        }
        crate::flutter_ffi::session_close(v.session_id);
        if !v.closing {
            if let Some(vm) = jvm(v.vm) {
                if let Ok(mut env) = vm.attach_current_thread_as_daemon() {
                    if let Ok(r) = env.new_string(reason) {
                        let _ = env.call_method(v.cb.as_obj(), "onClosed", "(Ljava/lang/String;)V", &[JValue::Object(&*r)]);
                    }
                }
            }
        }
        log::info!("NESTRA viewer closed ({reason})");
    }
}

/// Hook from FlutterHandler::on_rgba_soft_render. true = frame consumed by the NESTRA viewer.
pub fn on_frame(display: usize, rgba: &scrap::ImageRgb) -> bool {
    let mut lock = VIEWER.lock().unwrap();
    let Some(v) = lock.as_mut() else { return false };
    if display != 0 || rgba.w == 0 || rgba.h == 0 {
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
    let (vm, cb) = (v.vm, v.cb.clone());
    drop(lock);
    if first {
        callback(vm, &cb, "onConnected", "(II)V", &[JValue::Int(w as i32), JValue::Int(h as i32)]);
    } else if resized {
        callback(vm, &cb, "onResolution", "(II)V", &[JValue::Int(w as i32), JValue::Int(h as i32)]);
    }
    true
}

/// Hook from FlutterHandler::push_event_ (JSON with "name").
pub fn on_event(json: &str) {
    let Ok(e) = serde_json::from_str::<serde_json::Value>(json) else { return };
    let name = e.get("name").and_then(|x| x.as_str()).unwrap_or("");
    let session = match VIEWER.lock().unwrap().as_ref() {
        Some(v) => v.session.clone(),
        None => return,
    };
    match name {
        // logged in: ask for display 0 (what upstream's UI does on start)
        "peer_info" => {
            session.switch_display(0);
            session.refresh_video(0);
        }
        "msgbox" => {
            let t = e.get("type").and_then(|x| x.as_str()).unwrap_or("");
            let reason = match t {
                "input-password" | "re-input-password" | "session-login" | "session-re-login" | "session-login-password"
                | "session-login-other-password" => Some("login_failed"),
                "error" => Some("connection_error"),
                _ => None,
            };
            if let Some(r) = reason {
                let r = r.to_owned();
                std::thread::spawn(move || closed(&r));
            }
        }
        _ => {}
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
    if !INIT.is_completed()
        || host != crate::nestra_config::HOST
        || key != crate::nestra_config::KEY
        || engine.is_empty()
        || !engine.bytes().all(|b| b.is_ascii_digit())
        || VIEWER.lock().unwrap().is_some()
    {
        return 0;
    }
    let len = env.get_array_length(&grant).unwrap_or(0).max(0) as usize;
    let mut buf = vec![0u16; len];
    if len == 0 || env.get_char_array_region(&grant, 0, &mut buf).is_err() {
        return 0;
    }
    let mut password = String::from_utf16_lossy(&buf);
    buf.iter_mut().for_each(|c| *c = 0);
    let (Ok(vm), Ok(cb)) = (env.get_java_vm(), env.new_global_ref(cb)) else { return 0 };
    let vm = vm.get_java_vm_pointer() as usize;
    let session_id = uuid::Uuid::new_v4();
    let added = crate::flutter::session_add(
        &session_id, &engine, false, false, false, false, false, "", false, password.clone(), false, None,
    );
    // zero our copies of the grant (the session keeps it only until login; upstream rotates it on the PC side)
    unsafe { password.as_bytes_mut().iter_mut().for_each(|b| *b = b'0') };
    let Ok(session) = added else { return 0 };
    *VIEWER.lock().unwrap() = Some(Viewer {
        session_id,
        session: session.clone(),
        window: 0,
        cb,
        vm,
        size: (0, 0),
        connected: false,
        closing: false,
    });
    std::thread::spawn(move || {
        let s = (*session).clone();
        let round = s.connection_round_state.lock().unwrap().new_round();
        crate::ui_session_interface::io_loop(s, round);
        closed("engine_closed");
    });
    log::info!("NESTRA viewer connecting to engine {engine}");
    HANDLE
}

fn with_session(h: jlong, f: impl FnOnce(&crate::flutter::FlutterSession)) {
    if h != HANDLE {
        return;
    }
    let s = VIEWER.lock().unwrap().as_ref().map(|v| v.session.clone());
    if let Some(s) = s {
        f(&s);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nestra_remote_viewer_NativeViewer_nativeSetSurface(env: JNIEnv, _c: JClass, h: jlong, surface: JObject) {
    if h != HANDLE {
        return;
    }
    let new = if surface.is_null() {
        0
    } else {
        unsafe { ANativeWindow_fromSurface(env.get_raw(), surface.as_raw()) as usize }
    };
    let mut lock = VIEWER.lock().unwrap();
    match lock.as_mut() {
        Some(v) => {
            if v.window != 0 {
                unsafe { ANativeWindow_release(v.window as *mut c_void) };
            }
            v.window = new;
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
    if h != HANDLE {
        return;
    }
    if let Some(v) = VIEWER.lock().unwrap().as_mut() {
        v.closing = true; // the app asked: no onClosed callback
    }
    closed("viewer_disconnect");
}

