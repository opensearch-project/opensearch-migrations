from typing import Any, Optional

import yaml
from rich.markup import escape
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Container, Horizontal
from textual.screen import ModalScreen
from textual.widgets import Button, Static, TextArea

from .doc_markup import documentation_markup
from .modal_button_navigation import BUTTON_ARROW_BINDINGS, ButtonArrowNavigationMixin, ModalButton
from .modal_results import CLEAR_VALUE


class StructuredValueModal(ButtonArrowNavigationMixin, ModalScreen[Optional[Any]]):
    CSS = """
    StructuredValueModal { align: center middle; background: $background 60%; }
    #dialog { width: 84; height: auto; border: thick $primary; background: $surface; padding: 0 1; }
    #prompt { margin-bottom: 0; }
    #documentation { color: gray; margin-bottom: 1; }
    #value { width: 100%; height: 14; }
    #validation { color: $error; margin: 0 0 1 0; min-height: 1; }
    #buttons { align: center middle; height: 1; }
    Button { margin: 0 1 0 0; min-width: 5; height: 1; min-height: 1; border: none; padding: 0 1; }
    """
    BUTTON_NAV_SELECTOR = "#buttons Button"
    BINDINGS = [
        *BUTTON_ARROW_BINDINGS,
        Binding("ctrl+s", "submit", "Save", show=False, priority=True),
        Binding("escape", "cancel", "Cancel", show=False),
    ]

    def __init__(
        self,
        prompt: str,
        initial_value: str = "",
        documentation: str = "",
        expected_kind: str = "object",
        clear_allowed: bool = False,
        clear_label: str = "Clear",
    ):
        super().__init__()
        self.prompt = prompt
        self.initial_value = initial_value
        self.documentation = documentation
        self.expected_kind = expected_kind
        self.clear_allowed = clear_allowed
        self.clear_label = clear_label

    def compose(self) -> ComposeResult:
        with Container(id="dialog"):
            yield Static(escape(self.prompt), id="prompt")
            yield Static(documentation_markup(self.documentation), id="documentation")
            yield TextArea(
                self.initial_value,
                id="value",
                show_line_numbers=False,
                tab_behavior="focus",
            )
            yield Static("", id="validation")
            with Horizontal(id="buttons"):
                yield ModalButton("Save (^S)", id="save", variant="success")
                if self.clear_allowed:
                    yield ModalButton(self.clear_label, id="clear")
                yield ModalButton("Cancel (Esc)", id="cancel", variant="error")

    def on_mount(self) -> None:
        self.query_one("#documentation", Static).display = bool(self.documentation)
        self.query_one("#value", TextArea).focus()

    def action_cancel(self) -> None:
        self.dismiss(None)

    def action_submit(self) -> None:
        text = self.query_one("#value", TextArea).text
        try:
            value = self._parse_value(text)
        except ValueError as e:
            self.query_one("#validation", Static).update(escape(str(e)))
            return
        self.dismiss(value)

    def action_clear(self) -> None:
        if self.clear_allowed:
            self.dismiss(CLEAR_VALUE)

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "save":
            self.action_submit()
        elif event.button.id == "clear":
            self.action_clear()
        else:
            self.dismiss(None)

    def _parse_value(self, text: str) -> Any:
        try:
            value = yaml.safe_load(text) if text.strip() else None
        except yaml.YAMLError as e:
            raise ValueError(f"YAML parse error: {e}") from e

        if self.expected_kind == "array":
            value = [] if value is None else value
            if not isinstance(value, list):
                raise ValueError("Enter a YAML array/list value.")
            return value

        value = {} if value is None else value
        if not isinstance(value, dict):
            raise ValueError("Enter a YAML object/map value.")
        return value
