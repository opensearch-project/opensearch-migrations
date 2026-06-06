//! The resume phase checklist.
//!
//! Port of `lib/timeline.sh`. The bash version printed ANSI directly; here we
//! produce a structured `Vec<PhaseRow>` (pure data) that the TUI or a plain
//! renderer can draw. The phase list and key→label mapping are reproduced
//! exactly so `timeline_phase_label` and the marker logic match the contracts
//! by the unit tests below.

/// The canonical deploy phases: `(last_step key, operator-visible label)`, in
/// deploy order. Matches `__TIMELINE_PHASES`.
pub const PHASES: &[(&str, &str)] = &[
    ("discover", "Discover environment"),
    ("wizard_done", "Configure deploy"),
    ("cfn_done", "Deploy CloudFormation"),
    ("kubeconfig", "Set kubeconfig"),
    ("build_done", "Build images (optional)"),
    ("crane_done", "Mirror images (optional)"),
    ("helm_done", "Install helm chart"),
    ("ready", "Ready"),
    ("console_handoff", "Console (Manual mode)"),
    ("agent_handoff", "Agent handoff (AI mode)"),
];

/// The status of one phase relative to where the previous run stopped.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PhaseStatus {
    /// Completed — the previous run advanced past this phase. Marker `●`.
    Done,
    /// The phase the previous run was inside when it stopped. Marker `◐`.
    Last,
    /// Not yet reached. Marker `○`.
    Pending,
}

impl PhaseStatus {
    /// The glyph the bash timeline used for this status.
    pub fn marker(self) -> char {
        match self {
            PhaseStatus::Done => '●',
            PhaseStatus::Last => '◐',
            PhaseStatus::Pending => '○',
        }
    }
}

/// One rendered checklist row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PhaseRow {
    pub key: &'static str,
    pub label: &'static str,
    pub status: PhaseStatus,
}

/// The operator-visible label for a `last_step` key, or the key itself if
/// unknown — `timeline_phase_label`.
pub fn phase_label(key: &str) -> &str {
    PHASES
        .iter()
        .find(|(k, _)| *k == key)
        .map(|(_, l)| *l)
        .unwrap_or(key)
}

/// Build the checklist for a given `last_step`. Phases before it are `Done`,
/// the matching phase is `Last`, later phases are `Pending`. An unknown or
/// empty `last_step` (index not found) leaves everything `Pending`. Mirrors
/// `timeline_render`'s marker logic.
pub fn rows(last_step: &str) -> Vec<PhaseRow> {
    let last_idx = PHASES.iter().position(|(k, _)| *k == last_step);
    PHASES
        .iter()
        .enumerate()
        .map(|(i, (key, label))| {
            let status = match last_idx {
                Some(li) if i < li => PhaseStatus::Done,
                Some(li) if i == li => PhaseStatus::Last,
                _ => PhaseStatus::Pending,
            };
            PhaseRow { key, label, status }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn label_maps_known_keys() {
        assert_eq!(phase_label("discover"), "Discover environment");
        assert_eq!(phase_label("wizard_done"), "Configure deploy");
        assert_eq!(phase_label("cfn_done"), "Deploy CloudFormation");
        assert_eq!(phase_label("helm_done"), "Install helm chart");
        assert_eq!(phase_label("agent_handoff"), "Agent handoff (AI mode)");
    }

    #[test]
    fn label_echoes_unknown_key() {
        assert_eq!(
            phase_label("something_we_havent_added"),
            "something_we_havent_added"
        );
    }

    #[test]
    fn rows_mark_each_phase_correctly() {
        let rows = rows("wizard_done");
        let by_label = |l: &str| rows.iter().find(|r| r.label == l).unwrap().status;
        assert_eq!(by_label("Discover environment"), PhaseStatus::Done);
        assert_eq!(by_label("Configure deploy"), PhaseStatus::Last);
        assert_eq!(by_label("Deploy CloudFormation"), PhaseStatus::Pending);
        assert_eq!(by_label("Install helm chart"), PhaseStatus::Pending);
    }

    #[test]
    fn empty_last_step_mostly_pending() {
        let rows = rows("");
        let pending = rows
            .iter()
            .filter(|r| r.status == PhaseStatus::Pending)
            .count();
        let done = rows
            .iter()
            .filter(|r| r.status == PhaseStatus::Done)
            .count();
        assert!(pending > done);
        assert_eq!(done, 0, "nothing done when last_step is empty");
    }

    #[test]
    fn markers_match_bash_glyphs() {
        assert_eq!(PhaseStatus::Done.marker(), '●');
        assert_eq!(PhaseStatus::Last.marker(), '◐');
        assert_eq!(PhaseStatus::Pending.marker(), '○');
    }
}
