package msg

// ----------------------------------------------------------------------------
// Dialog identification + result messages
// ----------------------------------------------------------------------------
//
// Dialogs are the modal overlay surface in internal/ui/dialog. The
// stack itself doesn't know about page identity; instead, the page
// that pushes a dialog tags it with a DialogID, and the dialog emits
// a DialogResultMsg carrying that ID when it dismisses. The root
// Model routes the result back to whichever page is currently active
// — pages disambiguate concurrent dialogs by inspecting the ID.

// DialogID identifies a dialog instance for result routing. Stable
// across the dialog's lifecycle; pages typically generate unique IDs
// per push site (e.g. PageReview.confirmWriteBrief, PageHandoff.cancel).
type DialogID string

// ConfirmResultMsg is emitted when a Confirm dialog dismisses. The
// originating page matches by ID; Confirmed=true on yes, false on no.
type ConfirmResultMsg struct {
	ID        DialogID
	Confirmed bool
}

// ErrorAckMsg is emitted when an Error dialog is acknowledged.
// Pages that pushed a recoverable error dialog can listen for the
// ack to re-enable input or transition state. ID matches the push.
type ErrorAckMsg struct {
	ID DialogID
}

// DialogPushMsg is the way a page asks the root Model to push a
// dialog onto the stack. Pages must NOT mutate the dialog stack
// themselves (it lives in the root Model); they emit this Cmd and
// let the root Model serialize stack mutations. Dialog is an
// interface-typed payload satisfied by the concrete dialog types in
// internal/ui/dialog (Confirm, Error). It is `any` here to keep
// internal/ui/msg free of imports from internal/ui/dialog (msg is
// the lowest-tier UI package).
type DialogPushMsg struct {
	Dialog any
}

// DialogPopMsg is the way a dialog asks the root Model to remove
// itself from the stack. Dialogs emit this in their Update when
// they decide to dismiss; they pair it with the appropriate result
// msg via tea.Batch. The root Model pops the top of the stack on
// receipt — there is no "pop a specific dialog" semantics; modal
// stacks are LIFO.
type DialogPopMsg struct{}
