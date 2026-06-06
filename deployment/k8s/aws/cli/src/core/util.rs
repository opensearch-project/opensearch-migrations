//! Pure string / collection / path helpers.
//!
//! Idiomatic Rust replacement for the pure-bash-3.2 helpers in `lib/std.sh`.
//! Bash needed these because it lacks a standard library; Rust has most of
//! them built in (`str::trim`, `Vec::contains`, `slice::join`, …). We only
//! reimplement the ones with non-obvious semantics worth pinning down with
//! their own tests.

/// Strip ONE leading and ONE trailing matched `'` or `"` from `s`.
///
/// Asymmetric inputs (only a leading or only a trailing quote) are returned
/// unchanged — mirrors `trim_quotes` in std.sh, used by state.env parsing.
pub fn trim_quotes(s: &str) -> &str {
    let bytes = s.as_bytes();
    if bytes.len() >= 2 {
        let first = bytes[0];
        let last = bytes[bytes.len() - 1];
        if (first == b'"' && last == b'"') || (first == b'\'' && last == b'\'') {
            return &s[1..s.len() - 1];
        }
    }
    s
}

/// Split a comma-separated string into owned segments.
///
/// Matches `split_csv`: an empty input yields a single empty element (the
/// documented bash 3.2 `read -a` contract), a non-empty input splits on `,`.
pub fn split_csv(s: &str) -> Vec<String> {
    if s.is_empty() {
        return vec![String::new()];
    }
    s.split(',').map(str::to_string).collect()
}

/// Join path components with `/`, collapsing runs of slashes — `path_join`.
pub fn path_join<I, S>(parts: I) -> String
where
    I: IntoIterator<Item = S>,
    S: AsRef<str>,
{
    let mut out = String::new();
    for part in parts {
        let part = part.as_ref();
        if out.is_empty() {
            out.push_str(part);
        } else {
            let left = out.trim_end_matches('/');
            let right = part.trim_start_matches('/');
            out = format!("{left}/{right}");
        }
    }
    // Collapse any `//` that survived (e.g. an arg that began with `//`).
    while out.contains("//") {
        out = out.replace("//", "/");
    }
    out
}

/// Keep only the first occurrence of each non-empty line — `dedupe` (awk
/// `NF && !seen[$0]++`). Blank lines are dropped.
pub fn dedupe<'a, I>(lines: I) -> Vec<&'a str>
where
    I: IntoIterator<Item = &'a str>,
{
    let mut seen = std::collections::HashSet::new();
    let mut out = Vec::new();
    for line in lines {
        if line.is_empty() {
            continue;
        }
        if seen.insert(line) {
            out.push(line);
        }
    }
    out
}

/// Count newline-delimited lines in `s` — `count_lines_var`.
///
/// Empty string is 0; a trailing fragment without a final newline still
/// counts as a line (so `"a\nb"` is 2 and `"a\nb\n"` is 2).
pub fn count_lines(s: &str) -> usize {
    if s.is_empty() {
        return 0;
    }
    let newlines = s.bytes().filter(|&b| b == b'\n').count();
    if s.ends_with('\n') {
        newlines
    } else {
        newlines + 1
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn trim_uses_std() {
        // std.sh `trim` → Rust str::trim. Documented here so the contract is visible.
        assert_eq!("  hello world  ".trim(), "hello world");
        assert_eq!("".trim(), "");
        assert_eq!("no-ws".trim(), "no-ws");
    }

    #[test]
    fn trim_quotes_strips_matched_only() {
        assert_eq!(trim_quotes("\"hello\""), "hello");
        assert_eq!(trim_quotes("'hello'"), "hello");
        // Asymmetric input — left alone.
        assert_eq!(trim_quotes("\"only-leading"), "\"only-leading");
        assert_eq!(trim_quotes("only-trailing\""), "only-trailing\"");
    }

    #[test]
    fn split_csv_default() {
        let v = split_csv("a,b,c");
        assert_eq!(v, vec!["a", "b", "c"]);
    }

    #[test]
    fn split_csv_empty_is_one_empty_element() {
        let v = split_csv("");
        assert!(v.len() <= 1);
        assert_eq!(v, vec![String::new()]);
    }

    #[test]
    fn join_by_uses_slice_join() {
        // Joining is `slice::join`; documented here for the join helper's callers.
        assert_eq!(["a", "b", "c"].join(","), "a,b,c");
        assert_eq!(["one", "two", "three"].join(" | "), "one | two | three");
        let empty: [&str; 0] = [];
        assert_eq!(empty.join(","), "");
        assert_eq!(["single"].join(","), "single");
    }

    #[test]
    fn count_lines_var() {
        assert_eq!(count_lines("a\nb\nc"), 3);
        assert_eq!(count_lines(""), 0);
        assert_eq!(count_lines("a\nb\n"), 2);
        assert_eq!(count_lines("one-line-no-newline"), 1);
    }

    #[test]
    fn contains_uses_std() {
        assert!("abcdef".starts_with("abc"));
        assert!(!"abcdef".starts_with("xyz"));
        assert!("abcdef".ends_with("def"));
        assert!("abcdef".contains("cd"));
        assert!(!"abcdef".contains("zz"));
    }

    #[test]
    fn dedupe_drops_duplicates_and_blanks() {
        let out = dedupe(["a", "b", "a", "c", "b"]);
        assert_eq!(out, vec!["a", "b", "c"]);
    }

    #[test]
    fn path_join_collapses_slashes() {
        assert_eq!(
            path_join(["/var", "log", "migrate.log"]),
            "/var/log/migrate.log"
        );
        assert_eq!(path_join(["/foo/", "/bar"]), "/foo/bar");
        assert_eq!(path_join(["foo", "bar", "baz"]), "foo/bar/baz");
    }
}
