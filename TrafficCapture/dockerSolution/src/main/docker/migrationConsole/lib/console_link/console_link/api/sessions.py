from enum import Enum
from fastapi import HTTPException, Body, APIRouter
from pydantic import BaseModel, ValidationError, field_validator, field_serializer
from datetime import datetime, UTC
from console_link.environment import Environment
from tinydb import TinyDB, Query
from typing import Any, Dict, List
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
    env: Environment | None

    @field_serializer('created', 'updated')
    def serialize_datetime(self, dt: datetime) -> str:
        return dt.isoformat()

    @field_validator('created', 'updated', mode='before')
    @classmethod
    def parse_datetime(cls, v):
        if isinstance(v, str):
            return datetime.fromisoformat(v)
        return v

    @field_serializer('env')
    def serialize_environment(self, env: Environment) -> Dict:
        return env.config

    @field_validator('env', mode='before')
    @classmethod
    def parse_environment(cls, v):
        if isinstance(v, Dict):
            return Environment(config=v)
        return v


class StepState(str, Enum):
    PENDING = "Pending"
    RUNNING = "Running"
    COMPLETED = "Completed"
    FAILED = "Failed"


class SessionDeleteResponse(BaseModel):
    detail: str


class SessionExistence(Enum):
    MUST_EXIST = "must_exist"
    MAY_NOT_EXIST = "may_not_exist"


def find_session(session_name: str) -> Any:
    session_query = Query()
    return sessions_table.get(session_query.name == session_name)


def existance_check(session: Any) -> Session:
    if not session:
        raise HTTPException(status_code=404, detail="Session not found.")
    try:
        return Session.model_validate(session)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Session data was corrupt,\nfailure: ${e}\nraw data: ${session}")


@session_router.get("/", response_model=List[Session], operation_id="sessionsList")
def list_sessions() -> List[Session]:
    sessions = sessions_table.all()
    return [Session.model_validate(session) for session in sessions]


@session_router.get("/{session_name}", response_model=Session, operation_id="sessionGet")
def single_session(session_name: str) -> Session | None:
    return existance_check(find_session(session_name))


@session_router.post("/", response_model=Session, status_code=201, operation_id="sessionCreate")
def create_session(session: SessionBase) -> Session:
    if not is_url_safe(session.name):
        raise HTTPException(status_code=400, detail="Session name must be URL-safe (letters, numbers, '_', '-').")

    if unexpected_length(session.name):
        raise HTTPException(status_code=400, detail="Session name less than 50 characters in length.")

    existing = find_session(session.name)
    if existing:
        raise HTTPException(status_code=409, detail="Session already exists.")

    try:
        now = datetime.now(UTC)
        session = Session(
            name=session.name,
            created=now,
            updated=now,
            env=Environment(config_file="/config/migration_services.yaml")
        )
        sessions_table.insert(session.model_dump())
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Unable to create session: {e}")
    return session


@session_router.put("/{session_name}", response_model=Session, operation_id="sessionUpdate")
def update_session(session_name: str, data: Dict = Body(...)) -> Session:
    session_query = Query()
    existing = existance_check(find_session(session_name))

    try:
        updated_session = Session.model_validate(existing)
        session_dict = updated_session.model_dump()
        
        for key, value in data.items():
            # Don't allow overriding creation date
            if key == 'created':
                continue
                
            # Allow updating any other field
            if hasattr(updated_session, key):
                session_dict[key] = value
        
        updated_session = Session.model_validate(session_dict)
        # Always enforce update time
        updated_session.updated = datetime.now(UTC)
    except ValidationError as e:
        raise HTTPException(status_code=400, detail=f"Invalid session data: {e}")

    sessions_table.update(updated_session.model_dump(), session_query.name == session_name)
    return updated_session


@session_router.delete("/{session_name}", response_model=SessionDeleteResponse, operation_id="sessionDelete")
def delete_session(session_name: str) -> SessionDeleteResponse:
    # Make sure the session exists before we attempt to delete it
    existance_check(find_session(session_name))

    session_query = Query()
    if sessions_table.remove(session_query.name == session_name):
        return SessionDeleteResponse(detail=f"Session '{session_name}' deleted.")
    else:
        raise HTTPException(status_code=404, detail="Session not found.")
