from subprocess import CompletedProcess
from typing import Generic, TypeVar
from dataclasses import dataclass

T = TypeVar('T')


@dataclass
class CommandResult(Generic[T]):
    success: bool
    value: T | Exception | None
    output: CompletedProcess | None = None

    def display(self) -> str:
        if self.value:
            return str(self.value)
        return ""
