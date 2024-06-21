from typing import NamedTuple, Any


class CommandResult(NamedTuple):
    success: bool
    value: Any

    def display(self) -> str:
        if self.value:
            return str(self.value)
        return ""
