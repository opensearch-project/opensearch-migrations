//! Per-stage state I/O.
//!
//! An insertion-ordered key/value store, so `state.env` preserves the order
//! keys were first set (callers rely on grepping the file for diagnostics).
//!
//! Two artifacts are written together, for two readers:
//!   * `state.env`  — `KEY="VALUE"` lines, sourceable from shell and greppable.
//!   * `state.json` — canonical JSON object, for the agent and tooling.
//!
//! Round-trip (`save` → `load`) is lossless for values containing spaces and
//! embedded double quotes, which the round-trip tests pin down explicitly.

use crate::error::Result;
use crate::util::trim_quotes;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

/// An insertion-ordered string map backing one stage's state.
#[derive(Debug, Default, Clone)]
pub struct State {
    stage_dir: PathBuf,
    keys: Vec<String>,
    values: Vec<String>,
    index: HashMap<String, usize>,
}

impl State {
    /// Create an empty in-memory state rooted at `stage_dir` (not yet loaded).
    pub fn new(stage_dir: impl Into<PathBuf>) -> Self {
        Self {
            stage_dir: stage_dir.into(),
            keys: Vec::new(),
            values: Vec::new(),
            index: HashMap::new(),
        }
    }

    /// Path to `state.env` for this stage.
    pub fn env_path(&self) -> PathBuf {
        self.stage_dir.join("state.env")
    }

    /// Path to `state.json` for this stage.
    pub fn json_path(&self) -> PathBuf {
        self.stage_dir.join("state.json")
    }

    /// Load `state.env` from disk into memory, replacing any current contents.
    /// A missing file is not an error — it yields an empty state.
    pub fn load(&mut self) -> Result<()> {
        self.clear();
        let path = self.env_path();
        let Ok(content) = std::fs::read_to_string(&path) else {
            return Ok(());
        };
        for line in content.lines() {
            let line = line.trim_start();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let Some((k, v)) = line.split_once('=') else {
                continue;
            };
            // Strip wrapping quotes (matching how `save` serializes), then
            // unescape any `\"` inside.
            let v = trim_quotes(v).replace("\\\"", "\"");
            self.set(k, v);
        }
        Ok(())
    }

    fn clear(&mut self) {
        self.keys.clear();
        self.values.clear();
        self.index.clear();
    }

    /// Insert or update a key. Order is preserved on first insert.
    pub fn set(&mut self, key: impl Into<String>, value: impl Into<String>) {
        let key = key.into();
        let value = value.into();
        if let Some(&i) = self.index.get(&key) {
            self.values[i] = value;
        } else {
            let i = self.keys.len();
            self.keys.push(key.clone());
            self.values.push(value);
            self.index.insert(key, i);
        }
    }

    /// Get a key's value, or `None` if absent.
    pub fn get(&self, key: &str) -> Option<&str> {
        self.index.get(key).map(|&i| self.values[i].as_str())
    }

    /// Get a key's value, or `default` if absent.
    pub fn get_or<'a>(&'a self, key: &str, default: &'a str) -> &'a str {
        self.get(key).unwrap_or(default)
    }

    /// Get an owned value or owned default — convenience for call sites that
    /// need to keep the value past a later `set`.
    pub fn get_owned(&self, key: &str, default: &str) -> String {
        self.get(key).unwrap_or(default).to_string()
    }

    /// Whether a key is present.
    pub fn has(&self, key: &str) -> bool {
        self.index.contains_key(key)
    }

    /// `last_step` value, or empty string.
    pub fn resumable_step(&self) -> &str {
        self.get_or("last_step", "")
    }

    /// Serialize the current state to `state.env` (sourceable) form.
    pub fn to_env(&self) -> String {
        let mut out =
            String::from("# written by migration-assistant; do not edit while it is running.\n");
        for (k, v) in self.keys.iter().zip(self.values.iter()) {
            let escaped = v.replace('"', "\\\"");
            out.push_str(&format!("{k}=\"{escaped}\"\n"));
        }
        out
    }

    /// Serialize the current state to canonical JSON (`state.json`).
    pub fn to_json(&self) -> String {
        let map: serde_json::Map<String, serde_json::Value> = self
            .keys
            .iter()
            .zip(self.values.iter())
            .map(|(k, v)| (k.clone(), serde_json::Value::String(v.clone())))
            .collect();
        serde_json::to_string_pretty(&serde_json::Value::Object(map))
            .unwrap_or_else(|_| "{}".into())
    }

    /// Write both `state.env` and `state.json` atomically (write-temp + rename),
    /// creating the stage directory if needed — `state_save`.
    pub fn save(&self) -> Result<()> {
        std::fs::create_dir_all(&self.stage_dir)?;
        write_atomic(&self.env_path(), &self.to_env())?;
        write_atomic(&self.json_path(), &self.to_json())?;
        Ok(())
    }

    /// Move `state.env`/`state.json` into `archive/<timestamp>/` and clear the
    /// in-memory state.
    pub fn archive(&mut self) -> Result<PathBuf> {
        let ts = archive_timestamp();
        let dest = self.stage_dir.join("archive").join(&ts);
        std::fs::create_dir_all(&dest)?;
        for name in ["state.env", "state.json"] {
            let src = self.stage_dir.join(name);
            if src.is_file() {
                std::fs::rename(&src, dest.join(name))?;
            }
        }
        self.clear();
        Ok(dest)
    }
}

fn write_atomic(path: &Path, content: &str) -> Result<()> {
    let tmp = path.with_extension(format!("tmp.{}", std::process::id()));
    std::fs::write(&tmp, content)?;
    std::fs::rename(&tmp, path)?;
    Ok(())
}

/// A monotonic-ish archive directory name with sub-second precision so rapid
/// successive archives don't collide.
fn archive_timestamp() -> String {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    format!("{}_{:09}", now.as_secs(), now.subsec_nanos())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_state() -> (tempfile::TempDir, State) {
        let dir = tempfile::tempdir().unwrap();
        let stage = dir.path().join("default");
        (dir, State::new(stage))
    }

    #[test]
    fn save_load_round_trips_single_key() {
        let (_d, mut s) = temp_state();
        s.load().unwrap();
        s.set("FOO", "bar");
        s.save().unwrap();

        let mut s2 = State::new(s.env_path().parent().unwrap());
        s2.load().unwrap();
        assert_eq!(s2.get("FOO"), Some("bar"));
    }

    #[test]
    fn get_returns_default_when_absent() {
        let (_d, s) = temp_state();
        assert_eq!(s.get_or("MISSING", "fallback"), "fallback");
    }

    #[test]
    fn save_writes_both_env_and_json() {
        let (_d, mut s) = temp_state();
        s.set("REGION", "us-west-2");
        s.save().unwrap();

        assert!(s.env_path().is_file());
        assert!(s.json_path().is_file());
        let env = std::fs::read_to_string(s.env_path()).unwrap();
        assert!(env.contains("REGION=\"us-west-2\""));
        let json = std::fs::read_to_string(s.json_path()).unwrap();
        assert!(json.contains("\"REGION\""));
    }

    #[test]
    fn handles_values_with_spaces_and_quotes() {
        let (_d, mut s) = temp_state();
        let note = r#"arn:aws:iam::1234:user/"john doe""#;
        s.set("NOTE", note);
        s.save().unwrap();

        let mut s2 = State::new(s.env_path().parent().unwrap());
        s2.load().unwrap();
        assert_eq!(s2.get("NOTE"), Some(note));
        // And the JSON must be valid and carry the same value.
        let json: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(s.json_path()).unwrap()).unwrap();
        assert_eq!(json["NOTE"], serde_json::Value::String(note.to_string()));
    }

    #[test]
    fn archive_moves_state_into_timestamp_dir() {
        let (_d, mut s) = temp_state();
        s.set("FOO", "bar");
        s.save().unwrap();
        let stage_dir = s.env_path().parent().unwrap().to_path_buf();

        s.archive().unwrap();
        assert!(!stage_dir.join("state.env").exists());
        assert!(!stage_dir.join("state.json").exists());

        let archive = stage_dir.join("archive");
        assert!(archive.is_dir());
        let found = std::fs::read_dir(&archive)
            .unwrap()
            .filter_map(|e| e.ok())
            .filter(|e| e.path().join("state.env").is_file())
            .count();
        assert_eq!(found, 1);
    }

    #[test]
    fn resumable_step_empty_then_value() {
        let (_d, mut s) = temp_state();
        assert_eq!(s.resumable_step(), "");
        s.set("last_step", "helm_done");
        s.save().unwrap();

        let mut s2 = State::new(s.env_path().parent().unwrap());
        s2.load().unwrap();
        assert_eq!(s2.resumable_step(), "helm_done");
    }

    #[test]
    fn insertion_order_preserved_in_env() {
        let (_d, mut s) = temp_state();
        s.set("B", "2");
        s.set("A", "1");
        s.set("B", "two"); // update keeps original position
        let env = s.to_env();
        let b_pos = env.find("B=").unwrap();
        let a_pos = env.find("A=").unwrap();
        assert!(b_pos < a_pos, "B was set first, must serialize first");
        assert!(env.contains("B=\"two\""));
    }
}
