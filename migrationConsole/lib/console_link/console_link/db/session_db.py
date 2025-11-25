import re
from datetime import datetime, UTC
from threading import RLock
from tinydb import TinyDB, Query
from typing import Any, List, Optional

from console_link.db.utils import with_lock
from console_link.models.session import Session

_DB = TinyDB("sessions_db.json")
_TABLE = _DB.table("sessions")
_LOCK = RLock()
_QUERY = Query()


@with_lock(_LOCK)
def all_sessions() -> List[Session]:
    sessions = _TABLE.all()
    return [Session.model_validate(session) for session in sessions]


@with_lock(_LOCK)
def find_session(session_name: str) -> Optional[dict]:
    return _TABLE.get(_QUERY.name == session_name)


@with_lock(_LOCK)
def create_session(session: Session):
    def is_url_safe(name: str) -> bool:
        return re.match(r'^[a-zA-Z0-9_\-]+$', name) is not None

    def unexpected_length(name: str) -> bool:
        return len(name) <= 0 or len(name) > 50

    if unexpected_length(session.name):
        raise SessionNameLengthInvalid()

    if not is_url_safe(session.name):
        raise SessionNameContainsInvalidCharacters()

    # Existence check and insert protected by the same lock for atomicity
    if _TABLE.get(_QUERY.name == session.name):
        raise SessionAlreadyExists()

    _TABLE.insert(session.model_dump())


@with_lock(_LOCK)
def update_session(session: Session):
    session.updated = datetime.now(UTC)
    _TABLE.update(session.model_dump(), _QUERY.name == session.name)


@with_lock(_LOCK)
def delete_session(session_name: str):
    removed = _TABLE.remove(_QUERY.name == session_name)
    if not removed:
        raise SessionNotFound()


@with_lock(_LOCK)
def existence_check(session: Any) -> Session:
    if not session:
        raise SessionNotFound()
    try:
        return Session.model_validate(session)
    except Exception:
        raise SessionUnreadable()


class SessionNotFound(Exception):
    pass


class SessionUnreadable(Exception):
    pass


class SessionAlreadyExists(Exception):
    pass


class SessionNameLengthInvalid(Exception):
    pass


class SessionNameContainsInvalidCharacters(Exception):
    pass
