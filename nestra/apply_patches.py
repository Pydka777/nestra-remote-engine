#!/usr/bin/env python3
"""NESTRA Remote Engine: applies the NESTRA modifications to the PINNED upstream RustDesk checkout (AGPL-3.0).

Pinned upstream (see UPSTREAM.lock): rustdesk/rustdesk 1.5.0 = fada664df7a294d1d1a9ca3e7cd3637069122f17,
libs/hbb_common = rustdesk/hbb_common 229b904508364c8997aad0fb5af57effac859f60. Every anchor below was taken from
those exact files. Every edit is ANCHORED and counted: if an anchor is not found exactly once the script stops with
context, so a different upstream never yields a silently half-patched engine.

usage: apply_patches.py <path-to-upstream-rustdesk>

Changes (all listed in CHANGES-FROM-UPSTREAM.md, AGPL section 5a):
  hbb_common/config.rs      APP_NAME "nestra-remote-engine"; built-in server + key = NESTRA; get_rendezvous_server(s)
                            always return the NESTRA host (no exe-name / option / config override)
  hbb_common/password_security.rs  set_temporary_password_exact()   (the session grant becomes the temporary password)
  src/common.rs             get_key() always returns the NESTRA key
  src/lib.rs                + nestra_config (all), nestra_session (Windows), nestra_viewer (Android)
  src/core_main.rs          nestra_config::enforce() in every engine process; CLI modes --nestra-session, --nestra-close
  src/ipc.rs                engine-local IPC keys: get "nestra-authed"; set "nestra-grant", "nestra-close"
  src/server.rs             NESTRA_CLOSE flag + nestra_authed_count()
  src/server/connection.rs  an authorised connection closes on its next 1 s tick when NESTRA_CLOSE is set
  src/flutter.rs            Android viewer hooks: frames -> Surface (nestra_viewer::on_frame), events -> on_event
"""
import pathlib, shutil, sys

ROOT = pathlib.Path(sys.argv[1]).resolve()
HERE = pathlib.Path(__file__).resolve().parent
HOST = "remote.nestraparent.com"
KEY = "AZgwj6064Sndiv07Av1O8rn5E0GyNJz7BPQzS7TsnXo="


def edit(rel, anchor, new, count=1):
    p = ROOT / rel
    s = p.read_text(encoding="utf-8")
    hits = s.count(anchor)
    if hits != count:
        first = anchor.strip().splitlines()[0].strip()[:40]
        ctx = "\n".join(f"{i+1}: {l}" for i, l in enumerate(s.splitlines()) if first in l)[:2000]
        sys.exit(f"ANCHOR MISMATCH in {rel}: expected {count}, found {hits}\n--- anchor ---\n{anchor}\n--- lines containing {first!r} ---\n{ctx}")
    p.write_text(s.replace(anchor, new), encoding="utf-8")
    print(f"patched {rel}")


def after(rel, anchor, addition):
    edit(rel, anchor, anchor + addition)


def before(rel, anchor, addition):
    edit(rel, anchor, addition + anchor)


if "nestra_config" in (ROOT / "src" / "lib.rs").read_text(encoding="utf-8"):
    sys.exit("ALREADY PATCHED: start from a clean checkout of the pinned upstream commit")

# ---------------------------------------------------------------------------------------------- new modules
for f in ("nestra_config.rs", "nestra_session.rs", "nestra_viewer.rs"):
    shutil.copy(HERE / "src" / f, ROOT / "src" / f)
after("src/lib.rs", "mod custom_server;\n",
      "/// NESTRA Remote: fixed server/key and enforced settings\npub mod nestra_config;\n"
      "/// NESTRA Remote: --nestra-session (one-time session grant on stdin)\n#[cfg(windows)]\npub mod nestra_session;\n"
      "/// NESTRA Remote: JNI viewer for the NESTRA Remote Android app\n#[cfg(target_os = \"android\")]\npub mod nestra_viewer;\n")

# ---------------------------------------------------------------------------------------------- hbb_common
CFG = "libs/hbb_common/src/config.rs"
edit(CFG, 'pub static ref APP_NAME: RwLock<String> = RwLock::new("RustDesk".to_owned());',
     'pub static ref APP_NAME: RwLock<String> = RwLock::new("nestra-remote-engine".to_owned());')
edit(CFG, 'pub const RENDEZVOUS_SERVERS: &[&str] = &["rs-ny.rustdesk.com"];',
     f'pub const RENDEZVOUS_SERVERS: &[&str] = &["{HOST}"];\n/// NESTRA Remote: the only rendezvous/relay host this build ever uses.\npub const NESTRA_RENDEZVOUS_SERVER: &str = "{HOST}";')
edit(CFG, 'pub const RS_PUB_KEY: &str = "OeVuKk5nlHiXp+APNn0Y3pC1Iwpwn44JGqrQCsWqmBw=";',
     f'pub const RS_PUB_KEY: &str = "{KEY}";')
after(CFG, "    pub fn get_rendezvous_server() -> String {\n",
      "        if !NESTRA_RENDEZVOUS_SERVER.is_empty() {\n            return NESTRA_RENDEZVOUS_SERVER.to_owned();\n        }\n")
after(CFG, "    pub fn get_rendezvous_servers() -> Vec<String> {\n",
      "        if !NESTRA_RENDEZVOUS_SERVER.is_empty() {\n            return vec![NESTRA_RENDEZVOUS_SERVER.to_owned()];\n        }\n")
before("libs/hbb_common/src/password_security.rs", "// Should only be called in server\npub fn update_temporary_password() {",
       "/// NESTRA Remote: the one-time session grant becomes the temporary password (engine-local IPC only).\n"
       "/// Upstream rotates it again right after a successful login, so it opens one session.\n"
       "pub fn set_temporary_password_exact(p: &str) {\n    *TEMPORARY_PASSWORD.write().unwrap() = p.to_owned();\n}\n\n")

# ---------------------------------------------------------------------------------------------- key, CLI, settings
after("src/common.rs", "pub async fn get_key(sync: bool) -> String {\n",
      "    if !config::NESTRA_RENDEZVOUS_SERVER.is_empty() {\n        let _ = sync;\n        return config::RS_PUB_KEY.to_owned();\n    }\n")
after("src/core_main.rs", "    crate::load_custom_client();\n", "    crate::nestra_config::enforce();\n")
edit("src/core_main.rs", '        } else if args[0] == "--get-id" {\n',
     '        } else if args[0] == "--nestra-session" {\n'
     '            #[cfg(windows)]\n'
     '            crate::nestra_session::run();\n'
     '            return None;\n'
     '        } else if args[0] == "--nestra-close" {\n'
     '            #[cfg(windows)]\n'
     '            crate::nestra_session::run_close();\n'
     '            return None;\n'
     '        } else if args[0] == "--get-id" {\n')

# ---------------------------------------------------------------------------------------------- engine IPC keys
IPC = "src/ipc.rs"
edit(IPC,
     '                if name == "id" {\n'
     '                    value = Some(Config::get_id());\n'
     '                } else if name == "temporary-password" {\n',
     '                if name == "id" {\n'
     '                    value = Some(Config::get_id());\n'
     '                } else if name == "nestra-authed" {\n'
     '                    value = Some(crate::server::nestra_authed_count().to_string());\n'
     '                } else if name == "temporary-password" {\n')
edit(IPC,
     '                } else if name == "temporary-password" {\n'
     '                    password::update_temporary_password();\n',
     '                } else if name == "nestra-grant" {\n'
     '                    password::set_temporary_password_exact(&value);\n'
     '                } else if name == "nestra-close" {\n'
     '                    crate::server::NESTRA_CLOSE.store(value == "1", std::sync::atomic::Ordering::SeqCst);\n'
     '                } else if name == "temporary-password" {\n'
     '                    password::update_temporary_password();\n')

# ---------------------------------------------------------------------------------------------- server close + count
after("src/server.rs", "\nmod connection;\n",
      "\n/// NESTRA Remote: set over the engine IPC key \"nestra-close\"; every authorised connection closes on its next tick.\n"
      "pub static NESTRA_CLOSE: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);\n\n"
      "/// NESTRA Remote: authorised remote-desktop connections right now.\n"
      "pub fn nestra_authed_count() -> usize {\n"
      "    AUTHED_CONNS\n        .lock()\n        .unwrap()\n        .iter()\n"
      "        .filter(|x| x.conn_type == AuthConnType::Remote)\n        .count()\n}\n")
after("src/server/connection.rs", "                _ = second_timer.tick() => {\n",
      "                    if crate::server::NESTRA_CLOSE.load(std::sync::atomic::Ordering::SeqCst) && conn.authorized {\n"
      "                        conn.on_close(\"NESTRA Remote session ended\", true).await;\n"
      "                        break;\n"
      "                    }\n")

# ---------------------------------------------------------------------------------------------- Android viewer hooks
after("src/flutter.rs", "    fn on_rgba_soft_render(&self, display: usize, rgba: &mut scrap::ImageRgb) {\n",
      "        #[cfg(target_os = \"android\")]\n"
      "        if crate::nestra_viewer::on_frame(display, rgba) {\n"
      "            return;\n"
      "        }\n")
after("src/flutter.rs",
      '        h.insert("name", json!(name));\n        let out = serde_json::ser::to_string(&h).unwrap_or("".to_owned());\n',
      '        #[cfg(target_os = "android")]\n        crate::nestra_viewer::on_event(&out);\n')
print("NESTRA patches applied")
