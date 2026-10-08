#!/usr/bin/env python3
"""Unit test of nestra/apply_patches.py on excerpts copied VERBATIM (indentation included) from the pinned upstream
rustdesk 1.5.0 @ fada664d / hbb_common @ 229b9045 (every anchor was also checked to occur exactly once in the full
real files). The CI then runs the script on the full real checkout.   usage: python3 nestra/tests/test_apply_patches.py"""
import pathlib, subprocess, sys, tempfile

HERE = pathlib.Path(__file__).resolve().parent.parent
FILES = {
    "libs/hbb_common/src/config.rs":
        '    pub static ref APP_NAME: RwLock<String> = RwLock::new("RustDesk".to_owned());\n'
        'pub const RENDEZVOUS_SERVERS: &[&str] = &["rs-ny.rustdesk.com"];\n'
        'pub const RS_PUB_KEY: &str = "OeVuKk5nlHiXp+APNn0Y3pC1Iwpwn44JGqrQCsWqmBw=";\n\n'
        'impl Config {\n'
        '    pub fn get_rendezvous_server() -> String {\n'
        '        let mut rendezvous_server = EXE_RENDEZVOUS_SERVER.read().unwrap().clone();\n'
        '    }\n\n'
        '    pub fn get_rendezvous_servers() -> Vec<String> {\n'
        '        let s = EXE_RENDEZVOUS_SERVER.read().unwrap().clone();\n'
        '    }\n}\n',
    "libs/hbb_common/src/password_security.rs":
        '// Should only be called in server\npub fn update_temporary_password() {\n'
        '    *TEMPORARY_PASSWORD.write().unwrap() = get_auto_password();\n}\n',
    "src/common.rs":
        'pub async fn get_key(sync: bool) -> String {\n    #[cfg(windows)]\n',
    "src/lib.rs": 'pub mod core_main;\nmod custom_server;\nmod lang;\n',
    "src/core_main.rs":
        '    crate::load_custom_client();\n'
        '        } else if args[0] == "--set-unlock-pin" {\n            return None;\n'
        '        } else if args[0] == "--get-id" {\n            println!("{}", crate::ipc::get_id());\n            return None;\n',
    "src/ipc.rs":
        '                if name == "id" {\n'
        '                    value = Some(Config::get_id());\n'
        '                } else if name == "temporary-password" {\n'
        '                    value = Some(password::temporary_password());\n'
        '                } else if name == "permanent-password-storage-and-salt" {\n'
        '            Some(value) => {\n'
        '                let mut updated = true;\n'
        '                if name == "id" {\n'
        '                } else if name == "temporary-password" {\n'
        '                    password::update_temporary_password();\n'
        '                } else if name == "permanent-password" {\n',
    "src/server.rs": 'pub use connection::*;\n\nmod connection;\nmod login_failure_check;\n',
    "src/server/connection.rs":
        '                _ = second_timer.tick() => {\n                    #[cfg(windows)]\n                    conn.portable_check();\n'
        '    fn validate_password(&mut self, allow_permanent_password: bool) -> bool {\n'
        '        if password::temporary_enabled() {\n'
        '            let password = password::temporary_password();\n'
        '            if self.validate_password_plain(&password) {\n'
        '                self.set_conn_audit_primary_auth(ConnAuditPrimaryAuth::TemporaryPassword);\n',
    "src/client.rs":
        '    // last password\n'
        '    let mut password = lc.read().unwrap().password.clone();\n'
        '    // preset password\n'
        '    if password.is_empty() {\n',
    "src/flutter.rs":
        'pub struct FlutterHandler {\n'
        '    // ui session id -> display handler data\n'
        '    session_handlers: Arc<RwLock<HashMap<SessionID, SessionHandler>>>,\n'
        '    display_rgbas: Arc<RwLock<HashMap<usize, RgbaData>>>,\n'
        '}\n'
        '    fn on_rgba_soft_render(&self, display: usize, rgba: &mut scrap::ImageRgb) {\n'
        '        let mut rgba_write_lock = self.display_rgbas.write().unwrap();\n'
        '        h.insert("name", json!(name));\n'
        '        let out = serde_json::ser::to_string(&h).unwrap_or("".to_owned());\n'
        '        for (sid, session) in self.session_handlers.read().unwrap().iter() {\n',
}

def tree():
    d = pathlib.Path(tempfile.mkdtemp())
    for rel, txt in FILES.items():
        (d / rel).parent.mkdir(parents=True, exist_ok=True)
        (d / rel).write_text(txt, encoding="utf-8")
    return d

def run(d):
    return subprocess.run([sys.executable, str(HERE / "apply_patches.py"), str(d)], capture_output=True, text=True)

fails = 0
passes = 0
def check(name, ok):
    global fails, passes
    fails += not ok
    passes += bool(ok)
    print(("PASS  " if ok else "FAIL  ") + name)

d = tree()
r = run(d)
check("applies on the verbatim upstream excerpts", r.returncode == 0 and "NESTRA patches applied" in r.stdout)
R = lambda rel: (d / rel).read_text(encoding="utf-8")
cfg = R("libs/hbb_common/src/config.rs")
check("APP_NAME nestra-remote-engine", 'RwLock::new("nestra-remote-engine".to_owned())' in cfg)
check("upstream public server and key gone", "rs-ny.rustdesk.com" not in cfg and "OeVuKk5nlHiXp" not in cfg)
check("NESTRA host and key", '"remote.nestraparent.com"' in cfg and "AZgwj6064Sndiv07Av1O8rn5E0GyNJz7BPQzS7TsnXo=" in cfg)
check("get_rendezvous_server(s) fixed", cfg.count("return NESTRA_RENDEZVOUS_SERVER.to_owned();") == 1 and "return vec![NESTRA_RENDEZVOUS_SERVER.to_owned()];" in cfg)
check("set_temporary_password_exact added before update_temporary_password",
      R("libs/hbb_common/src/password_security.rs").index("set_temporary_password_exact") < R("libs/hbb_common/src/password_security.rs").index("update_temporary_password"))
check("get_key returns the NESTRA key", "return config::RS_PUB_KEY.to_owned();" in R("src/common.rs"))
cm = R("src/core_main.rs")
check("enforce() after load_custom_client", "crate::load_custom_client();\n    crate::nestra_config::enforce();" in cm)
check("--nestra-session and --nestra-close in the same else-if chain before --get-id",
      cm.index('"--nestra-session"') < cm.index('"--nestra-close"') < cm.index('"--get-id"') and '} else if args[0] == "--nestra-session" {' in cm)
ipc = R("src/ipc.rs")
check("ipc keys", 'name == "nestra-authed"' in ipc and 'name == "nestra-grant"' in ipc and 'name == "nestra-close"' in ipc)
check("server close flag + count", "pub static NESTRA_CLOSE" in R("src/server.rs") and "fn nestra_authed_count" in R("src/server.rs"))
check("connection closes on tick", "NESTRA_CLOSE.load" in R("src/server/connection.rs"))
check("nestra-grant is acknowledged with the fingerprint of the password held (never the value)",
      'let fp = crate::nestra_config::fingerprint(&held);' in ipc and 'stream.send(&Data::Config((name.clone(), Some(fp))))' in ipc
      and 'Some(value' not in ipc.split('name == "nestra-grant"')[1].split('name == "nestra-close"')[0])
check("nestra-close rotates the temporary password and is acknowledged",
      'if value == "1" {\n                        password::update_temporary_password();' in ipc
      and 'Some(value.clone())' in ipc.split('name == "nestra-close"')[1].split('name == "temporary-password"')[0])
cn = R("src/server/connection.rs")
check("login check logs the held temporary password's fingerprint + result, decision unchanged",
      'secret_diag(\"engine_login_check\", &password)' in cn)
check("login decision still validate_password_plain", "let nestra_ok = self.validate_password_plain(&password);" in cn and "if nestra_ok {" in cn)
check("android handle_hash reports the preset fingerprint input (android only)",
      '#[cfg(target_os = "android")]\n    crate::nestra_viewer::on_login_secret(password_preset, !password.is_empty());\n    // preset password' in R("src/client.rs"))
fl = R("src/flutter.rs")
check("android hooks carry the handler token", "nestra_viewer::on_frame(self.nestra_token(), display, rgba)" in fl
      and "nestra_viewer::on_event(self.nestra_token(), &out)" in fl)
check("FlutterHandler::nestra_token appended (android only, from the shared session_handlers Arc)",
      fl.rstrip().endswith("}") and "impl FlutterHandler {\n    pub fn nestra_token(&self) -> usize {\n        Arc::as_ptr(&self.session_handlers)" in fl
      and fl.count("#[cfg(target_os = \"android\")]\nimpl FlutterHandler") == 1)
check("new modules copied", all((d / "src" / f).exists() for f in ("nestra_config.rs", "nestra_session.rs", "nestra_viewer.rs")))
r2 = run(d)
check("second run refused (no double patch)", r2.returncode != 0 and "ALREADY PATCHED" in (r2.stdout + r2.stderr))
d3 = tree()
(d3 / "src/ipc.rs").write_text(FILES["src/ipc.rs"].replace("                if name == \"id\"", "            if name == \"id\""), encoding="utf-8")
r3 = run(d3)
check("a changed upstream shape stops the script (anchor mismatch, not a silent skip)", r3.returncode != 0 and "ANCHOR MISMATCH" in (r3.stdout + r3.stderr))
print(f"RESULT PASS={passes} FAIL={fails}")
sys.exit(1 if fails else 0)
