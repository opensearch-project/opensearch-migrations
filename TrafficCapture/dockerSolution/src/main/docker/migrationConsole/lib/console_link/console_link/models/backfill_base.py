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
    A base migration manager.
    """
    def __init__(self, config: Dict) -> None:
        v = Validator(SCHEMA)
        if not v.validate({"backfill": config}):
            raise ValueError("Invalid config file for Backfill", v.errors)

    def create(self):
        raise NotImplementedError

    def start(self):
        raise NotImplementedError

    def stop(self):
        raise NotImplementedError

    def get_status(self):
        raise NotImplementedError
