// tui/widgets.rs — pure ratatui widget builders for runtime panels.
//
// Each panel renders into a Block with a title; the App-level renderer
// chooses which one to draw. All widgets are PURE — they read borrowed
// state and produce ratatui primitives. No mutation, no I/O.
//
// Tests render each widget into a TestBackend buffer and snapshot the
// exact text output. That's how ratatui itself tests its widgets.

use ratatui::{
    Frame,
    layout::Rect,
    style::{Color, Modifier, Style},
    text::{Line, Span},
    widgets::{Block, Borders, List, ListItem, Paragraph, Wrap},
};

use crate::domain::app::App;
use crate::domain::runtime::{Runtime, RuntimePanel};

// ── small helpers ─────────────────────────────────────────────────────

fn block(title: impl Into<String>) -> Block<'static> {
    Block::default().borders(Borders::ALL).title(title.into())
}

fn dim(s: impl Into<String>) -> Span<'static> {
    Span::styled(s.into(), Style::default().fg(Color::DarkGray))
}

fn err(s: impl Into<String>) -> Span<'static> {
    Span::styled(s.into(), Style::default().fg(Color::Red).add_modifier(Modifier::BOLD))
}

// ── workflow panel ────────────────────────────────────────────────────

pub fn render_workflow(f: &mut Frame, area: Rect, rt: &Runtime) {
    let lines: Vec<Line> = match &rt.workflow {
        None => vec![Line::from(dim("(no workflow status received yet)"))],
        Some(ws) => ws.walk().into_iter().map(|(node, _path)| {
            let indent = "  ".repeat(node.depth as usize);
            Line::from(vec![
                Span::raw(indent),
                Span::raw(format!("• {} ", node.display_name)),
                dim(format!("[{:?}]", node.phase)),
            ])
        }).collect(),
    };
    let title = match &rt.workflow {
        Some(ws) => format!("workflow: {} ({:?})", ws.workflow_name, ws.phase),
        None => "workflow".to_string(),
    };
    let p = Paragraph::new(lines).block(block(title)).wrap(Wrap { trim: false });
    f.render_widget(p, area);
}

// ── config-edit panel ─────────────────────────────────────────────────

pub fn render_config(f: &mut Frame, area: Rect, rt: &Runtime) {
    let mut lines: Vec<Line> = vec![];

    match &rt.draft_config {
        None => lines.push(Line::from(dim("(no config loaded)"))),
        Some(doc) => {
            for (i, l) in doc.yaml.lines().enumerate() {
                lines.push(Line::from(vec![
                    dim(format!("{:>3} ", i + 1)),
                    Span::raw(l.to_string()),
                ]));
            }
        }
    }

    if let Some(rep) = &rt.draft_validation {
        lines.push(Line::from(""));
        if rep.ok {
            lines.push(Line::from(Span::styled(
                "✓ valid",
                Style::default().fg(Color::Green).add_modifier(Modifier::BOLD),
            )));
        } else {
            lines.push(Line::from(err(format!("✗ {} validation error(s):", rep.errors.len()))));
            for e in &rep.errors {
                lines.push(Line::from(vec![
                    Span::raw("  "),
                    err("•"),
                    Span::raw(format!(" {e}")),
                ]));
            }
        }
    }

    let dirty = match (&rt.committed_config, &rt.draft_config) {
        (Some(c), Some(d)) => c != d,
        _ => false,
    };
    let title = if dirty { "config (modified)" } else { "config" };
    let p = Paragraph::new(lines).block(block(title)).wrap(Wrap { trim: false });
    f.render_widget(p, area);
}

// ── approvals panel ───────────────────────────────────────────────────

pub fn render_approvals(f: &mut Frame, area: Rect, rt: &Runtime) {
    let items: Vec<ListItem> = if rt.gates.is_empty() {
        vec![ListItem::new(Line::from(dim("(no pending approval gates)")))]
    } else {
        rt.gates.iter().map(|g| {
            let blockers = if g.blockers.is_empty() {
                String::new()
            } else {
                format!("  ({} blocker(s))", g.blockers.len())
            };
            ListItem::new(Line::from(vec![
                Span::raw(format!("[{:?}] ", g.kind)),
                Span::raw(g.name.clone()),
                dim(blockers),
            ]))
        }).collect()
    };
    let list = List::new(items).block(block("approvals"));
    f.render_widget(list, area);
}

// ── status panel (ops + backfill snapshot) ────────────────────────────

pub fn render_status(f: &mut Frame, area: Rect, rt: &Runtime) {
    let mut lines: Vec<Line> = vec![];

    lines.push(Line::from(Span::styled(
        "ops:".to_string(),
        Style::default().add_modifier(Modifier::BOLD),
    )));
    if rt.ops.is_empty() {
        lines.push(Line::from(dim("  (none)")));
    } else {
        for op in &rt.ops {
            let prog = op.progress
                .map(|p| format!(" [{}/{}, {:.0}%]", p.complete, p.total, p.pct()))
                .unwrap_or_default();
            let mile = op.milestone.as_deref()
                .map(|m| format!(" — {m}")).unwrap_or_default();
            lines.push(Line::from(vec![
                Span::raw(format!("  #{} {} ", op.id.0, op.label)),
                dim(format!("{:?}", op.status)),
                Span::raw(prog),
                Span::raw(mile),
            ]));
            if let Some(e) = &op.error {
                lines.push(Line::from(vec![Span::raw("    "), err(e)]));
            }
        }
    }

    if let Some(bf) = &rt.backfill {
        lines.push(Line::from(""));
        lines.push(Line::from(Span::styled(
            "backfill:".to_string(),
            Style::default().add_modifier(Modifier::BOLD),
        )));
        lines.push(Line::from(format!("  status: {:?}", bf.status)));
        lines.push(Line::from(format!(
            "  shards: {} of {}", bf.shard_complete, bf.shard_total,
        )));
        lines.push(Line::from(format!("  {:.1}% complete", bf.percentage_completed)));
    }

    let p = Paragraph::new(lines).block(block("status")).wrap(Wrap { trim: false });
    f.render_widget(p, area);
}

// ── log view ──────────────────────────────────────────────────────────

pub fn render_log(f: &mut Frame, area: Rect, rt: &Runtime, op_id: crate::extension::op::OpId) {
    let title = match rt.op(op_id) {
        Some(rec) => format!("log: {} (#{})", rec.label, op_id.0),
        None => format!("log: #{}", op_id.0),
    };
    let body = match rt.op(op_id) {
        None => vec![Line::from(dim("(no such op)"))],
        Some(_) => vec![Line::from(dim("(log streaming via LogStreamer; not buffered in domain)"))],
    };
    let p = Paragraph::new(body).block(block(title));
    f.render_widget(p, area);
}

// ── confirmation modal overlay ────────────────────────────────────────

pub fn render_confirm(f: &mut Frame, area: Rect, rt: &Runtime) {
    let Some(c) = rt.confirmation() else { return };

    let mut lines = vec![
        Line::from(Span::styled(
            format!("⚠ {}", c.action_label),
            Style::default().fg(Color::Yellow).add_modifier(Modifier::BOLD),
        )),
        Line::from(""),
        Line::from(c.impact_summary.clone()),
        Line::from(""),
        Line::from(vec![
            Span::raw("type "),
            Span::styled(
                c.typed_token.clone(),
                Style::default().fg(Color::Yellow).add_modifier(Modifier::BOLD),
            ),
            Span::raw(" to confirm:"),
        ]),
        Line::from(vec![
            Span::raw("> "),
            Span::styled(
                c.buffer.clone(),
                Style::default().fg(if c.satisfied { Color::Green } else { Color::White }),
            ),
        ]),
        Line::from(""),
    ];
    if c.satisfied {
        lines.push(Line::from(Span::styled(
            "✓ press Enter to proceed, Esc to cancel".to_string(),
            Style::default().fg(Color::Green),
        )));
    } else {
        lines.push(Line::from(dim("Esc to cancel")));
    }

    let p = Paragraph::new(lines).block(block("confirm")).wrap(Wrap { trim: true });
    f.render_widget(p, area);
}

// ── error banner (top of screen) ──────────────────────────────────────

pub fn render_error_banner(f: &mut Frame, area: Rect, rt: &Runtime) {
    if let Some(msg) = &rt.error_banner {
        let p = Paragraph::new(Line::from(vec![err("⚠ "), Span::raw(msg.clone())]))
            .block(Block::default().borders(Borders::ALL).title("error"));
        f.render_widget(p, area);
    }
}

// ── status bar (bottom) ───────────────────────────────────────────────

pub fn render_status_bar(f: &mut Frame, area: Rect, app: &App) {
    let panel_name = match app.runtime.panel {
        RuntimePanel::WorkflowView   => "workflow",
        RuntimePanel::ConfigEdit     => "config",
        RuntimePanel::Approvals      => "approvals",
        RuntimePanel::Status         => "status",
        RuntimePanel::LogView { .. } => "log",
        RuntimePanel::Confirm { .. } => "confirm",
    };
    let running = app.runtime.running_ops().count();
    let line = Line::from(vec![
        Span::styled(format!(" {} ", panel_name),
                     Style::default().add_modifier(Modifier::REVERSED)),
        Span::raw("  "),
        dim(format!("{running} op(s) running")),
        Span::raw("    "),
        dim("Tab: cycle panels  q/Esc: quit"),
    ]);
    f.render_widget(Paragraph::new(line), area);
}

// ──────────────────────────────────────────────────────────────────────────
#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::confirmation::Confirmation;
    use crate::domain::runtime::{Runtime, RuntimeEvent, RuntimePanel};
    use crate::extension::config::{ConfigDoc, ValidationReport};
    use crate::extension::cluster::{BackfillStatus, BackfillStatusKind};
    use crate::extension::op::{OpId, OpStatus, OpUpdate};
    use crate::extension::workflow::{NodeType, WorkflowNode, WorkflowPhase, WorkflowStatus};
    use ratatui::{Terminal, backend::TestBackend, buffer::Buffer};

    fn render_to<F: FnOnce(&mut Frame)>(w: u16, h: u16, draw: F) -> Buffer {
        let backend = TestBackend::new(w, h);
        let mut term = Terminal::new(backend).unwrap();
        term.draw(|f| draw(f)).unwrap();
        term.backend().buffer().clone()
    }

    fn buf_contains(buf: &Buffer, needle: &str) -> bool {
        let mut s = String::new();
        for y in 0..buf.area.height {
            for x in 0..buf.area.width {
                s.push_str(buf.cell((x, y)).map(|c| c.symbol()).unwrap_or(" "));
            }
            s.push('\n');
        }
        s.contains(needle)
    }

    fn ws() -> WorkflowStatus {
        WorkflowStatus {
            workflow_name: "demo-wf".into(),
            namespace: "ma".into(),
            phase: WorkflowPhase::Running,
            progress: Some("1/3".into()),
            started_at: None, finished_at: None,
            step_tree: vec![WorkflowNode {
                id: "snapshot".into(), name: "snapshot".into(), display_name: "snapshot".into(),
                phase: WorkflowPhase::Succeeded, node_type: NodeType::StepGroup,
                started_at: None, finished_at: None,
                depth: 0, parent: None, children: vec![],
            }],
            error: None,
        }
    }

    #[test]
    fn workflow_panel_renders_node_names() {
        let rt = Runtime::default().update(RuntimeEvent::WorkflowStatusReceived(ws()));
        let buf = render_to(80, 10, |f| {
            render_workflow(f, f.area(), &rt);
        });
        assert!(buf_contains(&buf, "demo-wf"));
        assert!(buf_contains(&buf, "snapshot"));
    }

    #[test]
    fn workflow_panel_empty_state() {
        let rt = Runtime::default();
        let buf = render_to(80, 5, |f| render_workflow(f, f.area(), &rt));
        assert!(buf_contains(&buf, "no workflow status"));
    }

    #[test]
    fn config_panel_renders_yaml_with_line_numbers() {
        let rt = Runtime::default().update(RuntimeEvent::ConfigLoaded(ConfigDoc {
            yaml: "source:\n  endpoint: x\n".into(),
        }));
        let buf = render_to(80, 8, |f| render_config(f, f.area(), &rt));
        assert!(buf_contains(&buf, "1 source:"));
        assert!(buf_contains(&buf, "endpoint: x"));
    }

    #[test]
    fn config_panel_dirty_marker_appears_on_uncommitted_edit() {
        let rt = Runtime::default()
            .update(RuntimeEvent::ConfigLoaded(ConfigDoc { yaml: "a".into() }))
            .update(RuntimeEvent::DraftSet(ConfigDoc { yaml: "b".into() }));
        let buf = render_to(80, 8, |f| render_config(f, f.area(), &rt));
        assert!(buf_contains(&buf, "modified"));
    }

    #[test]
    fn config_panel_renders_validation_errors() {
        let rt = Runtime::default()
            .update(RuntimeEvent::ConfigLoaded(ConfigDoc { yaml: "x".into() }))
            .update(RuntimeEvent::DraftValidated(ValidationReport {
                ok: false,
                errors: vec!["source.endpoint: missing".into()],
                referenced_secrets: vec![],
            }));
        let buf = render_to(80, 10, |f| render_config(f, f.area(), &rt));
        assert!(buf_contains(&buf, "validation error"));
        assert!(buf_contains(&buf, "source.endpoint"));
    }

    #[test]
    fn approvals_panel_empty() {
        let rt = Runtime::default();
        let buf = render_to(80, 5, |f| render_approvals(f, f.area(), &rt));
        assert!(buf_contains(&buf, "no pending"));
    }

    #[test]
    fn status_panel_shows_op_progress() {
        let rt = Runtime::default()
            .update(RuntimeEvent::OpStarted { id: OpId(1), label: "snapshot create".into() })
            .update(RuntimeEvent::OpUpdated {
                id: OpId(1),
                update: OpUpdate::Progress { complete: 25, total: 100, eta_ms: Some(60_000) },
            })
            .update(RuntimeEvent::OpUpdated {
                id: OpId(1),
                update: OpUpdate::StatusChanged(OpStatus::Running),
            });
        let buf = render_to(80, 8, |f| render_status(f, f.area(), &rt));
        assert!(buf_contains(&buf, "snapshot create"));
        assert!(buf_contains(&buf, "25/100"));
        assert!(buf_contains(&buf, "Running"));
    }

    #[test]
    fn status_panel_shows_op_error() {
        let rt = Runtime::default()
            .update(RuntimeEvent::OpStarted { id: OpId(2), label: "backfill stop".into() })
            .update(RuntimeEvent::OpUpdated {
                id: OpId(2),
                update: OpUpdate::Error("workers in progress".into()),
            });
        let buf = render_to(80, 8, |f| render_status(f, f.area(), &rt));
        assert!(buf_contains(&buf, "Failed"));
        assert!(buf_contains(&buf, "workers in progress"));
    }

    #[test]
    fn status_panel_shows_backfill_snapshot() {
        let rt = Runtime::default()
            .update(RuntimeEvent::BackfillStatusReceived(BackfillStatus {
                status: BackfillStatusKind::Running,
                percentage_completed: 30.0,
                eta_ms: None,
                started: true,
                finished: false,
                shard_total: 10,
                shard_complete: 3,
                shard_in_progress: 4,
                shard_waiting: 3,
            }));
        let buf = render_to(80, 10, |f| render_status(f, f.area(), &rt));
        assert!(buf_contains(&buf, "backfill"));
        assert!(buf_contains(&buf, "3 of 10"));
    }

    #[test]
    fn confirm_modal_renders_typed_token_prompt() {
        let rt = Runtime::default().update(RuntimeEvent::OpenConfirm(
            Confirmation::new("delete workflow", "DESTROYS state", "DELETE")
        ));
        let buf = render_to(60, 12, |f| render_confirm(f, f.area(), &rt));
        assert!(buf_contains(&buf, "delete workflow"));
        assert!(buf_contains(&buf, "DESTROYS state"));
        assert!(buf_contains(&buf, "DELETE"));
    }

    #[test]
    fn confirm_modal_satisfied_shows_proceed_hint() {
        let mut rt = Runtime::default().update(RuntimeEvent::OpenConfirm(
            Confirmation::new("act", "x", "OK")
        ));
        for ch in ['O', 'K'] { rt = rt.update(RuntimeEvent::ConfirmChar(ch)); }
        let buf = render_to(60, 12, |f| render_confirm(f, f.area(), &rt));
        assert!(buf_contains(&buf, "press Enter"));
    }

    #[test]
    fn error_banner_renders_when_set() {
        let rt = Runtime::default().update(RuntimeEvent::SetError("kube apiserver 503".into()));
        let buf = render_to(80, 3, |f| render_error_banner(f, f.area(), &rt));
        assert!(buf_contains(&buf, "kube apiserver 503"));
    }

    #[test]
    fn error_banner_invisible_when_clear() {
        let rt = Runtime::default();
        let buf = render_to(80, 3, |f| render_error_banner(f, f.area(), &rt));
        assert!(!buf_contains(&buf, "⚠"));
    }

    #[test]
    fn status_bar_shows_panel_name() {
        let app = App::default()
            .dispatch(RuntimeEvent::SwitchPanel(RuntimePanel::ConfigEdit));
        let buf = render_to(80, 1, |f| render_status_bar(f, f.area(), &app));
        assert!(buf_contains(&buf, "config"));
    }

    #[test]
    fn status_bar_shows_running_op_count() {
        let app = App::default()
            .dispatch(RuntimeEvent::OpStarted { id: OpId(1), label: "x".into() })
            .dispatch(RuntimeEvent::OpStarted { id: OpId(2), label: "y".into() });
        let buf = render_to(80, 1, |f| render_status_bar(f, f.area(), &app));
        assert!(buf_contains(&buf, "2 op"));
    }
}
