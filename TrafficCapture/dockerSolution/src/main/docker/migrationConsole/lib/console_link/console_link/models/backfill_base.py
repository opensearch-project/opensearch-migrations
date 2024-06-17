from typing import Dict

from console_link.models.schema_tools import contains_one_of

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


class Backfill:
    """
    Interface for backfilling data from a source to target cluster.
    """
    def __init__(self, config: Dict) -> None:
        v = Validator(SCHEMA)
        self.config = config
        if not v.validate({"backfill": self.config}):
            raise ValueError("Invalid config file for backfill", v.errors)

    def create(self):
        raise NotImplementedError

    def start(self):
        raise NotImplementedError

    def stop(self):
        raise NotImplementedError

    def get_status(self):
        raise NotImplementedError

    def scale(self, units: int):
        raise NotImplementedError

    def describe(self):
        return self.config
