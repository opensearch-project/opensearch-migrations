from typing import NamedTuple, Any


class CommandResult(NamedTuple):
    success: bool
    value: Any
