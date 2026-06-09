// domain/confirmation.rs — destructive-op confirmation modal.
//
// Mirrors the Python `--acknowledge-risk` pattern. Every destructive
// service call (clear-indices, snapshot delete, snapshot unregister-repo,
// kafka delete-topic) goes through a Confirmation gate before the TUI
// dispatches to the service. The gate carries:
//   - what's about to happen (action_label)
//   - why it's destructive (impact_summary)
//   - the typed-confirmation token the user must echo back (e.g. "DELETE")
//
// Pure-data: no I/O. Tested with unit tests + fed into App::update via
// Message::ConfirmationResolved(yes/no).

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Confirmation {
    /// Short label for the action (button text), e.g. "Clear target indices".
    pub action_label: String,
    /// Multi-line description of what will be destroyed and why it matters.
    pub impact_summary: String,
    /// User must type this token verbatim to confirm. Conventionally upper-case.
    pub typed_token: String,
    /// True if `typed_token` matches the buffered input. Updated by App::update.
    pub satisfied: bool,
    /// Free-text input buffer the user is typing into.
    pub buffer: String,
}

impl Confirmation {
    pub fn new(action_label: impl Into<String>, impact_summary: impl Into<String>,
               typed_token: impl Into<String>) -> Self {
        Self {
            action_label: action_label.into(),
            impact_summary: impact_summary.into(),
            typed_token: typed_token.into(),
            satisfied: false,
            buffer: String::new(),
        }
    }

    /// Append a character to the buffer and recompute satisfied.
    pub fn push_char(mut self, c: char) -> Self {
        self.buffer.push(c);
        self.satisfied = self.buffer == self.typed_token;
        self
    }

    /// Remove the last character.
    pub fn backspace(mut self) -> Self {
        self.buffer.pop();
        self.satisfied = self.buffer == self.typed_token;
        self
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fresh_confirmation_not_satisfied() {
        let c = Confirmation::new("delete", "irreversible", "DELETE");
        assert!(!c.satisfied);
        assert!(c.buffer.is_empty());
    }

    #[test]
    fn typing_token_satisfies() {
        let mut c = Confirmation::new("delete", "irreversible", "DELETE");
        for ch in "DELETE".chars() {
            c = c.push_char(ch);
        }
        assert!(c.satisfied);
    }

    #[test]
    fn partial_match_not_satisfied() {
        let mut c = Confirmation::new("delete", "irreversible", "DELETE");
        for ch in "DELET".chars() {
            c = c.push_char(ch);
        }
        assert!(!c.satisfied);
    }

    #[test]
    fn extra_chars_unsatisfy() {
        let mut c = Confirmation::new("delete", "irreversible", "DELETE");
        for ch in "DELETEX".chars() {
            c = c.push_char(ch);
        }
        assert!(!c.satisfied);
    }

    #[test]
    fn case_sensitive_match() {
        let mut c = Confirmation::new("delete", "irreversible", "DELETE");
        for ch in "delete".chars() {
            c = c.push_char(ch);
        }
        assert!(!c.satisfied);
    }

    #[test]
    fn backspace_unsatisfies() {
        let mut c = Confirmation::new("delete", "irreversible", "DELETE");
        for ch in "DELETE".chars() {
            c = c.push_char(ch);
        }
        assert!(c.satisfied);
        c = c.backspace();
        assert!(!c.satisfied);
        assert_eq!(c.buffer, "DELET");
    }

    #[test]
    fn backspace_on_empty_is_noop() {
        let c = Confirmation::new("delete", "irreversible", "DELETE");
        let c2 = c.clone().backspace();
        assert_eq!(c, c2);
    }

    #[test]
    fn confirmation_serde_roundtrip() {
        let c = Confirmation::new("Clear target indices",
            "All user indices on target will be permanently removed.",
            "CLEAR-INDICES");
        let s = serde_json::to_string(&c).unwrap();
        let d: Confirmation = serde_json::from_str(&s).unwrap();
        assert_eq!(c, d);
    }
}
