import re
from datetime import datetime, UTC
from typing import Any, List
from tinydb import TinyDB, Query

from console_link.models.session import Session

db = TinyDB("sessions_db.json")
_sessions_table = db.table("sessions")


def all_sessions() -> List[Session]:
    sessions = _sessions_table.all()
    return [Session.model_validate(session) for session in sessions]


def find_session(session_name: str) -> Any:
    session_query = Query()
    return _sessions_table.get(session_query.name == session_name)


def create_session(session: Session):
    def is_url_safe(name: str) -> bool:
        return re.match(r'^[a-zA-Z0-9_\-]+$', name) is not None

    def unexpected_length(name: str) -> bool:
        return len(name) <= 0 or len(name) > 50

    if unexpected_length(session.name):
        raise SessionNameLengthInvalid()

    if not is_url_safe(session.name):
        raise SessionNameContainsInvalidCharacters()

    if (find_session(session.name)):
        raise SessionAlreadyExists()
    _sessions_table.insert(session.model_dump())


def update_session(session: Session):
    session_query = Query()
    raw_session = session.model_dump()
    # Always enforce update time
    raw_session.updated = datetime.now(UTC)

    _sessions_table.update(raw_session, session_query.name == session.name)


def delete_session(session_name: str):
    session_query = Query()
    if not _sessions_table.remove(session_query.name == session_name):
        raise SessionNotFound


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
