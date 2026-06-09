// tui/render.rs — top-level App renderer.
//
// Reads &App, calls panel-specific widget functions. Pure: no side
// effects, no async, no I/O.
//
// Layout:
//   ┌────────────────────────────────────────────────────────────────┐
//   │ error banner (only if rt.error_banner is Some)                 │  3 rows
//   ├────────────────────────────────────────────────────────────────┤
//   │                                                                │
//   │ active panel (workflow/config/approvals/status/log)            │
//   │                                                                │
//   ├────────────────────────────────────────────────────────────────┤
//   │ status bar                                                     │  1 row
//   └────────────────────────────────────────────────────────────────┘
//
// The confirmation modal renders ON TOP of everything as a centered
// overlay when `rt.confirm_active()`.

use ratatui::{
    Frame,
    layout::{Constraint, Direction, Layout, Rect},
};

use crate::domain::app::App;
use crate::domain::runtime::RuntimePanel;
use crate::tui::widgets;

pub fn render(f: &mut Frame, app: &App) {
    let area = f.area();
    let has_banner = app.runtime.error_banner.is_some();

    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints({
            let mut cs = vec![];
            if has_banner { cs.push(Constraint::Length(3)); }
            cs.push(Constraint::Min(0));
            cs.push(Constraint::Length(1));
            cs
        })
        .split(area);

    let mut idx = 0;
    if has_banner {
        widgets::render_error_banner(f, chunks[idx], &app.runtime);
        idx += 1;
    }
    let main = chunks[idx];
    let bar  = chunks[idx + 1];

    match &app.runtime.panel {
        RuntimePanel::WorkflowView => widgets::render_workflow(f, main, &app.runtime),
        RuntimePanel::ConfigEdit   => widgets::render_config(f, main, &app.runtime),
        RuntimePanel::Approvals    => widgets::render_approvals(f, main, &app.runtime),
        RuntimePanel::Status       => widgets::render_status(f, main, &app.runtime),
        RuntimePanel::LogView { op_id } => widgets::render_log(f, main, &app.runtime, *op_id),
        RuntimePanel::Confirm { behind, .. } => {
            // Render whatever's behind the modal into the main area first,
            // then overlay the modal in a centered sub-rect.
            match behind.as_ref() {
                RuntimePanel::WorkflowView => widgets::render_workflow(f, main, &app.runtime),
                RuntimePanel::ConfigEdit   => widgets::render_config(f, main, &app.runtime),
                RuntimePanel::Approvals    => widgets::render_approvals(f, main, &app.runtime),
                RuntimePanel::Status       => widgets::render_status(f, main, &app.runtime),
                RuntimePanel::LogView { op_id } => widgets::render_log(f, main, &app.runtime, *op_id),
                // No nested Confirm by construction; default to status.
                RuntimePanel::Confirm { .. } => widgets::render_status(f, main, &app.runtime),
            }
            widgets::render_confirm(f, centered(main, 60, 14), &app.runtime);
        }
    }

    widgets::render_status_bar(f, bar, app);
}

/// Centered sub-rect with the given width × height, clamped to `parent`.
fn centered(parent: Rect, w: u16, h: u16) -> Rect {
    let w = w.min(parent.width);
    let h = h.min(parent.height);
    Rect {
        x: parent.x + (parent.width - w) / 2,
        y: parent.y + (parent.height - h) / 2,
        width: w,
        height: h,
    }
}

// ──────────────────────────────────────────────────────────────────────────
#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::confirmation::Confirmation;
    use crate::domain::runtime::RuntimeEvent;
    use ratatui::{Terminal, backend::TestBackend, buffer::Buffer};

    fn render_app(w: u16, h: u16, app: &App) -> Buffer {
        let backend = TestBackend::new(w, h);
        let mut term = Terminal::new(backend).unwrap();
        term.draw(|f| render(f, app)).unwrap();
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

    #[test]
    fn default_renders_workflow_panel() {
        let app = App::default();
        let buf = render_app(80, 12, &app);
        assert!(buf_contains(&buf, "workflow"));
    }

    #[test]
    fn switching_panel_changes_render() {
        let app = App::default()
            .dispatch(RuntimeEvent::SwitchPanel(RuntimePanel::ConfigEdit));
        let buf = render_app(80, 12, &app);
        assert!(buf_contains(&buf, "config"));
    }

    #[test]
    fn confirm_modal_overlays_workflow() {
        let app = App::default()
            .dispatch(RuntimeEvent::OpenConfirm(
                Confirmation::new("delete", "removes state", "DELETE")));
        let buf = render_app(80, 16, &app);
        // Both the underlying panel name AND the modal text appear.
        assert!(buf_contains(&buf, "workflow"));
        assert!(buf_contains(&buf, "delete"));
        assert!(buf_contains(&buf, "DELETE"));
    }

    #[test]
    fn error_banner_steals_three_rows() {
        let app = App::default()
            .dispatch(RuntimeEvent::SetError("kube down".into()));
        let buf = render_app(80, 12, &app);
        assert!(buf_contains(&buf, "kube down"));
    }

    #[test]
    fn render_does_not_panic_on_tiny_terminal() {
        let app = App::default()
            .dispatch(RuntimeEvent::OpenConfirm(
                Confirmation::new("act", "x", "OK")));
        // 20x4: smaller than the modal — must not panic.
        let _ = render_app(20, 4, &app);
    }
}
