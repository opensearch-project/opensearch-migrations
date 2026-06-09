// domain/app.rs — top-level pure state.
//
// Owns:
//   * `runtime` : the post-deploy operational state (panels, ops, config, modals)
//   * `should_quit`, `size` : presentation-loop housekeeping
//
// Two ways to mutate App:
//
//   1. App::update(Message)        — keypress / resize / quit. Pure.
//   2. App::dispatch(RuntimeEvent) — async service callback (op progress,
//                                    workflow status, etc.) Pure.
//
// Both return a NEW App. No I/O, no async, no ratatui. The renderer reads
// `&App` and the event loop owns the App by value.
//
// Keypress translation lives here (not in `domain/runtime`) because the
// translation depends on the current panel — e.g. 'q' on a normal panel
// quits, 'q' inside a typed-token modal becomes a buffer character.

use crate::domain::message::Message;
use crate::domain::runtime::{Runtime, RuntimeEvent, RuntimePanel};

#[derive(Debug, Clone, PartialEq)]
pub struct App {
    pub runtime: Runtime,
    pub should_quit: bool,
    pub size: (u16, u16),
}

impl Default for App {
    fn default() -> Self {
        App {
            runtime: Runtime::default(),
            should_quit: false,
            size: (120, 40),
        }
    }
}

impl App {
    /// Pure keypress / terminal-event update.
    pub fn update(self, msg: Message) -> App {
        match msg {
            Message::Quit => App { should_quit: true, ..self },
            Message::Resize(w, h) => App { size: (w, h), ..self },

            // When a confirmation modal is open, all printable input goes to
            // the typed-token buffer — even 'q'. The modal is exclusive.
            other if self.runtime.confirm_active() => {
                let ev = match other {
                    Message::Char(c) => Some(RuntimeEvent::ConfirmChar(c)),
                    Message::Backspace => Some(RuntimeEvent::ConfirmBackspace),
                    Message::Escape => Some(RuntimeEvent::ConfirmCancel),
                    // Enter on a satisfied confirmation is the "go" signal —
                    // App layer decides what op to fire; runtime just resolves.
                    // We resolve here as cancel-or-proceed by clearing the modal;
                    // the App owner is expected to inspect runtime.confirmation()
                    // BEFORE feeding Enter to know whether to dispatch the op.
                    Message::Enter => Some(RuntimeEvent::ConfirmCancel),
                    _ => None,
                };
                match ev {
                    Some(e) => App { runtime: self.runtime.update(e), ..self },
                    None => self,
                }
            }

            // Tab: panel switch. Cycles through the four "home" panels.
            Message::Tab => {
                let next = match self.runtime.panel {
                    RuntimePanel::WorkflowView => RuntimePanel::ConfigEdit,
                    RuntimePanel::ConfigEdit   => RuntimePanel::Approvals,
                    RuntimePanel::Approvals    => RuntimePanel::Status,
                    RuntimePanel::Status       => RuntimePanel::WorkflowView,
                    RuntimePanel::LogView { .. } => RuntimePanel::WorkflowView,
                    RuntimePanel::Confirm { .. } => self.runtime.panel.clone(),
                };
                App { runtime: self.runtime.update(RuntimeEvent::SwitchPanel(next)), ..self }
            }

            // Outside a modal, 'q' or Esc quits.
            Message::Char('q') | Message::Escape => App { should_quit: true, ..self },

            // Other keys are panel-specific and routed by the renderer/owner.
            // For the POC we ignore them — a richer App would dispatch into
            // panel-specific event handlers here.
            _ => self,
        }
    }

    /// Pure dispatch of an async-service-originated event.
    pub fn dispatch(self, ev: RuntimeEvent) -> App {
        App { runtime: self.runtime.update(ev), ..self }
    }
}

// ──────────────────────────────────────────────────────────────────────────
#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::confirmation::Confirmation;

    #[test]
    fn quit_sets_flag() {
        let a = App::default().update(Message::Quit);
        assert!(a.should_quit);
    }

    #[test]
    fn resize_updates_size() {
        let a = App::default().update(Message::Resize(80, 24));
        assert_eq!(a.size, (80, 24));
    }

    #[test]
    fn char_q_quits_outside_modal() {
        let a = App::default().update(Message::Char('q'));
        assert!(a.should_quit);
    }

    #[test]
    fn escape_quits_outside_modal() {
        let a = App::default().update(Message::Escape);
        assert!(a.should_quit);
    }

    #[test]
    fn tab_cycles_panels() {
        let a = App::default();
        assert_eq!(a.runtime.panel, RuntimePanel::WorkflowView);
        let a = a.update(Message::Tab);
        assert_eq!(a.runtime.panel, RuntimePanel::ConfigEdit);
        let a = a.update(Message::Tab);
        assert_eq!(a.runtime.panel, RuntimePanel::Approvals);
        let a = a.update(Message::Tab);
        assert_eq!(a.runtime.panel, RuntimePanel::Status);
        let a = a.update(Message::Tab);
        assert_eq!(a.runtime.panel, RuntimePanel::WorkflowView);
    }

    #[test]
    fn modal_swallows_q_and_does_not_quit() {
        let a = App::default()
            .dispatch(RuntimeEvent::OpenConfirm(Confirmation::new("act", "imp", "OK")));
        let a = a.update(Message::Char('q'));
        assert!(!a.should_quit, "modal must capture q as buffer input");
        assert!(a.runtime.confirm_active());
    }

    #[test]
    fn modal_escape_cancels_returns_to_behind() {
        let a = App::default()
            .dispatch(RuntimeEvent::SwitchPanel(RuntimePanel::ConfigEdit))
            .dispatch(RuntimeEvent::OpenConfirm(Confirmation::new("act", "imp", "OK")));
        let a = a.update(Message::Escape);
        assert!(!a.should_quit, "escape inside modal cancels, doesn't quit");
        assert_eq!(a.runtime.panel, RuntimePanel::ConfigEdit);
    }

    #[test]
    fn modal_typed_token_satisfies() {
        let mut a = App::default()
            .dispatch(RuntimeEvent::OpenConfirm(Confirmation::new("act", "imp", "OK")));
        a = a.update(Message::Char('O'));
        a = a.update(Message::Char('K'));
        assert!(a.runtime.confirmation().unwrap().satisfied);
    }

    #[test]
    fn dispatch_passes_event_to_runtime() {
        use crate::extension::op::{OpId, OpStatus};
        let a = App::default()
            .dispatch(RuntimeEvent::OpStarted { id: OpId(1), label: "x".into() })
            .dispatch(RuntimeEvent::OpUpdated {
                id: OpId(1),
                update: crate::extension::op::OpUpdate::StatusChanged(OpStatus::Running),
            });
        assert_eq!(a.runtime.op(OpId(1)).unwrap().status, OpStatus::Running);
    }

    #[test]
    fn update_is_pure() {
        let a = App::default();
        let b = a.clone().update(Message::Tab);
        let c = a.clone().update(Message::Tab);
        assert_eq!(b, c);
    }
}
