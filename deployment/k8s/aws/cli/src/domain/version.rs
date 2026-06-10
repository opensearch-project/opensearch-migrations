//! CLI + Migration Assistant version resolution.
//!
//! `CLI_VERSION` is baked at build time; `resolve_cli_version` resolves the
//! effective CLI version at runtime by searching for a `VERSION.txt` near the
//! binary. This allows CI to build the binary once and stamp VERSION.txt into
//! the install layout later. A `cargo build` from source with no VERSION.txt
//! still works (falls back to `CLI_VERSION` or `FALLBACK_VERSION`).
//!
//! `resolve_ma_version` resolves the MA artifact version with a defined
//! priority order. Resolution is pure logic that takes the candidate inputs,
//! so it's testable without network — the actual GitHub release fetch is
//! layered on top at the call site.

use std::path::Path;

/// How many parent directories above the binary we'll search for
/// VERSION.txt before giving up. The deployed layout puts it one level
/// up; we tolerate 3 to handle reasonable variations (e.g. binary in
/// libexec/, bundle/bin/, etc.) without making the search unbounded.
const MAX_ANCESTOR_DEPTH: usize = 3;

/// Fallback version used when neither VERSION.txt nor CLI_VERSION resolves.
pub const FALLBACK_VERSION: &str = "0.0.0-dev";

/// The CLI's own version, baked at build time.
///
/// The release packaging (gradle `:deployment:k8s:aws:packageMigrationAssistantCli`)
/// sets `CLI_VERSION=<tag>` in the build environment; `option_env!` captures it
/// into the binary. A plain `cargo build` with no `CLI_VERSION` set falls back
/// to `FALLBACK_VERSION`.
pub const CLI_VERSION: &str = match option_env!("CLI_VERSION") {
    Some(v) => v,
    None => FALLBACK_VERSION,
};

/// Resolve the CLI version string to display in `--version` output.
///
/// Resolution order:
///   1. `VERSION.txt` found within [`MAX_ANCESTOR_DEPTH`] ancestor directories
///      above the running executable (trimmed, non-empty).
///   2. Compile-time `CLI_VERSION` (set by the build env, or `FALLBACK_VERSION`).
///
/// This allows CI to build the binary once and stamp VERSION.txt into the
/// install layout later. A `cargo run` from source with no VERSION.txt uses
/// the compile-time constant.
pub fn resolve_cli_version() -> String {
    if let Some(v) = resolve_from_exe() {
        return v;
    }
    CLI_VERSION.to_string()
}

/// Walk ancestors of the running executable looking for VERSION.txt.
fn resolve_from_exe() -> Option<String> {
    let exe = std::env::current_exe().ok()?;
    // canonicalize() resolves symlinks (e.g. /usr/local/bin →
    // /opt/migration-assistant/bin) so we walk the real layout.
    let exe = exe.canonicalize().unwrap_or(exe);
    resolve_from_path(&exe)
}

/// Pure version of [`resolve_cli_version`]: takes a binary path, walks its
/// ancestors looking for VERSION.txt. Public so tests can drive it against
/// tempdir-built layouts without mutating process state.
pub fn resolve_from_path(exe_path: &Path) -> Option<String> {
    exe_path
        .ancestors()
        .skip(1) // skip the binary itself; start at its parent dir
        .take(MAX_ANCESTOR_DEPTH)
        .filter_map(|dir| {
            let candidate = dir.join("VERSION.txt");
            std::fs::read_to_string(&candidate).ok()
        })
        .map(|s| s.trim().to_string())
        .find(|s| !s.is_empty())
}

/// GitHub "latest release" API for the MA artifacts.
pub const MA_RELEASES_API: &str =
    "https://api.github.com/repos/opensearch-project/opensearch-migrations/releases/latest";

/// Resolve the MA artifact version from the candidate inputs, in priority
/// order:
///
///   1. explicit override (`--version` / `MA_VERSION` env)
///   2. cached value from this stage's state
///   3. value fetched from the releases API (caller supplies it, may be `None`)
///   4. last-resort fallback if non-empty
///
/// Returns `Err` when nothing resolves, so the caller can surface the
/// "pass --version or check network" message.
pub fn resolve_ma_version(
    explicit: Option<&str>,
    cached: Option<&str>,
    fetched: Option<&str>,
    last_resort: &str,
) -> crate::Result<String> {
    for v in [explicit, cached, fetched].into_iter().flatten() {
        let v = strip_v_prefix(v);
        if !v.is_empty() {
            return Ok(v.to_string());
        }
    }
    if !last_resort.is_empty() {
        return Ok(strip_v_prefix(last_resort).to_string());
    }
    Err(crate::Error::die(format!(
        "could not determine Migration Assistant version: pass --version <ver> or check network access to {MA_RELEASES_API}"
    )))
}

/// Extract a `tag_name` from a GitHub releases-API JSON body, stripping a
/// leading `v`. Returns `None` if absent or unparseable.
pub fn parse_release_tag(body: &str) -> Option<String> {
    let v: serde_json::Value = serde_json::from_str(body).ok()?;
    let tag = v.get("tag_name")?.as_str()?;
    Some(strip_v_prefix(tag).to_string())
}

fn strip_v_prefix(s: &str) -> &str {
    s.strip_prefix('v').unwrap_or(s)
}

/// Compare two semver-like version strings. Returns true if `remote` is newer
/// than `local`. Non-numeric segments are compared lexicographically.
pub fn is_newer(remote: &str, local: &str) -> bool {
    let parse = |v: &str| -> Vec<u64> {
        v.split('.')
            .map(|s| s.parse::<u64>().unwrap_or(0))
            .collect()
    };
    let r = parse(strip_v_prefix(remote));
    let l = parse(strip_v_prefix(local));
    for i in 0..r.len().max(l.len()) {
        let rv = r.get(i).copied().unwrap_or(0);
        let lv = l.get(i).copied().unwrap_or(0);
        if rv > lv {
            return true;
        }
        if rv < lv {
            return false;
        }
    }
    false
}

/// Check whether a newer CLI version is available. Skips the network call if
/// a check was already performed in the last 24 hours (timestamp cached in
/// `~/.migration-assistant/last-update-check`). Returns `Some(version)` if a
/// newer release exists, `None` otherwise (including on any failure).
///
/// `update_check_url` is read from `manifest.json → build.updateCheckUrl`.
/// It's the single source of truth for where to check — no hardcoded fallback.
/// The upstream GitHub bundle sets it to the GitHub releases API; the Amazon
/// pack sets it to the Solutions S3 VERSION.txt endpoint.
pub fn check_for_update(update_check_url: &str) -> Option<String> {
    if update_check_url.is_empty() {
        return None;
    }
    let current = resolve_cli_version();
    if current == FALLBACK_VERSION {
        return None;
    }
    if !should_check_today() {
        return None;
    }
    let remote = fetch_remote_version(update_check_url)?;
    record_check();
    if is_newer(&remote, &current) {
        Some(remote)
    } else {
        None
    }
}

/// Force a check regardless of the cache (used by `migration-assistant update`).
pub fn check_for_update_now(update_check_url: &str) -> Option<String> {
    if update_check_url.is_empty() {
        return None;
    }
    let current = resolve_cli_version();
    if current == FALLBACK_VERSION {
        return None;
    }
    let remote = fetch_remote_version(update_check_url)?;
    record_check();
    if is_newer(&remote, &current) {
        Some(remote)
    } else {
        None
    }
}

/// Fetch the remote version from the given URL. Tries to parse as JSON first
/// (`tag_name` field for GitHub API, or `version` field), then falls back to
/// treating the entire body as a plain-text version (for S3 VERSION.txt).
fn fetch_remote_version(url: &str) -> Option<String> {
    let body = fetch_url(url)?;
    // JSON with tag_name (GitHub releases API)
    if let Some(v) = parse_release_tag(&body) {
        return Some(v);
    }
    // JSON with a "version" field
    if let Ok(parsed) = serde_json::from_str::<serde_json::Value>(&body) {
        if let Some(v) = parsed.get("version").and_then(|v| v.as_str()) {
            return Some(strip_v_prefix(v).to_string());
        }
    }
    // Plain text: the body IS the version (e.g. S3 VERSION.txt)
    let trimmed = body.trim();
    if !trimmed.is_empty() && trimmed.len() < 50 {
        return Some(strip_v_prefix(trimmed).to_string());
    }
    None
}

/// The `latest.json` URL at the stable releases path.
pub const LATEST_JSON_URL: &str =
    "https://github.com/opensearch-project/opensearch-migrations/releases/latest/download/latest.json";

/// Parsed `latest.json` manifest from the release.
#[derive(Debug, Clone)]
pub struct ReleaseManifest {
    pub version: String,
    pub tarball_url: String,
    pub binary_url: Option<String>,
    pub sha256: Option<String>,
}

/// Detect the current platform key: `x86_64-linux`, `aarch64-linux`,
/// `x86_64-darwin`, `aarch64-darwin`.
pub fn platform_key() -> String {
    let arch = if cfg!(target_arch = "x86_64") {
        "x86_64"
    } else if cfg!(target_arch = "aarch64") {
        "aarch64"
    } else {
        "unknown"
    };
    let os = if cfg!(target_os = "linux") {
        "linux"
    } else if cfg!(target_os = "macos") {
        "darwin"
    } else {
        "unknown"
    };
    format!("{arch}-{os}")
}

/// Fetch and parse `latest.json`, resolving the binary URL for this platform.
pub fn fetch_release_manifest() -> Option<ReleaseManifest> {
    let body = fetch_url(LATEST_JSON_URL)?;
    let v: serde_json::Value = serde_json::from_str(&body).ok()?;
    let version = v.get("version")?.as_str()?.to_string();
    let tarball_url = v
        .get("tarball")
        .and_then(|t| t.as_str())
        .unwrap_or("")
        .to_string();
    let key = platform_key();
    let binary_url = v
        .get("binaries")
        .and_then(|b| b.get(&key))
        .and_then(|entry| {
            if entry.is_string() {
                entry.as_str().map(str::to_string)
            } else {
                entry
                    .get("url")
                    .and_then(|u| u.as_str())
                    .map(str::to_string)
            }
        });
    let sha256 = v
        .get("binaries")
        .and_then(|b| b.get(&key))
        .and_then(|entry| entry.get("sha256"))
        .and_then(|s| s.as_str())
        .map(str::to_string);
    Some(ReleaseManifest {
        version,
        tarball_url,
        binary_url,
        sha256,
    })
}

/// Download a URL to a local path. Returns true on success.
pub fn download_to(url: &str, dest: &std::path::Path) -> bool {
    std::process::Command::new("curl")
        .args(["-fsSL", "--max-time", "120", "-o"])
        .arg(dest)
        .arg(url)
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

fn fetch_url(url: &str) -> Option<String> {
    let output = std::process::Command::new("curl")
        .args(["-sfL", "--max-time", "10", url])
        .output()
        .ok()?;
    if output.status.success() {
        String::from_utf8(output.stdout).ok()
    } else {
        None
    }
}

fn cache_dir() -> Option<std::path::PathBuf> {
    let home = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE"))?;
    Some(std::path::PathBuf::from(home).join(".migration-assistant"))
}

fn cache_file() -> Option<std::path::PathBuf> {
    Some(cache_dir()?.join("last-update-check"))
}

fn should_check_today() -> bool {
    let Some(path) = cache_file() else {
        return true;
    };
    let Ok(contents) = std::fs::read_to_string(&path) else {
        return true;
    };
    let Ok(ts) = contents.trim().parse::<u64>() else {
        return true;
    };
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    now.saturating_sub(ts) > 86400
}

fn record_check() {
    let Some(path) = cache_file() else { return };
    let Some(dir) = cache_dir() else { return };
    let _ = std::fs::create_dir_all(dir);
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let _ = std::fs::write(path, now.to_string());
}

/// The release download URL for a given version.
pub fn release_url(version: &str) -> String {
    format!("https://github.com/opensearch-project/opensearch-migrations/releases/tag/{version}")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::tempdir;

    // -----------------------------------------------------------------------
    // resolve_from_path / resolve_cli_version tests
    // -----------------------------------------------------------------------

    /// Layout: <tmp>/bin/migration-assistant  +  <tmp>/VERSION.txt
    /// Mirrors the production install layout.
    #[test]
    fn finds_version_one_level_above_bin() {
        let tmp = tempdir().unwrap();
        fs::create_dir(tmp.path().join("bin")).unwrap();
        let exe = tmp.path().join("bin").join("migration-assistant");
        fs::write(&exe, b"").unwrap();
        fs::write(tmp.path().join("VERSION.txt"), "1.4.2\n").unwrap();

        assert_eq!(resolve_from_path(&exe), Some("1.4.2".to_string()));
    }

    /// VERSION.txt directly next to the binary.
    #[test]
    fn finds_version_in_same_dir() {
        let tmp = tempdir().unwrap();
        let exe = tmp.path().join("migration-assistant");
        fs::write(&exe, b"").unwrap();
        fs::write(tmp.path().join("VERSION.txt"), "2.0.0").unwrap();

        assert_eq!(resolve_from_path(&exe), Some("2.0.0".to_string()));
    }

    /// No VERSION.txt anywhere in the ancestor chain → None
    /// (the public wrapper translates this to CLI_VERSION).
    #[test]
    fn returns_none_when_no_version_file() {
        let tmp = tempdir().unwrap();
        fs::create_dir(tmp.path().join("bin")).unwrap();
        let exe = tmp.path().join("bin").join("migration-assistant");
        fs::write(&exe, b"").unwrap();

        assert_eq!(resolve_from_path(&exe), None);
    }

    /// Empty / whitespace-only VERSION.txt → None (fall back to
    /// compile-time constant rather than report empty string).
    #[test]
    fn rejects_empty_version_file() {
        let tmp = tempdir().unwrap();
        let exe = tmp.path().join("migration-assistant");
        fs::write(&exe, b"").unwrap();
        fs::write(tmp.path().join("VERSION.txt"), "   \n  \n").unwrap();

        assert_eq!(resolve_from_path(&exe), None);
    }

    /// Trailing whitespace and newlines are trimmed.
    #[test]
    fn trims_whitespace_and_newlines() {
        let tmp = tempdir().unwrap();
        let exe = tmp.path().join("migration-assistant");
        fs::write(&exe, b"").unwrap();
        fs::write(tmp.path().join("VERSION.txt"), "  3.1.4  \n\n").unwrap();

        assert_eq!(resolve_from_path(&exe), Some("3.1.4".to_string()));
    }

    /// Closer VERSION.txt wins over a more distant one.
    #[test]
    fn nearer_version_file_wins() {
        let tmp = tempdir().unwrap();
        let near = tmp.path().join("near");
        let bin = near.join("bin");
        fs::create_dir_all(&bin).unwrap();
        let exe = bin.join("migration-assistant");
        fs::write(&exe, b"").unwrap();
        fs::write(near.join("VERSION.txt"), "near-version").unwrap();
        fs::write(tmp.path().join("VERSION.txt"), "far-version").unwrap();

        assert_eq!(resolve_from_path(&exe), Some("near-version".to_string()));
    }

    /// VERSION.txt outside the search depth (4+ levels up) is NOT found.
    #[test]
    fn ignores_version_file_beyond_max_depth() {
        let tmp = tempdir().unwrap();
        let deep = tmp.path().join("a").join("b").join("c").join("d");
        fs::create_dir_all(&deep).unwrap();
        let exe = deep.join("migration-assistant");
        fs::write(&exe, b"").unwrap();
        fs::write(tmp.path().join("VERSION.txt"), "should-not-find").unwrap();

        assert_eq!(resolve_from_path(&exe), None);
    }

    /// The public resolve_cli_version() always returns SOMETHING (never
    /// panics, never returns empty).
    #[test]
    fn public_resolver_never_returns_empty() {
        let v = resolve_cli_version();
        assert!(!v.is_empty(), "resolve_cli_version() returned empty string");
    }

    // -----------------------------------------------------------------------
    // resolve_ma_version tests
    // -----------------------------------------------------------------------

    #[test]
    fn explicit_wins() {
        assert_eq!(
            resolve_ma_version(Some("3.2.1"), Some("3.0.0"), Some("9.9.9"), "1.0.0").unwrap(),
            "3.2.1"
        );
    }

    #[test]
    fn cached_when_no_explicit() {
        assert_eq!(
            resolve_ma_version(None, Some("3.0.0"), Some("9.9.9"), "1.0.0").unwrap(),
            "3.0.0"
        );
    }

    #[test]
    fn fetched_when_no_explicit_or_cached() {
        assert_eq!(
            resolve_ma_version(None, None, Some("9.9.9"), "1.0.0").unwrap(),
            "9.9.9"
        );
    }

    #[test]
    fn last_resort_when_all_else_empty() {
        assert_eq!(
            resolve_ma_version(None, None, None, "1.0.0").unwrap(),
            "1.0.0"
        );
    }

    #[test]
    fn errors_when_nothing_resolves() {
        assert!(resolve_ma_version(None, None, None, "").is_err());
    }

    #[test]
    fn strips_leading_v() {
        assert_eq!(
            resolve_ma_version(Some("v3.2.1"), None, None, "").unwrap(),
            "3.2.1"
        );
    }

    #[test]
    fn empty_strings_skip_to_next_candidate() {
        // An empty explicit/cached should not win; fall through.
        assert_eq!(
            resolve_ma_version(Some(""), Some(""), Some("3.2.1"), "").unwrap(),
            "3.2.1"
        );
    }

    #[test]
    fn parses_release_tag() {
        assert_eq!(
            parse_release_tag(r#"{"tag_name":"3.2.1"}"#),
            Some("3.2.1".to_string())
        );
        assert_eq!(
            parse_release_tag(r#"{"tag_name":"v2.9.0"}"#),
            Some("2.9.0".to_string())
        );
        assert_eq!(parse_release_tag(r#"{"other":"x"}"#), None);
        assert_eq!(parse_release_tag("not json"), None);
    }

    #[test]
    fn is_newer_detects_upgrade() {
        assert!(is_newer("3.3.0", "3.2.1"));
        assert!(is_newer("4.0.0", "3.9.9"));
        assert!(is_newer("3.2.2", "3.2.1"));
    }

    #[test]
    fn is_newer_rejects_same_or_older() {
        assert!(!is_newer("3.2.1", "3.2.1"));
        assert!(!is_newer("3.2.0", "3.2.1"));
        assert!(!is_newer("2.9.0", "3.0.0"));
    }

    #[test]
    fn is_newer_handles_v_prefix() {
        assert!(is_newer("v3.3.0", "3.2.1"));
        assert!(is_newer("3.3.0", "v3.2.1"));
    }
}
