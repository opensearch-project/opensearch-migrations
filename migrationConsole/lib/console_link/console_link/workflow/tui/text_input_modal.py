import re
from typing import Callable, Optional

from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Container, Horizontal
from textual.screen import ModalScreen
from textual.widgets import Button, Input, Static
from rich.markup import escape


class TextInputModal(ModalScreen[Optional[str]]):
    CSS = """
    TextInputModal { align: center middle; background: $background 60%; }
    #dialog { width: 72; height: auto; border: thick $primary; background: $surface; padding: 1 2; }
    #prompt { margin-bottom: 1; }
    #documentation { color: gray; margin-bottom: 1; }
    #value { margin-bottom: 1; }
    #validation { color: $error; margin-bottom: 1; min-height: 1; }
    #remote-validation { margin-bottom: 1; min-height: 1; }
    #buttons { align: center middle; height: 3; }
    Button { margin: 0 1; min-width: 12; }
    """
    BINDINGS = [
        Binding("enter", "submit", "Save", show=False, priority=True),
        Binding("escape", "cancel", "Cancel", show=False),
    ]

    def __init__(
        self,
        prompt: str,
        initial_value: str = "",
        documentation: str = "",
        validation: Optional[dict] = None,
        required: bool = False,
        on_change: Optional[Callable[[str, bool], None]] = None,
    ):
        super().__init__()
        self.prompt = prompt
        self.initial_value = initial_value
        self.documentation = documentation
        self.validation = validation or {}
        self.required = required
        self.on_change = on_change
        self._compiled_pattern = self._compile_pattern(self.validation.get("pattern"))

    def compose(self) -> ComposeResult:
        with Container(id="dialog"):
            yield Static(escape(self.prompt), id="prompt")
            yield Static(escape(self.documentation), id="documentation")
            yield Input(value=self.initial_value, id="value", select_on_focus=False)
            yield Static("", id="validation")
            yield Static("", id="remote-validation")
            with Horizontal(id="buttons"):
                yield Button("Save", id="save", variant="success")
                yield Button("Cancel", id="cancel", variant="error")

    def on_mount(self) -> None:
        input_widget = self.query_one("#value", Input)
        input_widget.focus()
        input_widget.cursor_position = len(input_widget.value)
        self._update_validation(input_widget.value)

    def action_cancel(self) -> None:
        self.dismiss(None)

    def action_submit(self) -> None:
        value = self.query_one("#value", Input).value
        if self._update_validation(value):
            return
        self.dismiss(value)

    def on_key(self, event) -> None:
        if event.key == "enter":
            event.stop()
            self.action_submit()

    def on_input_submitted(self, event: Input.Submitted) -> None:
        if self._update_validation(event.value):
            return
        self.dismiss(event.value)

    def on_input_changed(self, event: Input.Changed) -> None:
        message = self._update_validation(event.value)
        if message:
            self.set_remote_validation("")
            if self.on_change:
                self.on_change(event.value, False)
            return
        if self.on_change:
            self.on_change(event.value, True)

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "save":
            self.action_submit()
        else:
            self.dismiss(None)

    def _update_validation(self, value: str) -> Optional[str]:
        message = self._validation_message(value)
        self.query_one("#validation", Static).update(escape(message or ""))
        self.query_one("#save", Button).disabled = bool(message)
        return message

    def set_remote_validation(self, message: str, severity: str = "warning") -> None:
        if not self.is_mounted:
            return
        color = {
            "ok": "green",
            "error": "red",
            "warning": "yellow",
            "pending": "cyan",
        }.get(severity, "yellow")
        if not message:
            self.query_one("#remote-validation", Static).update("")
            return
        self.query_one("#remote-validation", Static).update(f"[{color}]{escape(message)}[/]")

    def _validation_message(self, value: str) -> Optional[str]:
        if self.required and not value.strip():
            return "This field is required."
        if self._compiled_pattern is not None and value != "" and not self._compiled_pattern.search(value):
            return self.validation.get("message") or "Value does not match the expected format."
        return None

    @staticmethod
    def _compile_pattern(pattern: Optional[str]):
        if not pattern:
            return None
        try:
            return re.compile(pattern)
        except re.error:
            return None
