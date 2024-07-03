from typing import NamedTuple, TypeVar, Generic

T = TypeVar('T')


class CommandResult(NamedTuple, Generic[T]):
    success: bool
    value: T | Exception | None

    def display(self) -> str:
        if self.value:
            return str(self.value)
        return ""
