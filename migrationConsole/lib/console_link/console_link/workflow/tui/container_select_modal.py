from typing import List
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Container, Vertical
from textual.screen import ModalScreen
from textual.widgets import Static, Button


class ContainerSelectModal(ModalScreen[str]):
    CSS = """
    ContainerSelectModal { align: center middle; background: $background 60%; }
    #dialog { width: 60; height: auto; border: thick $primary; background: $surface; padding: 1 2; }
    #title { text-align: center; margin-bottom: 1; }
    #buttons { height: auto; }
    Button { margin: 0 1 1 0; min-width: 20; }
    """
    BINDINGS = [
        Binding("up", "focus_previous", "Up", show=False),
        Binding("down", "focus_next", "Down", show=False),
        Binding("escape", "cancel", "Cancel", show=False)
    ]

    def __init__(self, containers: List[str], pod_name: str):
        super().__init__()
        self.containers = containers
        self._pod_name = pod_name

    def compose(self) -> ComposeResult:
        with Container(id="dialog"):
            yield Static(f"Select container to follow in pod: {self._pod_name}", id="title")
            with Vertical(id="buttons"):
                for container in self.containers:
                    yield Button(container, id=container)
                yield Button("Cancel", id="cancel", variant="error")

    def on_mount(self) -> None:
        if self.containers:
            self.query_one(f"#{self.containers[0]}", Button).focus()

    def action_focus_previous(self) -> None:
        self.focus_previous()

    def action_focus_next(self) -> None:
        self.focus_next()

    def action_cancel(self) -> None:
        self.dismiss(None)

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "cancel":
            self.dismiss(None)
        else:
            self.dismiss(event.button.id)
