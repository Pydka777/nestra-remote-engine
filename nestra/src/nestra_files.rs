//! NESTRA Remote viewer: file browser + file transfer logic (AGPL-3.0, part of librustdesk in the public engine fork).
//!
//! WHY a separate connection (root cause of "No response from PC file browser", 2026-10-09):
//! upstream RustDesk 1.5.0 handles a `FileAction` (ReadDir, Send, Receive, ...) on the controlled PC ONLY when the
//! connection logged in as FILE_TRANSFER (`src/server/connection.rs`: `let mut handle_fa = self.file_transfer.is_some();`).
//! On the remote-desktop connection every directory request is dropped silently, so no `file_dir` ever came back.
//! Upstream's own clients open a second, FILE_TRANSFER connection for the file manager, authenticated with the
//! remote-desktop session's ConnToken (same client session id + the already derived password hash; the PC accepts it
//! through its "recent session" check while the remote-desktop session is alive). nestra_viewer does exactly that.
//!
//! This module holds the pure, unit-tested part (no JNI, no network): path rules, job bookkeeping and the translation
//! of upstream UI events into the events the NESTRA app consumes (`onFileEvent(name, json)`):
//!   file_dir      {"name":"file_dir","value":"{\"id\":0,\"path\":..,\"entries\":[..]}"}   (upstream shape, browse only)
//!   file_error    {"op":"dir|transfer|session","id":N,"message":".."}
//!   job_progress  {"id":N,"finished":B,"total":B,"percent":P}
//!   job_done      {"id":N}
//!   job_error     {"id":N,"message":".."}
//! Never logged or forwarded: file contents, tokens, the session password or ConnToken.

use serde_json::{json, Value};
use std::collections::HashMap;

/// Android package whose private / app-specific storage is the only place a transfer may read from or write to.
pub const PACKAGE: &str = "com.nestra.remote";
/// Longest path accepted on either side (Windows long paths are 32767 UTF-16 units; this is plenty for real use).
pub const MAX_PATH_CHARS: usize = 4096;

fn has_traversal(p: &str) -> bool {
    p.split(|c| c == '/' || c == '\\').any(|c| c == "..")
}

/// A local (phone) path used by a transfer: absolute, no `..`, no NUL, inside this app's own storage only
/// (`/data/user/<n>/<pkg>/`, `/data/data/<pkg>/`, `/storage/emulated/<n>/Android/data/<pkg>/`).
pub fn local_path_allowed(p: &str) -> bool {
    if p.is_empty() || p.len() > MAX_PATH_CHARS || p.contains('\0') || !p.starts_with('/') || has_traversal(p) || p.contains('\\') {
        return false;
    }
    let parts: Vec<&str> = p.split('/').collect(); // ["", "data", "user", "0", pkg, ...]
    let digits = |s: &str| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit());
    let ok = match parts.as_slice() {
        ["", "data", "user", n, pkg, rest @ ..] => digits(n) && *pkg == PACKAGE && !rest.is_empty(),
        ["", "data", "data", pkg, rest @ ..] => *pkg == PACKAGE && !rest.is_empty(),
        ["", "storage", "emulated", n, "Android", "data", pkg, rest @ ..] => digits(n) && *pkg == PACKAGE && !rest.is_empty(),
        _ => false,
    };
    ok && parts.last().map_or(false, |l| !l.is_empty())
}

fn windows_component_ok(c: &str) -> bool {
    !c.is_empty() && c != "." && c != ".." && !c.chars().any(|ch| matches!(ch, '<' | '>' | ':' | '"' | '|' | '?' | '*') || (ch as u32) < 0x20)
}

/// `X:` / `X:\` / `X:\a\b` (forward slashes tolerated), every component a valid Windows name.
fn windows_absolute(p: &str) -> bool {
    let b = p.as_bytes();
    if b.len() < 2 || !b[0].is_ascii_alphabetic() || b[1] != b':' {
        return false;
    }
    let rest = &p[2..];
    if rest.is_empty() {
        return true;
    }
    if !(rest.starts_with('\\') || rest.starts_with('/')) {
        return false;
    }
    rest[1..].split(|c| c == '\\' || c == '/').filter(|c| !c.is_empty()).all(windows_component_ok)
}

/// A directory the phone may list on the PC: "" (the PC user's home), "/" (the drive list) or an absolute Windows path.
pub fn remote_dir_allowed(p: &str) -> bool {
    if p.is_empty() || p == "/" {
        return true;
    }
    p.chars().count() <= MAX_PATH_CHARS && !p.contains('\0') && !has_traversal(p) && windows_absolute(p)
}

/// A single PC file of a transfer: absolute Windows path of a file (not a drive, not ending in a separator).
pub fn remote_file_allowed(p: &str) -> bool {
    remote_dir_allowed(p) && p.len() > 3 && !p.ends_with('\\') && !p.ends_with('/')
}

#[derive(Debug, Clone, PartialEq)]
pub struct Job {
    pub upload: bool,
    pub total: Option<u64>,
}

/// What the viewer must do after an upstream event of the FILE_TRANSFER session.
#[derive(Debug, PartialEq)]
pub enum Action {
    /// hand (name, json) to the app's onFileEvent
    Emit(&'static str, String),
    /// answer upstream's overwrite question: never overwrite an existing file
    SkipOverwrite { id: i32, file_num: i32, is_upload: bool },
    /// cancel this job (unsafe or unexpected file list)
    Cancel(i32),
}

#[derive(Default)]
pub struct Jobs {
    jobs: HashMap<i32, Job>,
}

fn s<'a>(e: &'a Value, k: &str) -> &'a str {
    e.get(k).and_then(|x| x.as_str()).unwrap_or("")
}

fn num(e: &Value, k: &str) -> Option<f64> {
    match e.get(k)? {
        Value::String(x) => x.parse().ok(),
        Value::Number(n) => n.as_f64(),
        _ => None,
    }
}

/// Upstream error text is shown to the user; keep it short and on one line.
pub fn short(msg: &str) -> String {
    let one: String = msg.chars().map(|c| if c.is_control() { ' ' } else { c }).collect();
    let t = one.trim();
    if t.is_empty() {
        "Unknown error".to_owned()
    } else {
        t.chars().take(160).collect()
    }
}

pub fn file_error(op: &str, id: i32, message: &str) -> Action {
    Action::Emit("file_error", json!({"op": op, "id": id, "message": short(message)}).to_string())
}

impl Jobs {
    pub fn start(&mut self, id: i32, upload: bool, total: Option<u64>) {
        self.jobs.insert(id, Job { upload, total });
    }
    pub fn get(&self, id: i32) -> Option<&Job> {
        self.jobs.get(&id)
    }
    pub fn ids(&self) -> Vec<i32> {
        let mut v: Vec<i32> = self.jobs.keys().copied().collect();
        v.sort();
        v
    }
    pub fn remove(&mut self, id: i32) -> Option<Job> {
        self.jobs.remove(&id)
    }
    pub fn is_empty(&self) -> bool {
        self.jobs.is_empty()
    }

    /// Translates one upstream event (`name`, parsed `e`, raw `json`) of the FILE_TRANSFER session.
    pub fn handle(&mut self, name: &str, e: &Value, json: &str) -> Vec<Action> {
        match name {
            "file_dir" => {
                let Ok(fd) = serde_json::from_str::<Value>(s(e, "value")) else {
                    return vec![file_error("dir", 0, "Unreadable directory listing from the PC")];
                };
                let id = fd.get("id").and_then(|x| x.as_i64()).unwrap_or(0) as i32;
                if id == 0 {
                    return vec![Action::Emit("file_dir", json.to_owned())]; // a browse answer: upstream shape as is
                }
                // the file list of a download job (the PC side of `Receive`): exactly ONE plain file, no sub-paths
                let Some(job) = self.jobs.get_mut(&id) else { return vec![] };
                if job.upload {
                    return vec![];
                }
                let entries = fd.get("entries").and_then(|x| x.as_array()).cloned().unwrap_or_default();
                let single = entries.len() == 1
                    && entries[0].get("name").and_then(|x| x.as_str()) == Some("")
                    && entries[0].get("entry_type").and_then(|x| x.as_i64()) == Some(4);
                if !single {
                    self.jobs.remove(&id);
                    return vec![
                        Action::Cancel(id),
                        Action::Emit("job_error", json!({"id": id, "message": "Only single files can be downloaded"}).to_string()),
                    ];
                }
                job.total = entries[0].get("size").and_then(|x| x.as_u64());
                vec![]
            }
            "job_progress" => {
                let id = num(e, "id").unwrap_or(-1.0) as i32;
                let Some(job) = self.jobs.get(&id) else { return vec![] };
                let finished = num(e, "finished_size").unwrap_or(0.0).max(0.0) as u64;
                let total = job.total.unwrap_or(0);
                let percent = if total > 0 { (finished.min(total) * 100 / total) as i64 } else { -1 };
                vec![Action::Emit("job_progress", json!({"id": id, "finished": finished, "total": total, "percent": percent}).to_string())]
            }
            "job_done" => {
                let id = num(e, "id").unwrap_or(-1.0) as i32;
                if self.jobs.remove(&id).is_some() {
                    vec![Action::Emit("job_done", json!({"id": id}).to_string())]
                } else {
                    vec![]
                }
            }
            "job_error" => {
                let id = num(e, "id").unwrap_or(-1.0) as i32;
                let err = s(e, "err");
                if id == 0 {
                    // the answer to a directory request (upstream uses job id 0 for ReadDir errors)
                    return vec![file_error("dir", 0, err)];
                }
                if self.jobs.remove(&id).is_some() {
                    vec![Action::Emit("job_error", json!({"id": id, "message": short(err)}).to_string())]
                } else {
                    vec![]
                }
            }
            "override_file_confirm" => {
                let id = num(e, "id").unwrap_or(-1.0) as i32;
                let file_num = num(e, "file_num").unwrap_or(0.0) as i32;
                let is_upload = s(e, "is_upload") == "true";
                if self.jobs.remove(&id).is_none() {
                    return vec![];
                }
                vec![
                    Action::SkipOverwrite { id, file_num, is_upload },
                    Action::Emit("job_error", json!({"id": id, "message": "A file with this name already exists; nothing was overwritten"}).to_string()),
                ]
            }
            _ => vec![],
        }
    }

    /// The FILE_TRANSFER connection is gone: every running job fails.
    pub fn fail_all(&mut self, message: &str) -> Vec<Action> {
        let ids = self.ids();
        self.jobs.clear();
        ids.into_iter()
            .map(|id| Action::Emit("job_error", json!({"id": id, "message": short(message)}).to_string()))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ev(name: &str, fields: Value) -> (Value, String) {
        let mut m = fields.as_object().cloned().unwrap_or_default();
        m.insert("name".into(), json!(name));
        let v = Value::Object(m);
        let s = v.to_string();
        (v, s)
    }

    fn fd(id: i32, path: &str, entries: Value) -> String {
        json!({"id": id, "path": path, "entries": entries}).to_string()
    }

    #[test]
    fn local_paths_only_inside_this_apps_storage() {
        assert!(local_path_allowed("/data/user/0/com.nestra.remote/cache/nestra-upload-1-a.txt"));
        assert!(local_path_allowed("/data/data/com.nestra.remote/files/x"));
        assert!(local_path_allowed("/storage/emulated/0/Android/data/com.nestra.remote/files/Download/Zażółć gęślą jaźń.txt"));
        assert!(local_path_allowed("/data/user/10/com.nestra.remote/cache/my file (2).zip"));
        for bad in [
            "",
            "relative/x",
            "/data/user/0/com.nestra.remote/../com.other/x",
            "/data/user/0/com.nestra.remote/cache/../../x",
            "/data/user/0/com.nestra.remotex/cache/x",
            "/data/user/0/com.other/cache/x",
            "/storage/emulated/0/Download/x",
            "/sdcard/Android/data/com.nestra.remote/x",
            "/data/user/0/com.nestra.remote",
            "/data/user/0/com.nestra.remote/",
            "/data/user/0/com.nestra.remote/cache/x\0y",
            "/data/user/zero/com.nestra.remote/x",
            "/data/user/0/com.nestra.remote/a\\..\\b",
        ] {
            assert!(!local_path_allowed(bad), "{bad:?}");
        }
    }

    #[test]
    fn remote_paths_absolute_windows_without_traversal() {
        for ok in ["", "/", "C:", "C:\\", "c:\\Users\\Mazi", "D:\\Zdjęcia\\Wakacje 2026", "C:/Users/Mazi/Desktop", "C:\\Users\\Mazi\\Pulpit\\ąęść żółw.txt"] {
            assert!(remote_dir_allowed(ok), "{ok:?}");
        }
        for bad in ["..", "C:\\..\\Windows", "C:\\Users\\..\\..\\Windows", "Users\\Mazi", "\\\\server\\share", "C:x", "C:\\a\\b:c", "C:\\a?b", "C:\\a\0", "1:\\x", "C:\\a\\\u{1}b"] {
            assert!(!remote_dir_allowed(bad), "{bad:?}");
        }
        assert!(remote_file_allowed("C:\\Users\\Mazi\\Desktop\\plik z spacjami.txt"));
        assert!(remote_file_allowed("C:\\Users\\Mazi\\Desktop\\Łódź – raport.zip"));
        for bad in ["", "/", "C:", "C:\\", "C:\\Users\\", "C:\\a\\..\\b.txt"] {
            assert!(!remote_file_allowed(bad), "{bad:?}");
        }
        let long = format!("C:\\{}", "a".repeat(MAX_PATH_CHARS));
        assert!(!remote_dir_allowed(&long));
    }

    #[test]
    fn browse_answers_pass_through_and_job_lists_never_replace_the_listing() {
        let mut j = Jobs::default();
        let (e, raw) = ev("file_dir", json!({"is_local": "false", "value": fd(0, "C:\\Users\\Mazi", json!([{"name": "Pulpit", "entry_type": 0, "size": 0}]))}));
        assert_eq!(j.handle("file_dir", &e, &raw), vec![Action::Emit("file_dir", raw.clone())]);
        // a download job's file list: total size recorded, nothing emitted
        j.start(1001, false, None);
        let (e, raw) = ev("file_dir", json!({"value": fd(1001, "C:\\x.zip", json!([{"name": "", "entry_type": 4, "size": 20971520}]))}));
        assert_eq!(j.handle("file_dir", &e, &raw), vec![]);
        assert_eq!(j.get(1001).unwrap().total, Some(20971520));
        let (e, raw) = ev("job_progress", json!({"id": "1001", "file_num": "0", "speed": "1.0", "finished_size": "10485760"}));
        assert_eq!(
            j.handle("job_progress", &e, &raw),
            vec![Action::Emit("job_progress", json!({"id": 1001, "finished": 10485760u64, "total": 20971520u64, "percent": 50}).to_string())]
        );
        let (e, raw) = ev("job_done", json!({"id": "1001", "file_num": "0"}));
        assert_eq!(j.handle("job_done", &e, &raw), vec![Action::Emit("job_done", json!({"id": 1001}).to_string())]);
        assert!(j.is_empty());
        // events of unknown jobs are ignored
        assert_eq!(j.handle("job_done", &e, &raw), vec![]);
    }

    #[test]
    fn a_download_that_is_not_one_plain_file_is_cancelled() {
        let mut j = Jobs::default();
        j.start(1002, false, None);
        let (e, raw) = ev("file_dir", json!({"value": fd(1002, "C:\\dir", json!([{"name": "a\\..\\..\\evil", "entry_type": 4, "size": 1}, {"name": "b", "entry_type": 4, "size": 1}]))}));
        let out = j.handle("file_dir", &e, &raw);
        assert_eq!(out[0], Action::Cancel(1002));
        assert!(matches!(&out[1], Action::Emit("job_error", s) if s.contains("single files")));
        assert!(j.get(1002).is_none());
    }

    #[test]
    fn errors_overwrite_and_connection_loss_end_with_clear_messages() {
        let mut j = Jobs::default();
        let (e, raw) = ev("job_error", json!({"id": "0", "err": "Access is denied. (os error 5)", "file_num": "-1"}));
        assert_eq!(
            j.handle("job_error", &e, &raw),
            vec![Action::Emit("file_error", json!({"op": "dir", "id": 0, "message": "Access is denied. (os error 5)"}).to_string())]
        );
        j.start(1003, true, Some(1024));
        let (e, raw) = ev("override_file_confirm", json!({"id": "1003", "file_num": "0", "read_path": "C:\\a.txt", "is_upload": "true", "is_identical": "false"}));
        let out = j.handle("override_file_confirm", &e, &raw);
        assert_eq!(out[0], Action::SkipOverwrite { id: 1003, file_num: 0, is_upload: true });
        assert!(matches!(&out[1], Action::Emit("job_error", s) if s.contains("already exists")));
        j.start(1004, true, Some(1));
        j.start(1005, false, None);
        let out = j.fail_all("File connection to the PC closed\n");
        assert_eq!(out.len(), 2);
        assert!(j.is_empty());
        assert_eq!(short(" a\nb\t "), "a b");
        assert_eq!(short(""), "Unknown error");
        assert_eq!(short(&"x".repeat(500)).chars().count(), 160);
    }

    #[test]
    fn progress_of_an_upload_uses_the_local_size_and_never_exceeds_100() {
        let mut j = Jobs::default();
        j.start(1006, true, Some(1000));
        let (e, raw) = ev("job_progress", json!({"id": "1006", "finished_size": "5000"}));
        assert_eq!(
            j.handle("job_progress", &e, &raw),
            vec![Action::Emit("job_progress", json!({"id": 1006, "finished": 5000u64, "total": 1000u64, "percent": 100}).to_string())]
        );
        j.start(1007, true, None);
        let (e, raw) = ev("job_progress", json!({"id": "1007", "finished_size": "5"}));
        assert!(matches!(&j.handle("job_progress", &e, &raw)[0], Action::Emit("job_progress", s) if s.contains("\"percent\":-1")));
    }
}
