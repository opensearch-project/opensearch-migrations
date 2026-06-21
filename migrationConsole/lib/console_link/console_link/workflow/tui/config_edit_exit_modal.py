from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Container, Horizontal
from textual.screen import ModalScreen
from textual.widgets import Button, Static
from rich.markup import escape

from .modal_button_navigation import BUTTON_ARROW_BINDINGS, ButtonArrowNavigationMixin, ModalButton


class ConfigEditExitModal(ButtonArrowNavigationMixin, ModalScreen[str]):
    CSS = """
    ConfigEditExitModal { align: center middle; background: $background 60%; }
    #dialog { width: 76; height: auto; border: thick $primary; background: $surface; padding: 1 2; }
    #message { text-align: center; margin-bottom: 1; }
    #status { color: gray; text-align: center; margin-bottom: 1; }
    #buttons { align: center middle; height: 3; }
    Button { margin: 0 1; min-width: 14; }
    """
    BINDINGS = [
        *BUTTON_ARROW_BINDINGS,
        Binding("d", "discard", "Discard", show=False),
        Binding("s", "save", "Save", show=False),
        Binding("r", "return_to_editor", "Return", show=False),
        Binding("enter", "submit_focused", "Submit", show=False),
        Binding("escape", "return_to_editor", "Return", show=False),
    ]

    def __init__(
        self,
        message: str,
        status_message: str,
        default_action: str = "return",
        save_label: str = "Save",
        discard_label: str = "Discard",
    ):
        super().__init__()
        self.message = message
        self.status_message = status_message
        self.default_action = default_action
        self.save_label = save_label
        self.discard_label = discard_label

    def compose(self) -> ComposeResult:
        with Container(id="dialog"):
            yield Static(escape(self.message), id="message")
            yield Static(escape(self.status_message), id="status")
            with Horizontal(id="buttons"):
                yield ModalButton(f"{escape(self.discard_label)} (d)", id="discard", variant="error")
                yield ModalButton(f"{escape(self.save_label)} (s)", id="save", variant="success")
                yield ModalButton("Return (r)", id="return")

    def on_mount(self) -> None:
        button_id = {
            "discard": "#discard",
            "save": "#save",
            "return": "#return",
        }.get(self.default_action, "#return")
        self.query_one(button_id, Button).focus()

    def action_discard(self) -> None:
        self.dismiss("discard")

    def action_save(self) -> None:
        self.dismiss("save")

    def action_return_to_editor(self) -> None:
        self.dismiss("return")

    def action_submit_focused(self) -> None:
        focused = self.focused
        if isinstance(focused, Button) and focused.id:
            self.dismiss(str(focused.id))

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id:
            self.dismiss(str(event.button.id))
