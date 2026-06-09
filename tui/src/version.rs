// version.rs — runtime version resolution from a sibling VERSION.txt.
//
// Why this exists:
//   The migration-tui crate ships with a perma-placeholder version
//   (`0.0.0-dev`) in Cargo.toml. The real release version comes from
//   the install bundle, NOT compile time — see Model C in
//   tui/build.gradle.kts. That keeps the source tree evergreen and
//   means the binary is built once and shipped many times.
//
//   So `--version` resolves the version at *runtime* by reading
//   VERSION.txt from the install layout, which looks like:
//
//       /opt/migration-assistant/
//       ├── bin/migration-tui      ← we're here
//       └── VERSION.txt            ← read this
//
//   Any deployment that places VERSION.txt within 3 levels above the
//   binary (parent, grandparent, great-grandparent) resolves cleanly.
//
//   `cargo run` from the source tree can't find one and falls back to
//   `0.0.0-dev`, which honestly reflects what was built.

use std::path::Path;

/// How many parent directories above the binary we'll search for
/// VERSION.txt before giving up. The deployed layout puts it one level
/// up; we tolerate 3 to handle reasonable variations (e.g. binary in
/// libexec/, bundle/bin/, etc.) without making the search unbounded.
const MAX_ANCESTOR_DEPTH: usize = 3;

/// The placeholder used in Cargo.toml; also our fallback when no
/// VERSION.txt is present.
pub const FALLBACK_VERSION: &str = "0.0.0-dev";

/// Resolve the version string to display in `--version` output.
///
/// Returns the contents of the nearest VERSION.txt above the running
/// executable (within MAX_ANCESTOR_DEPTH levels), trimmed.
/// Falls back to FALLBACK_VERSION when no readable, non-empty
/// VERSION.txt is found.
pub fn resolve_version() -> String {
    let exe = match std::env::current_exe() {
        Ok(p) => p,
        Err(_) => return FALLBACK_VERSION.to_string(),
    };
    // canonicalize() resolves symlinks (e.g. /usr/local/bin →
    // /opt/migration-assistant/bin) so we walk the real layout.
    let exe = exe.canonicalize().unwrap_or(exe);
    resolve_from_path(&exe).unwrap_or_else(|| FALLBACK_VERSION.to_string())
}

/// Pure version of [`resolve_version`]: takes a binary path, walks its
/// ancestors looking for VERSION.txt. Public so tests can drive it
/// against tempdir-built layouts without mutating process state.
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::tempdir;

    /// Layout: <tmp>/bin/migration-tui  +  <tmp>/VERSION.txt
    /// Mirrors the production install layout.
    #[test]
    fn finds_version_one_level_above_bin() {
        let tmp = tempdir().unwrap();
        fs::create_dir(tmp.path().join("bin")).unwrap();
        let exe = tmp.path().join("bin").join("migration-tui");
        fs::write(&exe, b"").unwrap();
        fs::write(tmp.path().join("VERSION.txt"), "1.4.2\n").unwrap();

        assert_eq!(resolve_from_path(&exe), Some("1.4.2".to_string()));
    }

    /// VERSION.txt directly next to the binary.
    #[test]
    fn finds_version_in_same_dir() {
        let tmp = tempdir().unwrap();
        let exe = tmp.path().join("migration-tui");
        fs::write(&exe, b"").unwrap();
        fs::write(tmp.path().join("VERSION.txt"), "2.0.0").unwrap();

        assert_eq!(resolve_from_path(&exe), Some("2.0.0".to_string()));
    }

    /// No VERSION.txt anywhere in the ancestor chain → None
    /// (the public wrapper translates this to FALLBACK_VERSION).
    #[test]
    fn returns_none_when_no_version_file() {
        let tmp = tempdir().unwrap();
        fs::create_dir(tmp.path().join("bin")).unwrap();
        let exe = tmp.path().join("bin").join("migration-tui");
        fs::write(&exe, b"").unwrap();
        // intentionally no VERSION.txt

        assert_eq!(resolve_from_path(&exe), None);
    }

    /// Empty / whitespace-only VERSION.txt → None (fall back to
    /// placeholder rather than report empty string).
    #[test]
    fn rejects_empty_version_file() {
        let tmp = tempdir().unwrap();
        let exe = tmp.path().join("migration-tui");
        fs::write(&exe, b"").unwrap();
        fs::write(tmp.path().join("VERSION.txt"), "   \n  \n").unwrap();

        assert_eq!(resolve_from_path(&exe), None);
    }

    /// Trailing whitespace and newlines are trimmed.
    #[test]
    fn trims_whitespace_and_newlines() {
        let tmp = tempdir().unwrap();
        let exe = tmp.path().join("migration-tui");
        fs::write(&exe, b"").unwrap();
        fs::write(tmp.path().join("VERSION.txt"), "  3.1.4  \n\n").unwrap();

        assert_eq!(resolve_from_path(&exe), Some("3.1.4".to_string()));
    }

    /// Closer VERSION.txt wins over a more distant one. This matters
    /// when the binary is bundled inside a larger tree that happens to
    /// have a VERSION.txt elsewhere — we want the one belonging to OUR
    /// install.
    #[test]
    fn nearer_version_file_wins() {
        let tmp = tempdir().unwrap();
        let near = tmp.path().join("near");
        let bin = near.join("bin");
        fs::create_dir_all(&bin).unwrap();
        let exe = bin.join("migration-tui");
        fs::write(&exe, b"").unwrap();
        // Near (1 level up from bin/): "near-version"
        fs::write(near.join("VERSION.txt"), "near-version").unwrap();
        // Far (3 levels up): "far-version"
        fs::write(tmp.path().join("VERSION.txt"), "far-version").unwrap();

        assert_eq!(resolve_from_path(&exe), Some("near-version".to_string()));
    }

    /// VERSION.txt outside the search depth (4+ levels up) is NOT
    /// found. This guards against accidentally picking up an unrelated
    /// VERSION.txt high in the filesystem.
    #[test]
    fn ignores_version_file_beyond_max_depth() {
        let tmp = tempdir().unwrap();
        // Layout: <tmp>/a/b/c/d/migration-tui   (5 levels deep)
        //         <tmp>/VERSION.txt             (5 levels above bin)
        let deep = tmp.path().join("a").join("b").join("c").join("d");
        fs::create_dir_all(&deep).unwrap();
        let exe = deep.join("migration-tui");
        fs::write(&exe, b"").unwrap();
        fs::write(tmp.path().join("VERSION.txt"), "should-not-find").unwrap();

        assert_eq!(resolve_from_path(&exe), None);
    }

    /// The public resolve_version() always returns SOMETHING (never
    /// panics, never returns empty).
    #[test]
    fn public_resolver_never_returns_empty() {
        let v = resolve_version();
        assert!(!v.is_empty(), "resolve_version() returned empty string");
    }
}
