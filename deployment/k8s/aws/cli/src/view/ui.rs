//! Terminal UI primitives + the output-discipline rule.
//!
//! Port of `lib/ui.sh` + `lib/term.sh`. The cardinal rule is reproduced: **all
//! UI text goes to stderr; stdout is reserved for return values** (the chosen
//! mode, the resolved version). The bug-prone parts — the `ui_confirm` truth
//! table and the mode-picker selection matching — are factored into pure
//! functions ([`confirm_decision`], [`resolve_pick`]) so the exact contracts in
//! the prompt/confirm truth table and the mode-pick matching are pure and
//! unit-tested, and the interactive prompting is a thin shell on top.

use std::io::{IsTerminal, Write};

/// Whether stderr is an interactive terminal — `is_interactive` / `-t 2`.
pub fn is_interactive() -> bool {
    std::io::stderr().is_terminal()
}

/// ANSI SGR helpers, emitted only when stderr is a TTY (CI-log safe), matching
/// term.sh's detection. Kept minimal — the eight colors term.sh used.
fn color(code: &str, s: &str) -> String {
    if is_interactive() {
        format!("\x1b[{code}m{s}\x1b[0m")
    } else {
        s.to_string()
    }
}

macro_rules! emit {
    ($($arg:tt)*) => {{
        let _ = writeln!(std::io::stderr(), $($arg)*);
    }};
}

/// `ℹ <msg>` — informational. Stderr.
pub fn info(msg: &str) {
    emit!("{} {}", color("36", "ℹ"), msg);
}
/// `✓ <msg>` — success. Stderr.
pub fn ok(msg: &str) {
    emit!("{} {}", color("32", "✓"), msg);
}
/// `! <msg>` — warning. Stderr.
pub fn warn(msg: &str) {
    emit!("{} {}", color("33", "!"), msg);
}
/// `✗ <msg>` — error. Stderr.
pub fn err(msg: &str) {
    emit!("{} {}", color("31", "✗"), msg);
}
/// A dim line. Stderr.
pub fn dim(msg: &str) {
    emit!("{}", color("2", msg));
}
/// A bold `▶ <msg>` step header, with a leading blank line. Stderr.
pub fn step(msg: &str) {
    emit!("");
    emit!("{}", color("1", &format!("▶ {msg}")));
}

/// A bold boxed banner — `ui_banner`. Stderr.
pub fn banner(title: &str) {
    let bar = "━".repeat(title.chars().count() + 4);
    emit!("");
    emit!("{}", color("1", &bar));
    emit!("{}", color("1", &format!("  {title}  ")));
    emit!("{}", color("1", &bar));
    emit!("");
}

/// The yes/no decision of `ui_confirm`, as a pure function of the raw answer
/// and the default. The truth table:
///
///   * empty input, or the hint string itself (`Y/n` / `y/N`) → use the default
///   * leading `y`/`Y` → yes; leading `n`/`N` → no
///   * anything else → no (conservative)
pub fn confirm_decision(answer: &str, default_yes: bool) -> bool {
    let hint = if default_yes { "Y/n" } else { "y/N" };
    let effective = if answer.is_empty() || answer == hint {
        if default_yes {
            "y"
        } else {
            "n"
        }
    } else {
        answer
    };
    match effective.chars().next() {
        Some('y') | Some('Y') => true,
        Some('n') | Some('N') => false,
        _ => false,
    }
}

/// Resolve a mode-picker selection against the visible mode ids/labels —
/// the matching logic of `_select_mode`. Accepts:
///   * a 1-based numeric index;
///   * an exact id or label match;
///   * a case-insensitive first-letter match (legacy `m`/`a` shortcuts).
///
/// Returns the matched id, or `None` for an invalid pick.
pub fn resolve_pick<'a>(pick: &str, ids: &[&'a str], labels: &[&str]) -> Option<&'a str> {
    let pick = pick.trim();
    // Numeric index.
    if let Ok(n) = pick.parse::<usize>() {
        if n >= 1 && n <= ids.len() {
            return Some(ids[n - 1]);
        }
        return None;
    }
    // Exact id or label.
    for (i, id) in ids.iter().enumerate() {
        if pick == *id || labels.get(i).map(|l| pick == *l).unwrap_or(false) {
            return Some(id);
        }
    }
    // First-letter (case-insensitive).
    let pf = pick.chars().next().map(|c| c.to_ascii_lowercase());
    for id in ids {
        let idf = id.chars().next().map(|c| c.to_ascii_lowercase());
        if pf.is_some() && pf == idf {
            return Some(id);
        }
    }
    None
}

/// Prompt for a free-text value, returning the answer or the default.
///
/// Honors non-interactive mode (return the default without reading). UI text
/// goes to stderr; the answer is the return value, never printed to stdout —
/// matching `ui_prompt`'s discipline.
pub fn prompt(prompt_text: &str, default: &str, non_interactive: bool) -> String {
    if non_interactive {
        return default.to_string();
    }
    let label = if default.is_empty() {
        format!("{prompt_text}: ")
    } else {
        format!("{prompt_text} [{default}]: ")
    };
    let _ = write!(std::io::stderr(), "{}", color("1", &label));
    let _ = std::io::stderr().flush();
    let mut line = String::new();
    if std::io::stdin().read_line(&mut line).is_err() {
        return default.to_string();
    }
    let answer = line.trim_end_matches(['\n', '\r']);
    if answer.is_empty() {
        default.to_string()
    } else {
        answer.to_string()
    }
}

/// Prompt for yes/no — `ui_confirm`. `default_yes` is the default when the
/// operator just hits Enter. Non-interactive runs take the default.
pub fn confirm(prompt_text: &str, default_yes: bool, non_interactive: bool) -> bool {
    let hint = if default_yes { "Y/n" } else { "y/N" };
    let answer = prompt(prompt_text, hint, non_interactive);
    confirm_decision(&answer, default_yes)
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---- confirm truth table ----

    #[test]
    fn empty_input_uses_default() {
        assert!(confirm_decision("", true)); // default Y
        assert!(!confirm_decision("", false)); // default N
    }

    #[test]
    fn hint_string_collapses_to_default() {
        assert!(confirm_decision("Y/n", true));
        assert!(!confirm_decision("y/N", false));
    }

    #[test]
    fn explicit_yes_no() {
        assert!(confirm_decision("y", true));
        assert!(confirm_decision("y", false)); // default N, but explicit y
        assert!(!confirm_decision("n", true)); // default Y, but explicit n
        assert!(!confirm_decision("n", false));
    }

    #[test]
    fn case_and_word_forms() {
        assert!(confirm_decision("Y", false));
        assert!(confirm_decision("YES", false));
        assert!(confirm_decision("Yes", false));
        assert!(!confirm_decision("NO", true));
    }

    #[test]
    fn garbage_is_conservative_no() {
        assert!(!confirm_decision("maybe", true));
        assert!(!confirm_decision("42", true));
    }

    // ---- mode-pick resolution ----

    #[test]
    fn pick_by_number() {
        let ids = ["Manual", "Agent"];
        let labels = ["Manual", "AI"];
        assert_eq!(resolve_pick("1", &ids, &labels), Some("Manual"));
        assert_eq!(resolve_pick("2", &ids, &labels), Some("Agent"));
        assert_eq!(resolve_pick("3", &ids, &labels), None);
        assert_eq!(resolve_pick("0", &ids, &labels), None);
    }

    #[test]
    fn pick_by_id_or_label() {
        let ids = ["Manual", "Agent"];
        let labels = ["Manual", "AI"];
        assert_eq!(resolve_pick("Manual", &ids, &labels), Some("Manual"));
        assert_eq!(resolve_pick("Agent", &ids, &labels), Some("Agent"));
        assert_eq!(resolve_pick("AI", &ids, &labels), Some("Agent"), "by label");
    }

    #[test]
    fn pick_by_first_letter() {
        let ids = ["Manual", "Agent"];
        let labels = ["Manual", "AI"];
        assert_eq!(resolve_pick("m", &ids, &labels), Some("Manual"));
        assert_eq!(resolve_pick("a", &ids, &labels), Some("Agent"));
        assert_eq!(resolve_pick("M", &ids, &labels), Some("Manual"));
    }

    #[test]
    fn pick_invalid_returns_none() {
        let ids = ["Manual"];
        let labels = ["Manual"];
        assert_eq!(resolve_pick("zzz", &ids, &labels), None);
    }
}
