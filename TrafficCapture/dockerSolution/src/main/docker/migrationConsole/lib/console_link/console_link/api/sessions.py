from fastapi import HTTPException, Body, APIRouter
from pydantic import BaseModel, constr, field_validator, field_serializer
from datetime import datetime, UTC
from tinydb import TinyDB, Query
from typing import Dict, List, Optional
import re

session_router = APIRouter(
    prefix="/system",
    tags=["system"],
)

# Initialize TinyDB
db = TinyDB("sessions_db.json")
sessions_table = db.table("sessions")


# Helper for URL-safe session names
def is_url_safe(name: str) -> bool:
    return re.match(r'^[a-zA-Z0-9_\-]+$', name) is not None


class SessionBase(BaseModel):
    name: constr(min_length=1, max_length=50)
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


@session_router.get("/sessions", response_model=List[Session], operation_id="sessionsList")
def list_sessions():
    return sessions_table.all()


@session_router.get("/sessions/{session_name}", response_model=List[Session], operation_id="sessionGet")
def single_session(session_name: str):
    SessionQuery = Query()
    existing = sessions_table.get(SessionQuery.name == session_name)
    if not existing:
        raise HTTPException(status_code=404, detail="Session not found.")

    return existing


@session_router.post("/sessions", response_model=Session, operation_id="sessionCreate")
def create_session(session: SessionBase):
    if not is_url_safe(session.name):
        raise HTTPException(status_code=400, detail="Session name must be URL-safe (letters, numbers, '_', '-').")

    SessionQuery = Query()
    existing = sessions_table.get(SessionQuery.name == session.name)
    if existing:
        raise HTTPException(status_code=409, detail="Session already exists.")

    now = datetime.now(UTC)
    session = Session(
        name=session.name,
        created=now,
        updated=now,
    )
    sessions_table.insert(session.model_dump())
    return session


@session_router.put("/sessions/{session_name}", response_model=Session, operation_id="sessionUpdate")
def update_session(session_name: str, data: Dict = Body(...)):
    SessionQuery = Query()
    existing = sessions_table.get(SessionQuery.name == session_name)
    if not existing:
        raise HTTPException(status_code=404, detail="Session not found.")

    updated_session = Session(**existing)

    updated_session.data = data
    updated_session.updated = datetime.now(UTC)

    sessions_table.update(updated_session.model_dump(), SessionQuery.name == session_name)
    return updated_session


@session_router.delete("/sessions/{session_name}", operation_id="sessionDelete")
def delete_session(session_name: str):
    SessionQuery = Query()
    if sessions_table.remove(SessionQuery.name == session_name):
        return {"detail": f"Session '{session_name}' deleted."}
    else:
        raise HTTPException(status_code=404, detail="Session not found.")
