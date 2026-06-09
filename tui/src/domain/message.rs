// domain/message.rs — keypress / terminal events.
//
// Two layers:
//   * Message      : raw input from the event loop (key, resize, quit)
//   * RuntimeEvent : already in domain::runtime, fed by App::update after
//                    translating Message + current panel context.
//
// Async-service callbacks (op progress, workflow status) feed RuntimeEvent
// directly via App::dispatch_runtime — they never become a Message.

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Message {
    /// Quit the app.
    Quit,
    /// Terminal resize.
    Resize(u16, u16),
    /// A printable character (already filtered: no control chars, no esc).
    Char(char),
    /// Backspace key.
    Backspace,
    /// Enter key.
    Enter,
    /// Escape key.
    Escape,
    /// Tab key (panel-switch).
    Tab,
    /// Up arrow.
    Up,
    /// Down arrow.
    Down,
}
