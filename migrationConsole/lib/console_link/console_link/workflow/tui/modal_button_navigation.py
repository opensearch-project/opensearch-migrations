from textual.binding import Binding
from textual.widgets import Button


BUTTON_ARROW_BINDINGS = [
    Binding("left", "focus_button_previous", "Previous", show=False, priority=True),
    Binding("right", "focus_button_next", "Next", show=False, priority=True),
]


class ModalButton(Button):
    def key_left(self, event) -> None:
        event.stop()
        self._focus_modal_button(-1)

    def key_right(self, event) -> None:
        event.stop()
        self._focus_modal_button(1)

    def _focus_modal_button(self, delta: int) -> None:
        screen = self.screen
        if delta < 0 and hasattr(screen, "action_focus_button_previous"):
            screen.action_focus_button_previous()
        elif delta > 0 and hasattr(screen, "action_focus_button_next"):
            screen.action_focus_button_next()
        else:
            focus_modal_button(screen, delta)


class ButtonArrowNavigationMixin:
    BUTTON_NAV_SELECTOR = "Button"

    def check_action(self, action: str, parameters: tuple[object, ...]) -> bool | None:
        if action in {"focus_button_previous", "focus_button_next"}:
            focused = self.app.focused or self.focused
            return isinstance(focused, Button)
        return super().check_action(action, parameters)

    def action_focus_button_previous(self) -> None:
        focus_modal_button(self, -1, self.BUTTON_NAV_SELECTOR)

    def action_focus_button_next(self) -> None:
        focus_modal_button(self, 1, self.BUTTON_NAV_SELECTOR)


def focus_modal_button(screen, delta: int, selector: str = "Button") -> bool:
    """Move focus across visible enabled buttons in a modal."""
    focused = screen.app.focused or screen.focused
    if not isinstance(focused, Button):
        return False

    buttons = [
        button for button in screen.query(selector)
        if isinstance(button, Button) and not button.disabled and button.display
    ]
    if not buttons:
        return False

    focused = focused if focused in buttons else None
    if focused:
        index = buttons.index(focused)
        target = (index + delta) % len(buttons)
    else:
        target = 0 if delta > 0 else len(buttons) - 1
    screen.set_focus(buttons[target])
    return True
