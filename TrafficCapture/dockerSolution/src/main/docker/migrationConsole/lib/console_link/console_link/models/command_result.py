from typing import Any, NamedTuple


class CommandResult(NamedTuple):
    success: bool
    value: Any

    def display(self) -> str:
        if self.value:
            return str(self.value)
        return ""
