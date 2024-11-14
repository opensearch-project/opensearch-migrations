from enum import Enum
from typing import Dict, Tuple
from abc import ABC, abstractmethod

from console_link.models.schema_tools import contains_one_of
from console_link.models.command_result import CommandResult

from cerberus import Validator

SCHEMA = {
    "backfill": {
        "type": "dict",
        "schema": {
            "opensearch_ingestion": {"type": "dict"},
            "reindex_from_snapshot": {"type": "dict"},
        },
        "check_with": contains_one_of({"opensearch_ingestion", "reindex_from_snapshot"})
    }
}


BackfillStatus = Enum("BackfillStatus", ["NOT_STARTED", "STARTING", "RUNNING", "STOPPED", "FAILED"])


class Backfill(ABC):
    """
    Interface for backfilling data from a source to target cluster.
    """
    def __init__(self, config: Dict) -> None:
        v = Validator(SCHEMA)
        self.config = config
        if not v.validate({"backfill": self.config}):
            raise ValueError("Invalid config file for backfill", v.errors)

    @abstractmethod
    def create(self, *args, **kwargs) -> CommandResult[str]:
        """If necessary, create/deploy the backfill mechanism iteslf. After create succesfully completes,
        the backfill should be ready to start."""
        pass

    @abstractmethod
    def start(self, *args, **kwargs) -> CommandResult[str]:
        """Begin running the backfill. After running start, the user should be able to assume that--barring exceptions
        or failures--their data will begin moving to the target cluster."""
        pass

    @abstractmethod
    def pause(self, *args, **kwargs) -> CommandResult[str]:
        """Pause the backfill. This backfill should be resumable afterwards by invoking `start`."""
        pass

    @abstractmethod
    def stop(self, *args, **kwargs) -> CommandResult[str]:
        """Stop the backfill. This does not make guarantees about resumeability."""
        pass

    @abstractmethod
    def get_status(self, *args, **kwargs) -> CommandResult[Tuple[BackfillStatus, str]]:
        """Return a status"""
        pass

    @abstractmethod
    def scale(self, units: int, *args, **kwargs) -> CommandResult[str]:
        pass

    @abstractmethod
    def archive(self, *args, **kwargs) -> CommandResult[str]:
        """Archive the backfill operation.  Should return the information required to resume the backfill operations.
        Should fail if there are currently running operations."""
        pass

    def describe(self) -> Dict:
        return self.config
