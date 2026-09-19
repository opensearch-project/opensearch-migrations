from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Container, Horizontal
from textual.screen import ModalScreen
from textual.widgets import Static, Button
from rich.markup import escape

from .modal_button_navigation import BUTTON_ARROW_BINDINGS, ButtonArrowNavigationMixin, ModalButton


class ConfirmModal(ButtonArrowNavigationMixin, ModalScreen[bool]):
    CSS = """
    ConfirmModal { align: center middle; background: $background 60%; }
    #dialog { width: 60; height: auto; border: thick $primary; background: $surface; padding: 0 1; }
    #question { text-align: center; margin-bottom: 1; }
    #buttons { align: center middle; height: 1; }
    Button { margin: 0 1 0 0; min-width: 5; height: 1; min-height: 1; border: none; padding: 0 1; }
    """
    BINDINGS = [
        *BUTTON_ARROW_BINDINGS,
        Binding("y", "confirm", "Yes"),
        Binding("n", "cancel", "No"),
        Binding("enter", "submit_focused", "Submit", show=False),
        Binding("escape", "cancel", "No", show=False)
    ]

    def __init__(
        self,
        message: str,
        confirm_label: str = "Yes",
        cancel_label: str = "No",
        default_confirm: bool = True,
    ):
        super().__init__()
        self.message = message
        self.confirm_label = confirm_label
        self.cancel_label = cancel_label
        self.default_confirm = default_confirm

    def compose(self) -> ComposeResult:
        with Container(id="dialog"):
            yield Static(escape(self.message), id="question")
            with Horizontal(id="buttons"):
                yield ModalButton(f"{escape(self.confirm_label)} (y)", id="yes", variant="success")
                yield ModalButton(f"{escape(self.cancel_label)} (n)", id="no", variant="error")

    def on_mount(self) -> None:
        default_button_id = "#yes" if self.default_confirm else "#no"
        self.query_one(default_button_id, Button).focus()

    def action_confirm(self) -> None:
        self.dismiss(True)

    def action_cancel(self) -> None:
        self.dismiss(False)

    def action_submit_focused(self) -> None:
        focused = self.focused
        if isinstance(focused, Button):
            self.dismiss(focused.id == "yes")

    def on_button_pressed(self, event: Button.Pressed) -> None:
        self.dismiss(event.button.id == "yes")
