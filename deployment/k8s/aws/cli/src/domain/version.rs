//! CLI + Migration Assistant version resolution.
//!
//! `CLI_VERSION` is baked at build time; `resolve_ma_version` resolves the MA
//! artifact version with a defined priority order. Resolution is pure logic
//! that takes the candidate inputs, so it's testable without network — the
//! actual GitHub release fetch is layered on top at the call site.

/// The CLI's own version, baked at build time.
///
/// The release packaging (gradle `:deployment:k8s:aws:packageMigrationAssistantCli`)
/// sets `CLI_VERSION=<tag>` in the build environment; `option_env!` captures it
/// into the binary. A plain `cargo build` with no `CLI_VERSION` set falls back
/// to `0.0.0-dev`.
pub const CLI_VERSION: &str = match option_env!("CLI_VERSION") {
    Some(v) => v,
    None => "0.0.0-dev",
};

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

#[cfg(test)]
mod tests {
    use super::*;

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
}
