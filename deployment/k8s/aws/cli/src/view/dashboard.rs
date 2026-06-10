//! The deploy dashboard — a responsive, immediate-mode view of the deploy
//! timeline plus a scrollable activity log.
//!
//! Design goals (from the redesign proposal):
//!   * **Preserve terminal scrollback.** The dashboard is built to render into a
//!     `Viewport::Inline` region, so the user's shell history above it stays
//!     intact and scrollable — we never take over the alternate screen.
//!   * **Redraw in place while scrolled.** The view is a pure projection of
//!     [`Dashboard`]; every frame re-renders from the model, so a redraw lands
//!     correctly whether or not the user has scrolled the log.
//!   * **In-app history.** [`Dashboard`] keeps the full activity log and a
//!     scroll offset; [`Dashboard::scroll_up`]/[`scroll_down`] page through it.
//!   * **Responsive.** A wide terminal gets a two-pane layout (timeline │ log);
//!     a narrow one collapses to a single stacked column. The breakpoint is a
//!     width threshold, re-evaluated every frame from `area.width`.
//!   * **Testable.** The model is a plain state machine and the view is a pure
//!     function of it, asserted exactly via `TestBackend` (see the tests).
//!
//! This module owns layout + rendering only; the model is fed by the
//! orchestrator (phase transitions) and the run log (activity lines).

use crate::timeline::{self, PhaseStatus};
use ratatui::{
    layout::{Constraint, Layout, Rect},
    style::Stylize,
    text::{Line, Span},
    widgets::{Block, Padding, Paragraph, Widget, Wrap},
    Frame,
};

/// Below this inner width the two-pane layout collapses to a single column.
const WIDE_BREAKPOINT: u16 = 72;

/// A scroll/quit intent, decoded from a key event. Keeping this separate from
/// the IO loop makes the key bindings testable.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DashboardMsg {
    ScrollUp(usize),
    ScrollDown(usize),
    ScrollToBottom,
    Quit,
    None,
}

/// Map a key event to a [`DashboardMsg`]. Up/k scroll one line, PageUp a page,
/// Down/j/PageDown the reverse, End/G tails, q/Esc quits.
pub fn key_to_msg(code: ratatui::crossterm::event::KeyCode, page: usize) -> DashboardMsg {
    use ratatui::crossterm::event::KeyCode::*;
    match code {
        Up | Char('k') => DashboardMsg::ScrollUp(1),
        Down | Char('j') => DashboardMsg::ScrollDown(1),
        PageUp => DashboardMsg::ScrollUp(page),
        PageDown => DashboardMsg::ScrollDown(page),
        End | Char('G') => DashboardMsg::ScrollToBottom,
        Char('q') | Esc => DashboardMsg::Quit,
        _ => DashboardMsg::None,
    }
}

/// The dashboard model: the current deploy phase, the activity log, and the
/// log scroll offset (lines from the bottom; 0 = pinned to newest).
#[derive(Debug, Clone, Default)]
pub struct Dashboard {
    /// The `last_step` key the deploy has reached (drives the timeline).
    pub last_step: String,
    /// Newest-last activity lines.
    pub log: Vec<String>,
    /// How many lines up from the bottom the log is scrolled. 0 = follow tail.
    pub scroll_back: usize,
    /// App title shown in the header.
    pub title: String,
}

impl Dashboard {
    /// A dashboard for `title`, starting at the first phase with an empty log.
    pub fn new(title: impl Into<String>) -> Self {
        Self {
            title: title.into(),
            ..Default::default()
        }
    }

    /// Append an activity line. If the log is tailing (offset 0) it keeps
    /// following the newest line; if the user has scrolled up, their view
    /// stays put (the offset is preserved relative to the bottom).
    pub fn push_log(&mut self, line: impl Into<String>) {
        self.log.push(line.into());
    }

    /// Advance the deploy phase.
    pub fn set_phase(&mut self, last_step: impl Into<String>) {
        self.last_step = last_step.into();
    }

    /// Scroll the log view up by `n` lines (toward older entries), clamped so
    /// we never scroll past the oldest line.
    pub fn scroll_up(&mut self, n: usize) {
        let max = self.log.len().saturating_sub(1);
        self.scroll_back = (self.scroll_back + n).min(max);
    }

    /// Scroll the log view down by `n` lines (toward newer entries).
    pub fn scroll_down(&mut self, n: usize) {
        self.scroll_back = self.scroll_back.saturating_sub(n);
    }

    /// Re-pin the log to the newest line (follow tail).
    pub fn scroll_to_bottom(&mut self) {
        self.scroll_back = 0;
    }

    /// Whether the log is following the newest line.
    pub fn is_tailing(&self) -> bool {
        self.scroll_back == 0
    }

    /// Apply a scroll/quit message. Returns `true` when the loop should exit.
    /// Pure — the IO loop maps key events to these and calls `apply`.
    pub fn apply(&mut self, msg: DashboardMsg) -> bool {
        match msg {
            DashboardMsg::ScrollUp(n) => self.scroll_up(n),
            DashboardMsg::ScrollDown(n) => self.scroll_down(n),
            DashboardMsg::ScrollToBottom => self.scroll_to_bottom(),
            DashboardMsg::Quit => return true,
            DashboardMsg::None => {}
        }
        false
    }

    /// Render the dashboard into the whole frame (immediate mode).
    pub fn view(&self, frame: &mut Frame) {
        frame.render_widget(self, frame.area());
    }

    /// The visible slice of the log for a pane `height` lines tall, honoring the
    /// scroll offset. Returns `(lines, scrolled)` where `scrolled` is true when
    /// older lines are hidden above the window.
    fn visible_log(&self, height: usize) -> (&[String], bool) {
        if self.log.is_empty() || height == 0 {
            return (&[], false);
        }
        // The bottom of the window is `scroll_back` lines up from the newest.
        let end = self.log.len().saturating_sub(self.scroll_back);
        let start = end.saturating_sub(height);
        (&self.log[start..end], start > 0)
    }
}

impl Widget for &Dashboard {
    fn render(self, area: Rect, buf: &mut ratatui::buffer::Buffer) {
        // Header (1 row) + body (rest). The header carries the title and a
        // scroll hint so the controls are discoverable.
        let [header, body] =
            Layout::vertical([Constraint::Length(1), Constraint::Min(0)]).areas(area);
        self.render_header(header, buf);

        // Responsive: wide → timeline │ log side by side; narrow → stacked.
        if body.width >= WIDE_BREAKPOINT {
            let [tl, log] =
                Layout::horizontal([Constraint::Length(34), Constraint::Min(20)]).areas(body);
            self.render_timeline(tl, buf);
            self.render_log(log, buf);
        } else {
            // Stacked: timeline takes what it needs, log fills the rest.
            let [tl, log] = Layout::vertical([
                Constraint::Length(timeline::PHASES.len() as u16 + 2),
                Constraint::Min(3),
            ])
            .areas(body);
            self.render_timeline(tl, buf);
            self.render_log(log, buf);
        }
    }
}

impl Dashboard {
    fn render_header(&self, area: Rect, buf: &mut ratatui::buffer::Buffer) {
        let status = if self.is_tailing() {
            "● live".to_string()
        } else {
            format!("↑ history −{}", self.scroll_back)
        };
        // Title left, scroll status right — the right span is padded to fit.
        let title = Span::from(format!(" {} ", self.title)).bold().on_blue();
        let status = Span::from(format!(" {status} ")).dim();
        let used = title.width() + status.width();
        let gap = (area.width as usize).saturating_sub(used);
        let line = Line::from(vec![title, Span::raw(" ".repeat(gap)), status]);
        Paragraph::new(line).render(area, buf);
    }

    fn render_timeline(&self, area: Rect, buf: &mut ratatui::buffer::Buffer) {
        let lines: Vec<Line> = timeline::rows(&self.last_step)
            .iter()
            .map(|r| {
                let glyph = Span::from(format!("{} ", r.status.marker()));
                let (glyph, label) = match r.status {
                    PhaseStatus::Done => (glyph.green(), Span::raw(r.label).dim()),
                    PhaseStatus::Last => (glyph.yellow(), Span::raw(r.label).bold()),
                    PhaseStatus::Pending => (glyph.dim(), Span::raw(r.label).dim()),
                };
                Line::from(vec![glyph, label])
            })
            .collect();
        let block = Block::bordered()
            .title(" Progress ".bold())
            .padding(Padding::horizontal(1));
        Paragraph::new(lines).block(block).render(area, buf);
    }

    fn render_log(&self, area: Rect, buf: &mut ratatui::buffer::Buffer) {
        let block = Block::bordered()
            .title(" Activity ".bold())
            .padding(Padding::horizontal(1));
        let inner_h = block.inner(area).height as usize;
        let (lines, more_above) = self.visible_log(inner_h);

        let mut rendered: Vec<Line> = Vec::with_capacity(lines.len() + 1);
        if more_above {
            rendered.push(Line::from("⋯ older".dim()));
        }
        rendered.extend(lines.iter().map(|l| Line::from(l.as_str())));
        // Wrap long lines so a narrow terminal stays readable (responsive).
        Paragraph::new(rendered)
            .block(block)
            .wrap(Wrap { trim: false })
            .render(area, buf);
    }
}

/// Drive the dashboard interactively in an INLINE viewport.
///
/// `Viewport::Inline(height)` renders the dashboard in a fixed band at the
/// bottom of the terminal while leaving the scrollback above it untouched — so
/// the operator can scroll their shell history up and the dashboard keeps
/// redrawing in place. Resize events re-flow the layout (the widget reads the
/// width every frame); the scroll keys page the in-app activity log.
///
/// This is the only IO in the module; the model + view it drives are unit
/// tested. Returns when the user quits (q/Esc) or the closure signals done.
///
/// `step` is called once per loop with the latest model so the caller can push
/// log lines / advance the phase; it returns `false` to keep running.
pub fn run_inline<F>(model: Dashboard, height: u16, step: F) -> std::io::Result<()>
where
    F: FnMut(&mut Dashboard) -> bool,
{
    use ratatui::{TerminalOptions, Viewport};

    let mut terminal = ratatui::try_init_with_options(TerminalOptions {
        viewport: Viewport::Inline(height),
    })?;
    // Run the loop, then ALWAYS restore the terminal before propagating any
    // error — keeping teardown here means the loop itself stays flat.
    let result = run_event_loop(&mut terminal, model, height, step);
    ratatui::restore();
    result
}

/// The draw → step → input loop. Split out of [`run_inline`] so that function
/// only owns terminal setup + guaranteed teardown; this stays a flat loop.
/// Returns when the user quits (q/Esc) or `step` signals completion.
fn run_event_loop<F>(
    terminal: &mut ratatui::DefaultTerminal,
    mut model: Dashboard,
    height: u16,
    mut step: F,
) -> std::io::Result<()>
where
    F: FnMut(&mut Dashboard) -> bool,
{
    let page = height.saturating_sub(2).max(1) as usize;
    loop {
        terminal.draw(|f| model.view(f))?;
        if step(&mut model) || poll_quit(&mut model, page)? {
            return Ok(());
        }
    }
}

/// Poll for one input event (100 ms tick, so the loop also redraws when idle)
/// and apply it to `model`. Returns `true` when the key asks the loop to quit.
fn poll_quit(model: &mut Dashboard, page: usize) -> std::io::Result<bool> {
    use ratatui::crossterm::event::{self, Event};
    if event::poll(std::time::Duration::from_millis(100))? {
        if let Event::Key(key) = event::read()? {
            if key.is_press() {
                return Ok(model.apply(key_to_msg(key.code, page)));
            }
        }
    }
    Ok(false)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ratatui::{backend::TestBackend, Terminal};

    fn sample() -> Dashboard {
        let mut d = Dashboard::new("Migration Assistant");
        d.set_phase("cfn_done");
        for i in 1..=6 {
            d.push_log(format!("step {i}: working"));
        }
        d
    }

    /// Symbol-only frame assertion (style-independent), each expected line
    /// already full width. (See RATATUI_DEEP_DIVE.md §9.)
    fn assert_frame(d: &Dashboard, w: u16, h: u16, expected: &[&str]) {
        let mut t = Terminal::new(TestBackend::new(w, h)).unwrap();
        t.draw(|f| d.view(f)).unwrap();
        let buf = t.backend().buffer().clone();
        let actual: Vec<String> = (0..buf.area().height)
            .map(|y| {
                (0..buf.area().width)
                    .map(|x| buf[(x, y)].symbol())
                    .collect()
            })
            .collect();
        assert_eq!(
            actual.iter().map(String::as_str).collect::<Vec<_>>(),
            expected
        );
    }

    // ---- model ----

    #[test]
    fn scroll_clamps_at_both_ends() {
        let mut d = sample(); // 6 log lines
        assert!(d.is_tailing());
        d.scroll_down(3); // already at bottom — no-op
        assert_eq!(d.scroll_back, 0);
        d.scroll_up(100); // clamps to len-1
        assert_eq!(d.scroll_back, 5);
        d.scroll_down(2);
        assert_eq!(d.scroll_back, 3);
        d.scroll_to_bottom();
        assert!(d.is_tailing());
    }

    #[test]
    fn visible_log_follows_tail_then_scrolls() {
        let d = sample();
        // A 3-tall window tailing shows the last 3 lines, nothing hidden below.
        let (lines, more_above) = d.visible_log(3);
        assert_eq!(
            lines,
            ["step 4: working", "step 5: working", "step 6: working"]
        );
        assert!(more_above);

        // Scrolled up 2: window shifts to the older block.
        let mut d2 = d.clone();
        d2.scroll_up(2);
        let (lines, more_above) = d2.visible_log(3);
        assert_eq!(
            lines,
            ["step 2: working", "step 3: working", "step 4: working"]
        );
        assert!(more_above);
    }

    #[test]
    fn empty_log_renders_nothing_visible() {
        let d = Dashboard::new("x");
        assert_eq!(d.visible_log(5), (&[][..], false));
    }

    #[test]
    fn key_bindings_map_to_scroll_messages() {
        use ratatui::crossterm::event::KeyCode;
        assert_eq!(key_to_msg(KeyCode::Up, 5), DashboardMsg::ScrollUp(1));
        assert_eq!(key_to_msg(KeyCode::Char('k'), 5), DashboardMsg::ScrollUp(1));
        assert_eq!(key_to_msg(KeyCode::PageUp, 5), DashboardMsg::ScrollUp(5));
        assert_eq!(key_to_msg(KeyCode::Down, 5), DashboardMsg::ScrollDown(1));
        assert_eq!(
            key_to_msg(KeyCode::PageDown, 5),
            DashboardMsg::ScrollDown(5)
        );
        assert_eq!(key_to_msg(KeyCode::End, 5), DashboardMsg::ScrollToBottom);
        assert_eq!(key_to_msg(KeyCode::Char('q'), 5), DashboardMsg::Quit);
        assert_eq!(key_to_msg(KeyCode::Esc, 5), DashboardMsg::Quit);
        assert_eq!(key_to_msg(KeyCode::Char('z'), 5), DashboardMsg::None);
    }

    #[test]
    fn apply_quit_signals_exit_others_continue() {
        let mut d = sample();
        assert!(!d.apply(DashboardMsg::ScrollUp(2)));
        assert_eq!(d.scroll_back, 2);
        assert!(!d.apply(DashboardMsg::ScrollToBottom));
        assert!(d.is_tailing());
        assert!(d.apply(DashboardMsg::Quit));
    }

    // ---- responsive layout ----

    #[test]
    fn wide_terminal_uses_two_pane_layout() {
        // 80 cols ≥ breakpoint: Progress and Activity sit side by side.
        assert_frame(
            &sample(),
            80,
            9,
            &[
                " Migration Assistant                                                     ● live ",
                "┌ Progress ──────────────────────┐┌ Activity ──────────────────────────────────┐",
                "│ ● Discover environment         ││ step 1: working                            │",
                "│ ● Configure deploy             ││ step 2: working                            │",
                "│ ◐ Deploy CloudFormation        ││ step 3: working                            │",
                "│ ○ Set kubeconfig               ││ step 4: working                            │",
                "│ ○ Build images (optional)      ││ step 5: working                            │",
                "│ ○ Mirror images (optional)     ││ step 6: working                            │",
                "└────────────────────────────────┘└────────────────────────────────────────────┘",
            ],
        );
    }

    #[test]
    fn narrow_terminal_stacks_panes() {
        // 40 cols < breakpoint: Progress on top, Activity below.
        assert_frame(
            &sample(),
            40,
            16,
            &[
                " Migration Assistant             ● live ",
                "┌ Progress ────────────────────────────┐",
                "│ ● Discover environment               │",
                "│ ● Configure deploy                   │",
                "│ ◐ Deploy CloudFormation              │",
                "│ ○ Set kubeconfig                     │",
                "│ ○ Build images (optional)            │",
                "│ ○ Mirror images (optional)           │",
                "│ ○ Install helm chart                 │",
                "│ ○ Ready                              │",
                "│ ○ Console (Manual mode)              │",
                "│ ○ Agent handoff (AI mode)            │",
                "└──────────────────────────────────────┘",
                "┌ Activity ────────────────────────────┐",
                "│ ⋯ older                              │",
                "└──────────────────────────────────────┘",
            ],
        );
    }

    #[test]
    fn header_shows_history_offset_when_scrolled() {
        let mut d = sample();
        d.scroll_up(2);
        let mut t = Terminal::new(TestBackend::new(40, 16)).unwrap();
        t.draw(|f| d.view(f)).unwrap();
        let header: String = (0..40)
            .map(|x| t.backend().buffer()[(x, 0)].symbol())
            .collect();
        assert!(header.contains("↑ history −2"), "header was: {header:?}");
    }
}
