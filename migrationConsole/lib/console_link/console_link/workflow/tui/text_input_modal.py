import re
from typing import Any, Callable, Optional
from urllib.parse import urlencode

from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Container, Horizontal
from textual.screen import ModalScreen
from textual.widgets import Button, Input, Static
from rich.markup import escape

from .doc_markup import documentation_markup
from .modal_button_navigation import BUTTON_ARROW_BINDINGS, ButtonArrowNavigationMixin, ModalButton
from .modal_results import CLEAR_VALUE


class TextInputModal(ButtonArrowNavigationMixin, ModalScreen[Optional[Any]]):
    VALUE_SELECTOR = "#value"

    CSS = """
    TextInputModal { align: center middle; background: $background 60%; }
    #dialog { width: 72; height: auto; border: thick $primary; background: $surface; padding: 1 2; }
    #prompt { margin-bottom: 1; }
    #documentation { color: gray; margin-bottom: 1; }
    #value { margin-bottom: 1; }
    #validation { color: $error; margin-bottom: 0; min-height: 1; }
    #remote-validation { margin-bottom: 1; min-height: 1; }
    #regex-help { color: gray; margin-top: 1; margin-bottom: 0; }
    #regex-samples { color: gray; margin-bottom: 1; }
    #buttons { align: center middle; height: 1; }
    Button { margin: 0 1 0 0; min-width: 5; height: 1; min-height: 1; border: none; padding: 0 1; }
    """
    BUTTON_NAV_SELECTOR = "#buttons Button"
    BINDINGS = [
        *BUTTON_ARROW_BINDINGS,
        Binding("enter", "submit_focused", "Save", show=False, priority=True),
        Binding("t", "test_regex", "Test regex", show=False),
        Binding("escape", "cancel", "Cancel", show=False),
    ]

    def __init__(
        self,
        prompt: str,
        initial_value: str = "",
        documentation: str = "",
        validation: Optional[dict] = None,
        required: bool = False,
        clear_allowed: bool = False,
        clear_label: str = "Clear",
        on_change: Optional[Callable[[str, bool], None]] = None,
        regex_help: Optional[dict] = None,
    ):
        super().__init__()
        self.prompt = prompt
        self.initial_value = initial_value
        self.documentation = documentation
        self.validation = validation or {}
        self.required = required
        self.clear_allowed = clear_allowed
        self.clear_label = clear_label
        self.on_change = on_change
        self.regex_help = regex_help or {}
        self._compiled_pattern = self._compile_pattern(self.validation.get("pattern"))

    def compose(self) -> ComposeResult:
        with Container(id="dialog"):
            yield Static(escape(self.prompt), id="prompt")
            yield Static(documentation_markup(self.documentation), id="documentation")
            yield Input(value=self.initial_value, id="value", select_on_focus=False)
            yield Static("", id="validation")
            yield Static("", id="remote-validation")
            yield Static("", id="regex-help")
            yield Static("", id="regex-samples")
            with Horizontal(id="buttons"):
                yield ModalButton("Save (<Enter>)", id="save", variant="success")
                if self.regex_help:
                    yield ModalButton("Test (t)", id="test")
                if self.clear_allowed:
                    yield ModalButton(f"{self.clear_label}", id="clear")
                yield ModalButton("Cancel (Esc)", id="cancel", variant="error")

    def on_mount(self) -> None:
        self.query_one("#documentation", Static).display = bool(self.documentation)
        input_widget = self.query_one(self.VALUE_SELECTOR, Input)
        input_widget.focus()
        input_widget.cursor_position = len(input_widget.value)
        self._update_validation(input_widget.value)
        self._update_regex_help(input_widget.value)

    def action_cancel(self) -> None:
        self.dismiss(None)

    def action_submit(self) -> None:
        value = self.query_one(self.VALUE_SELECTOR, Input).value
        if self._update_validation(value):
            return
        self.dismiss(value)

    def action_clear(self) -> None:
        if self.clear_allowed:
            self.dismiss(CLEAR_VALUE)

    def action_test_regex(self) -> None:
        if not self.regex_help:
            return
        self.app.open_url(self._current_regex101_url())

    def action_submit_focused(self) -> None:
        focused = self.app.focused or self.focused
        if isinstance(focused, Button):
            if focused.id == "test":
                self.action_test_regex()
                return
            if focused.id == "clear":
                self.action_clear()
                return
            if focused.id == "cancel":
                self.action_cancel()
                return
        self.action_submit()

    def on_key(self, event) -> None:
        if event.key == "enter":
            event.stop()
            self.action_submit_focused()

    def on_input_submitted(self, event: Input.Submitted) -> None:
        if self._update_validation(event.value):
            return
        self.dismiss(event.value)

    def on_input_changed(self, event: Input.Changed) -> None:
        message = self._update_validation(event.value)
        self._update_regex_help(event.value)
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
        elif event.button.id == "test":
            self.action_test_regex()
        elif event.button.id == "clear":
            self.action_clear()
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

    def _update_regex_help(self, value: str) -> None:
        help_widget = self.query_one("#regex-help", Static)
        samples_widget = self.query_one("#regex-samples", Static)
        if not self.regex_help:
            help_widget.display = False
            samples_widget.display = False
            return

        samples = self._regex_help_samples()
        message = str(self.regex_help.get("message") or "Java regex used by the capture proxy.")
        help_widget.display = True
        samples_widget.display = bool(samples)
        help_widget.update(self._regex_help_markup(message))
        if samples:
            samples_widget.update("Samples:\n" + "\n".join(f"  {escape(sample)}" for sample in samples))
        else:
            samples_widget.update("")

    def _current_regex101_url(self) -> str:
        value = self.query_one(self.VALUE_SELECTOR, Input).value if self.is_mounted else self.initial_value
        return self._regex101_url(value, self._regex_help_samples())

    def _regex_help_samples(self) -> list[str]:
        return [str(sample) for sample in self.regex_help.get("testStrings", []) if str(sample)]

    @staticmethod
    def _regex_help_markup(message: str) -> str:
        return (
            f"{escape(message)}\n"
            "Regex101: Test (t) opens this pattern with samples."
        )

    @staticmethod
    def _regex101_url(regex: str, samples: list[str]) -> str:
        params = {
            "regex": regex,
            "testString": "\n".join(samples),
        }
        return "https://regex101.com/?" + urlencode(params)

    @staticmethod
    def _compile_pattern(pattern: Optional[str]):
        if not pattern:
            return None
        try:
            return re.compile(pattern)
        except re.error:
            return None
