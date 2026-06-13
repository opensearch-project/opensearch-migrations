from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Container, Horizontal
from textual.screen import ModalScreen
from textual.widgets import Button, Input, Static
from rich.markup import escape


class TextInputModal(ModalScreen[str]):
    CSS = """
    TextInputModal { align: center middle; background: $background 60%; }
    #dialog { width: 72; height: auto; border: thick $primary; background: $surface; padding: 1 2; }
    #prompt { margin-bottom: 1; }
    #value { margin-bottom: 1; }
    #buttons { align: center middle; height: 3; }
    Button { margin: 0 1; min-width: 12; }
    """
    BINDINGS = [
        Binding("escape", "cancel", "Cancel", show=False),
    ]

    def __init__(self, prompt: str, initial_value: str = ""):
        super().__init__()
        self.prompt = prompt
        self.initial_value = initial_value

    def compose(self) -> ComposeResult:
        with Container(id="dialog"):
            yield Static(escape(self.prompt), id="prompt")
            yield Input(value=self.initial_value, id="value")
            with Horizontal(id="buttons"):
                yield Button("Save", id="save", variant="success")
                yield Button("Cancel", id="cancel", variant="error")

    def on_mount(self) -> None:
        self.query_one("#value", Input).focus()

    def action_cancel(self) -> None:
        self.dismiss(None)

    def on_input_submitted(self, event: Input.Submitted) -> None:
        self.dismiss(event.value)

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "save":
            self.dismiss(self.query_one("#value", Input).value)
        else:
            self.dismiss(None)
