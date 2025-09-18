from datetime import datetime
from pydantic import BaseModel, field_validator, field_serializer
from threading import RLock
from tinydb import Query, TinyDB
from typing import Any, Dict

from console_link.db.utils import with_lock

_DB = TinyDB("metadata_results_db.json")
# TODO: https://opensearch.atlassian.net/browse/MIGRATIONS-2666
_TABLE = _DB.table("metadata_results")
_LOCK = RLock()
_QUERY = Query()


class MetadataEntry(BaseModel):
    """Database storage model for metadata migration results."""
    session_name: str
    timestamp: datetime
    started: datetime
    finished: datetime
    dry_run: bool
    detailed_results: Dict[str, Any]

    @field_serializer('started', 'finished', 'timestamp')
    def serialize_datetime(self, dt: datetime) -> str:
        return dt.isoformat()

    @field_validator('started', 'finished', 'timestamp', mode='before')
    @classmethod
    def parse_datetime(cls, v):
        if isinstance(v, str):
            return datetime.fromisoformat(v)
        return v


@with_lock(_LOCK)
def get_latest(session_name: str) -> MetadataEntry:
    results = _TABLE.search(_QUERY.session_name == session_name)
    
    if not results or len(results) == 0:
        raise MetadataNotAvailable

    latest_item = sorted(results, key=lambda x: x["timestamp"], reverse=True)[0]
    return MetadataEntry.model_validate(latest_item)


@with_lock(_LOCK)
def create_entry(entry: MetadataEntry):
    _TABLE.insert(entry.model_dump())


class MetadataNotAvailable(Exception):
    pass
