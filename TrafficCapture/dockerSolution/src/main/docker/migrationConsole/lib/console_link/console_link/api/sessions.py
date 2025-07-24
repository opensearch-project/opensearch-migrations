from enum import Enum
from fastapi import HTTPException, Body, APIRouter
from pydantic import BaseModel, ValidationError, field_validator, field_serializer
from datetime import datetime, UTC
from tinydb import TinyDB, Query
from typing import Dict, List
import re

session_router = APIRouter(
    prefix="/sessions",
    tags=["sessions"],
)

# Initialize TinyDB
db = TinyDB("sessions_db.json")
sessions_table = db.table("sessions")


# Helper for URL-safe session names
def is_url_safe(name: str) -> bool:
    return re.match(r'^[a-zA-Z0-9_\-]+$', name) is not None


def unexpected_length(name: str) -> bool:
    return len(name) <= 0 or len(name) > 50


class SessionBase(BaseModel):
    name: str
    model_config = {
        "arbitrary_types_allowed": True,
    }


class Session(SessionBase):
    created: datetime
    updated: datetime

    @field_serializer('created', 'updated')
    def serialize_datetime(self, dt: datetime) -> str:
        return dt.isoformat()

    @field_validator('created', 'updated', mode='before')
    @classmethod
    def parse_datetime(cls, v):
        if isinstance(v, str):
            return datetime.fromisoformat(v)
        return v


class SessionExistence(Enum):
    MUST_EXIST = "must_exist"
    MAY_NOT_EXIST = "may_not_exist"


def find_session(session_name: str, existence: SessionExistence):
    session_query = Query()
    session = sessions_table.get(session_query.name == session_name)
    if existence == SessionExistence.MUST_EXIST and not session:
        raise HTTPException(status_code=404, detail="Session not found.")

    return session


@session_router.get("/", response_model=List[Session], operation_id="sessionsList")
def list_sessions():
    return sessions_table.all()


@session_router.get("/{session_name}", response_model=List[Session], operation_id="sessionGet")
def single_session(session_name: str):
    return find_session(session_name, SessionExistence.MUST_EXIST)


@session_router.post("/", response_model=Session, operation_id="sessionCreate")
def create_session(session: SessionBase):
    if not is_url_safe(session.name):
        raise HTTPException(status_code=400, detail="Session name must be URL-safe (letters, numbers, '_', '-').")

    if unexpected_length(session.name):
        raise HTTPException(status_code=400, detail="Session name less than 50 characters in length.")

    existing = find_session(session.name, SessionExistence.MAY_NOT_EXIST)
    if existing:
        raise HTTPException(status_code=409, detail="Session already exists.")

    try:
        now = datetime.now(UTC)
        session = Session(
            name=session.name,
            created=now,
            updated=now,
        )
        sessions_table.insert(session.model_dump())
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Unable to create session: {e}")
    return session


@session_router.put("/{session_name}", response_model=Session, operation_id="sessionUpdate")
def update_session(session_name: str, data: Dict = Body(...)):
    sessionQuery = Query()
    existing = find_session(session_name, SessionExistence.MUST_EXIST)

    try:
        updated_session = Session.model_validate(existing)
    except ValidationError as e:
        raise HTTPException(status_code=500, detail=f"Invalid session data: {e}")

    updated_session.updated = datetime.now(UTC)

    sessions_table.update(updated_session.model_dump(), sessionQuery.name == session_name)
    return updated_session


@session_router.delete("/{session_name}", operation_id="sessionDelete")
def delete_session(session_name: str):
    # Make sure the session exists before we attempt to delete it
    find_session(session_name, SessionExistence.MUST_EXIST)

    sessionQuery = Query()
    if sessions_table.remove(sessionQuery.name == session_name):
        return {"detail": f"Session '{session_name}' deleted."}
    else:
        raise HTTPException(status_code=404, detail="Session not found.")
