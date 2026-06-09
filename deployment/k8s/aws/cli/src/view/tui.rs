//! Ratatui front-end — the interactive surfaces, built The-Elm-Architecture
//! style (Model → Message → update → view).
//!
//! The whole deploy form is one immediate-mode [`Wizard`] model: every frame
//! the form is rebuilt from the model, key events become [`Msg`]s, and
//! [`Wizard::update`] is the only place the model mutates. That keeps the form
//! logic a pure state machine (unit-tested directly) and the rendering a pure
//! projection of the model (asserted with `ratatui::backend::TestBackend`).
//!
//! On a non-interactive run (CI / `-y`) the TUI is never entered — the
//! dispatcher fills the wizard from state/defaults directly. The TUI is for the
//! human at a terminal.

use crate::timeline::{self, PhaseStatus};
use ratatui::{
    layout::{Constraint, Layout, Rect},
    style::Stylize,
    text::{Line, Span},
    widgets::{Block, Paragraph, Widget},
    Frame,
};

/// The deploy config the wizard collects — region, stage name, image-mirror
/// choice, and MA version, plus the chosen driver mode.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeployConfig {
    pub region: String,
    pub stage_name: String,
    pub mirror_images: bool,
    pub ma_version: String,
    pub mode: String,
}

/// Which field the wizard cursor is on.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Field {
    Mode,
    Region,
    StageName,
    MirrorImages,
    MaVersion,
}

impl Field {
    /// Fields in tab order. `Mode` is hidden (single-option) when the Agent
    /// gate is off, but the field list is fixed; the picker just shows one
    /// option then.
    pub const ORDER: [Field; 5] = [
        Field::Mode,
        Field::Region,
        Field::StageName,
        Field::MirrorImages,
        Field::MaVersion,
    ];

    fn label(self) -> &'static str {
        match self {
            Field::Mode => "Mode",
            Field::Region => "AWS region",
            Field::StageName => "Stage name",
            Field::MirrorImages => "Mirror images to private ECR",
            Field::MaVersion => "Migration Assistant version",
        }
    }
}

/// A message the wizard reacts to — the only way the model changes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Msg {
    /// Move to the next field (Tab / Down).
    Next,
    /// Move to the previous field (Shift-Tab / Up).
    Prev,
    /// Append a character to the focused text field.
    Char(char),
    /// Backspace in the focused text field.
    Backspace,
    /// Toggle a boolean field, or cycle the mode options.
    Toggle,
    /// Submit the form (Enter on the last field / Ctrl-S).
    Submit,
    /// Cancel the wizard (Esc).
    Cancel,
}

/// The wizard's running state.
#[derive(Debug, Clone)]
pub struct Wizard {
    pub config: DeployConfig,
    pub focus: usize,
    /// Visible mode ids (gate-filtered by the caller).
    pub mode_options: Vec<String>,
    pub mode_index: usize,
    /// Set when the form is submitted/cancelled — stops the event loop.
    pub done: Option<bool>,
}

impl Wizard {
    /// Build a wizard pre-filled from existing values (resume-friendly), with
    /// the gate-filtered list of selectable modes.
    pub fn new(config: DeployConfig, mode_options: Vec<String>) -> Self {
        let mode_index = mode_options
            .iter()
            .position(|m| *m == config.mode)
            .unwrap_or(0);
        Self {
            config,
            focus: 0,
            mode_options,
            mode_index,
            done: None,
        }
    }

    /// The currently focused field.
    pub fn focused(&self) -> Field {
        Field::ORDER[self.focus.min(Field::ORDER.len() - 1)]
    }

    /// Apply a message — the sole mutation point (TEA `update`).
    pub fn update(&mut self, msg: Msg) {
        match msg {
            Msg::Next => self.focus = (self.focus + 1) % Field::ORDER.len(),
            Msg::Prev => self.focus = (self.focus + Field::ORDER.len() - 1) % Field::ORDER.len(),
            Msg::Char(c) => self.edit(|s| s.push(c)),
            Msg::Backspace => self.edit(|s| {
                s.pop();
            }),
            Msg::Toggle => self.toggle(),
            Msg::Submit => {
                self.sync_mode();
                self.done = Some(true);
            }
            Msg::Cancel => self.done = Some(false),
        }
    }

    fn edit(&mut self, f: impl FnOnce(&mut String)) {
        match self.focused() {
            Field::Region => f(&mut self.config.region),
            Field::StageName => f(&mut self.config.stage_name),
            Field::MaVersion => f(&mut self.config.ma_version),
            // Mode / MirrorImages aren't text fields — typing is a no-op.
            _ => {}
        }
    }

    fn toggle(&mut self) {
        match self.focused() {
            Field::MirrorImages => self.config.mirror_images = !self.config.mirror_images,
            Field::Mode if !self.mode_options.is_empty() => {
                self.mode_index = (self.mode_index + 1) % self.mode_options.len();
                self.sync_mode();
            }
            _ => {}
        }
    }

    fn sync_mode(&mut self) {
        if let Some(m) = self.mode_options.get(self.mode_index) {
            self.config.mode = m.clone();
        }
    }

    /// Map a key event to a [`Msg`], or `None` if the key is inert. Pure, so the
    /// key bindings are testable without a terminal.
    pub fn key_to_msg(&self, code: ratatui::crossterm::event::KeyCode) -> Option<Msg> {
        use ratatui::crossterm::event::KeyCode::*;
        match code {
            Tab | Down => Some(Msg::Next),
            BackTab | Up => Some(Msg::Prev),
            Backspace => Some(Msg::Backspace),
            Esc => Some(Msg::Cancel),
            Enter => Some(Msg::Submit),
            Char(' ') if matches!(self.focused(), Field::MirrorImages | Field::Mode) => {
                Some(Msg::Toggle)
            }
            Char(c) => Some(Msg::Char(c)),
            _ => None,
        }
    }

    /// Render the whole form (immediate mode — rebuilt every frame).
    pub fn view(&self, frame: &mut Frame) {
        frame.render_widget(self, frame.area());
    }
}

impl Widget for &Wizard {
    fn render(self, area: Rect, buf: &mut ratatui::buffer::Buffer) {
        // `bordered()` + a styled `Line` title is the 0.30 idiom (the old
        // `Block::title_style` / `block::Title` API was removed).
        let block = Block::bordered().title(" Configure deployment ".bold());
        let inner = block.inner(area);
        block.render(area, buf);

        // One row per field + a footer hint.
        let rows: Vec<Constraint> = Field::ORDER
            .iter()
            .map(|_| Constraint::Length(1))
            .chain([Constraint::Length(1), Constraint::Min(0)])
            .collect();
        // `.areas()` can't be used here (the count is runtime, +2 for footer),
        // so split into an Rc<[Rect]> and index.
        let chunks = Layout::vertical(rows).split(inner);

        for (i, field) in Field::ORDER.iter().enumerate() {
            let focused = i == self.focus;
            // Stylize sugar (`.bold()`, `.reversed()`) over the verbose
            // `Style::new().add_modifier(...)` form — see the styling guide.
            let label = Span::from(format!("{}{:<30}", focus_marker(focused), field.label()));
            let label = if focused { label.bold() } else { label };
            let mut line = Line::from(vec![label, Span::raw(self.field_display(*field))]);
            if focused {
                line = line.reversed();
            }
            Paragraph::new(line).render(chunks[i], buf);
        }

        let hint = "Tab/↑↓ move · type to edit · Space toggles · Enter submits · Esc cancels";
        Paragraph::new(hint.dim()).render(chunks[Field::ORDER.len()], buf);
    }
}

/// The focus cursor prefix for a form row.
fn focus_marker(focused: bool) -> &'static str {
    if focused {
        "▶ "
    } else {
        "  "
    }
}

impl Wizard {
    fn field_display(&self, field: Field) -> String {
        match field {
            Field::Mode => self
                .mode_options
                .get(self.mode_index)
                .cloned()
                .unwrap_or_else(|| "Manual".into()),
            Field::Region => self.config.region.clone(),
            Field::StageName => self.config.stage_name.clone(),
            Field::MirrorImages => if self.config.mirror_images {
                "Yes"
            } else {
                "No"
            }
            .into(),
            Field::MaVersion => self.config.ma_version.clone(),
        }
    }
}

/// Render the resume timeline into `frame` — the visual context for the resume
/// prompt (`timeline_render`). Stateless: a pure projection of `last_step`.
pub fn render_timeline(frame: &mut Frame, last_step: &str) {
    let lines: Vec<Line> = timeline::rows(last_step)
        .iter()
        .map(|r| {
            let glyph = Span::from(format!("  {} ", r.status.marker()));
            let (glyph, suffix) = match r.status {
                PhaseStatus::Done => (glyph.green(), " done"),
                PhaseStatus::Last => (glyph.yellow(), " last"),
                PhaseStatus::Pending => (glyph.dim(), ""),
            };
            Line::from(vec![glyph, Span::raw(r.label), suffix.dim()])
        })
        .collect();
    let block = Block::bordered().title(" Previous run ");
    frame.render_widget(Paragraph::new(lines).block(block), frame.area());
}

#[cfg(test)]
mod tests {
    use super::*;
    use ratatui::backend::TestBackend;
    use ratatui::crossterm::event::KeyCode;
    use ratatui::Terminal;

    fn cfg() -> DeployConfig {
        DeployConfig {
            region: "us-east-1".into(),
            stage_name: "ma".into(),
            mirror_images: true,
            ma_version: "3.2.1".into(),
            mode: "Manual".into(),
        }
    }

    fn wizard() -> Wizard {
        Wizard::new(cfg(), vec!["Manual".into(), "Agent".into()])
    }

    // ---- update logic (pure state machine) ----

    #[test]
    fn next_prev_cycle_focus() {
        let mut w = wizard();
        assert_eq!(w.focused(), Field::Mode);
        w.update(Msg::Next);
        assert_eq!(w.focused(), Field::Region);
        w.update(Msg::Prev);
        assert_eq!(w.focused(), Field::Mode);
        w.update(Msg::Prev); // wraps to last
        assert_eq!(w.focused(), Field::MaVersion);
    }

    #[test]
    fn typing_edits_focused_text_field() {
        let mut w = wizard();
        w.update(Msg::Next); // Region
        w.update(Msg::Backspace);
        w.update(Msg::Char('2'));
        assert_eq!(w.config.region, "us-east-2");
    }

    #[test]
    fn typing_in_non_text_field_is_noop() {
        let mut w = wizard();
        // Focus = Mode (not a text field).
        w.update(Msg::Char('x'));
        assert_eq!(w.config.mode, "Manual");
    }

    #[test]
    fn toggle_flips_mirror_images() {
        let mut w = wizard();
        // Move to MirrorImages.
        while w.focused() != Field::MirrorImages {
            w.update(Msg::Next);
        }
        assert!(w.config.mirror_images);
        w.update(Msg::Toggle);
        assert!(!w.config.mirror_images);
    }

    #[test]
    fn toggle_cycles_mode_and_syncs_config() {
        let mut w = wizard();
        assert_eq!(w.focused(), Field::Mode);
        w.update(Msg::Toggle);
        assert_eq!(w.config.mode, "Agent");
        w.update(Msg::Toggle);
        assert_eq!(w.config.mode, "Manual");
    }

    #[test]
    fn submit_and_cancel_set_done() {
        let mut w = wizard();
        w.update(Msg::Submit);
        assert_eq!(w.done, Some(true));

        let mut w2 = wizard();
        w2.update(Msg::Cancel);
        assert_eq!(w2.done, Some(false));
    }

    #[test]
    fn key_bindings_map_to_messages() {
        let w = wizard();
        assert_eq!(w.key_to_msg(KeyCode::Tab), Some(Msg::Next));
        assert_eq!(w.key_to_msg(KeyCode::Up), Some(Msg::Prev));
        assert_eq!(w.key_to_msg(KeyCode::Esc), Some(Msg::Cancel));
        assert_eq!(w.key_to_msg(KeyCode::Enter), Some(Msg::Submit));
        // Space toggles on Mode (focused initially).
        assert_eq!(w.key_to_msg(KeyCode::Char(' ')), Some(Msg::Toggle));
        // A letter on Mode → Char (inert in update, but mapped).
        assert_eq!(w.key_to_msg(KeyCode::Char('z')), Some(Msg::Char('z')));
    }

    // ---- rendering ----
    //
    // These render into a `TestBackend` and assert the EXACT frame with
    // `assert_buffer_lines`. Pinning the whole frame (not a substring) catches
    // layout, spacing, border, alignment, and label regressions — the bugs a
    // `contains` check silently misses. Buffers are sized to fit the content
    // so the expected lines are explicit. (See RATATUI_DEEP_DIVE.md §9.)

    use ratatui::buffer::Buffer;
    use ratatui::layout::Rect;
    use ratatui::style::Style;

    /// Render `widget` into a `cols`×`rows` buffer and return it.
    fn render(widget: &Wizard, cols: u16, rows: u16) -> Buffer {
        let mut terminal = Terminal::new(TestBackend::new(cols, rows)).unwrap();
        terminal.draw(|f| widget.view(f)).unwrap();
        terminal.backend().buffer().clone()
    }

    /// Assert a buffer's *symbols* match `lines` exactly, ignoring styling.
    /// (`Buffer::with_lines` carries default styles, so the built-in
    /// `assert_buffer` also compares styling — we want a symbol-only frame check
    /// for the content tests and a separate styled check below.) Each expected
    /// line must already be the full buffer width.
    fn assert_symbols(buf: &Buffer, lines: &[&str]) {
        let actual: Vec<String> = (0..buf.area().height)
            .map(|y| {
                (0..buf.area().width)
                    .map(|x| buf[(x, y)].symbol())
                    .collect::<String>()
            })
            .collect();
        let expected: Vec<&str> = lines.to_vec();
        assert_eq!(
            actual.iter().map(String::as_str).collect::<Vec<_>>(),
            expected
        );
    }

    const FULL_FRAME: [&str; 9] = [
        "┌ Configure deployment ──────────────────────────┐",
        "│▶ Mode                          Manual          │",
        "│  AWS region                    us-east-1       │",
        "│  Stage name                    ma              │",
        "│  Mirror images to private ECR  Yes             │",
        "│  Migration Assistant version   3.2.1           │",
        "│Tab/↑↓ move · type to edit · Space toggles · Ent│",
        "│                                                │",
        "└────────────────────────────────────────────────┘",
    ];

    #[test]
    fn renders_the_full_form_frame() {
        // The canonical first frame: title, five labelled fields + values, the
        // focus cursor on Mode, and the hint footer — pinned exactly.
        assert_symbols(&render(&wizard(), 50, 9), &FULL_FRAME);
    }

    #[test]
    fn focus_cursor_moves_with_next() {
        // After one Next, the ▶ cursor moves from Mode to AWS region.
        let mut w = wizard();
        w.update(Msg::Next);
        let mut expected = FULL_FRAME;
        expected[1] = "│  Mode                          Manual          │";
        expected[2] = "│▶ AWS region                    us-east-1       │";
        assert_symbols(&render(&w, 50, 9), &expected);
    }

    #[test]
    fn focused_row_and_title_carry_the_right_styles() {
        // The strongest assertion: build the expected buffer AND its style
        // regions, then compare both symbols and styles via `assert_buffer`.
        let mut expected = Buffer::with_lines(FULL_FRAME);
        // Title text is bold (x=1..23 on the top border).
        expected.set_style(Rect::new(1, 0, 22, 1), Style::new().bold());
        // Focused Mode row (y=1): the rendered spans (cursor+label+value, 38
        // cols ending at x=39) are reversed; the label span (32 cols) is also
        // bold. Trailing padding past the text keeps the default style.
        expected.set_style(Rect::new(1, 1, 38, 1), Style::new().reversed());
        expected.set_style(Rect::new(1, 1, 32, 1), Style::new().reversed().bold());
        // Footer hint is dim (x=1..49 on y=6).
        expected.set_style(Rect::new(1, 6, 48, 1), Style::new().dim());

        let mut terminal = Terminal::new(TestBackend::new(50, 9)).unwrap();
        terminal.draw(|f| wizard().view(f)).unwrap();
        terminal.backend().assert_buffer(&expected);
    }

    #[test]
    fn mirror_images_renders_no_when_disabled() {
        let mut w = wizard();
        w.config.mirror_images = false;
        let mut expected = FULL_FRAME;
        expected[4] = "│  Mirror images to private ECR  No              │";
        assert_symbols(&render(&w, 50, 9), &expected);
    }

    #[test]
    fn edits_are_reflected_in_the_next_frame() {
        let mut w = wizard();
        while w.focused() != Field::MaVersion {
            w.update(Msg::Next);
        }
        for _ in 0..5 {
            w.update(Msg::Backspace);
        }
        "9.9.9".chars().for_each(|c| w.update(Msg::Char(c)));

        let mut expected = FULL_FRAME;
        expected[1] = "│  Mode                          Manual          │";
        expected[5] = "│▶ Migration Assistant version   9.9.9           │";
        assert_symbols(&render(&w, 50, 9), &expected);
    }

    #[test]
    fn timeline_renders_exact_phase_rows_with_markers() {
        let mut terminal = Terminal::new(TestBackend::new(46, 12)).unwrap();
        terminal
            .draw(|f| render_timeline(f, "wizard_done"))
            .unwrap();
        // Done phases get ●, the current phase ◐, the rest ○ — asserted exactly.
        assert_symbols(
            &terminal.backend().buffer().clone(),
            &[
                "┌ Previous run ──────────────────────────────┐",
                "│  ● Discover environment done               │",
                "│  ◐ Configure deploy last                   │",
                "│  ○ Deploy CloudFormation                   │",
                "│  ○ Set kubeconfig                          │",
                "│  ○ Build images (optional)                 │",
                "│  ○ Mirror images (optional)                │",
                "│  ○ Install helm chart                      │",
                "│  ○ Ready                                   │",
                "│  ○ Console (Manual mode)                   │",
                "│  ○ Agent handoff (AI mode)                 │",
                "└────────────────────────────────────────────┘",
            ],
        );
    }
}
