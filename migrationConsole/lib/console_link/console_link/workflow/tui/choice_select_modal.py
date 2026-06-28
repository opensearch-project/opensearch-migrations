from typing import Any, Dict, List

from rich.markup import escape
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Container, Horizontal, Vertical
from textual.screen import ModalScreen
from textual.widgets import Button, Static

from .modal_button_navigation import (
    BUTTON_ARROW_BINDINGS,
    ButtonArrowNavigationMixin,
    ModalButton,
    MouseOnlyModalButton,
)


class ChoiceSelectModal(ButtonArrowNavigationMixin, ModalScreen[Any]):
    CSS = """
    ChoiceSelectModal { align: center middle; background: $background 60%; }
    #dialog { width: 72; height: auto; border: thick $primary; background: $surface; padding: 0 1; }
    #title { text-align: center; margin-bottom: 0; }
    #documentation { color: gray; margin-bottom: 1; }
    #choice-doc { color: gray; margin-top: 0; min-height: 1; }
    #buttons { height: auto; }
    #actions { align: center middle; height: 1; margin-top: 1; }
    Button { margin: 0 0 0 0; min-width: 24; height: 1; min-height: 1; border: none; padding: 0 1; }
    #buttons Button { width: 100%; text-align: left; content-align: left middle; }
    #actions Button { margin: 0 1 0 0; min-width: 5; width: auto; text-align: center; content-align: center middle; }
    """
    BINDINGS = [
        *BUTTON_ARROW_BINDINGS,
        Binding("enter", "submit", "Select", show=False, priority=True),
        Binding("up", "focus_previous", "Up", show=False),
        Binding("down", "focus_next", "Down", show=False),
        Binding("escape", "cancel", "Cancel", show=False),
    ]

    def __init__(
        self,
        title: str,
        choices: List[Dict[str, Any]],
        current_value: Any = None,
        documentation: str = "",
    ):
        super().__init__()
        self.title_text = title
        self.choices = choices
        self.current_value = current_value
        self.documentation = documentation

    def compose(self) -> ComposeResult:
        with Container(id="dialog"):
            yield Static(escape(self.title_text), id="title")
            yield Static(escape(self.documentation), id="documentation")
            with Vertical(id="buttons"):
                for index, choice in enumerate(self.choices):
                    label = str(choice.get("label") or choice.get("value") or "")
                    if choice.get("value") == self.current_value:
                        label = f"{label} (current)"
                    yield ModalButton(label, id=f"choice-{index}")
            yield Static("", id="choice-doc")
            with Horizontal(id="actions"):
                yield MouseOnlyModalButton("OK (<Enter>)", id="ok", variant="primary")
                yield MouseOnlyModalButton("Cancel (Esc)", id="cancel", variant="error")

    def on_mount(self) -> None:
        self.query_one("#documentation", Static).display = bool(self.documentation)
        choice_index = next(
            (index for index, choice in enumerate(self.choices) if choice.get("value") == self.current_value),
            0,
        )
        if self.choices:
            self.query_one(f"#choice-{choice_index}", Button).focus()
            self._update_choice_doc()

    def action_focus_previous(self) -> None:
        self.focus_previous()
        self._update_choice_doc()

    def action_focus_next(self) -> None:
        self.focus_next()
        self._update_choice_doc()

    def action_cancel(self) -> None:
        self.dismiss(None)

    def action_submit(self) -> None:
        focused = self.focused
        if isinstance(focused, Button):
            self._dismiss_button(focused)

    def on_key(self, event) -> None:
        if event.key == "enter":
            event.stop()
            self.action_submit()

    def action_focus_button_previous(self) -> None:
        super().action_focus_button_previous()
        self._update_choice_doc()

    def action_focus_button_next(self) -> None:
        super().action_focus_button_next()
        self._update_choice_doc()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "ok":
            self.action_submit()
        else:
            self._dismiss_button(event.button)

    def _dismiss_button(self, button: Button) -> None:
        if button.id == "cancel":
            self.dismiss(None)
            return
        index = int(str(button.id).removeprefix("choice-"))
        self.dismiss(self.choices[index].get("value"))

    def _update_choice_doc(self) -> None:
        focused = self.app.focused or self.focused
        description = ""
        if isinstance(focused, Button) and focused.id and focused.id.startswith("choice-"):
            index = int(str(focused.id).removeprefix("choice-"))
            description = str(self.choices[index].get("description") or "")
        self.query_one("#choice-doc", Static).update(escape(description))
