import logging
from datetime import datetime, UTC
import os
from fastapi import HTTPException, Body, APIRouter
from pydantic import BaseModel, ValidationError
from typing import Dict, List

from console_link.db import session_db
from console_link.environment import Environment
from console_link.models.session import Session, SessionBase

logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

session_router = APIRouter(
    prefix="/sessions",
    tags=["sessions"],
)


class SessionDeleteResponse(BaseModel):
    detail: str


def http_safe_find_session(session_name: str) -> Session:
    try:
        return session_db.existence_check(session_db.find_session(session_name))
    except session_db.SessionNotFound:
        raise HTTPException(status_code=404, detail="Session not found.")
    except session_db.SessionUnreadable:
        raise HTTPException(status_code=500, detail=f"Unable to read session data for {session_name}.")


@session_router.get("/", response_model=List[Session], operation_id="sessionsList")
def list_sessions() -> List[Session]:
    return session_db.all_sessions()


@session_router.get("/{session_name}", response_model=Session, operation_id="sessionGet")
def single_session(session_name: str) -> Session | None:
    return http_safe_find_session(session_name)


@session_router.post("/", response_model=Session, status_code=201, operation_id="sessionCreate")
def create_session(session: SessionBase) -> Session:
    try:
        now = datetime.now(UTC)
        config_file_path = "/config/migration_services.yaml"
        env: Environment
        if os.path.exists(config_file_path):
            env = Environment(config_file=config_file_path)
        else:
            default_config: Dict = {}
            env = Environment(config=default_config)

        session = Session(
            name=session.name,
            created=now,
            updated=now,
            env=env
        )
        session_db.create_session(session)
    except session_db.SessionNameContainsInvalidCharacters:
        raise HTTPException(status_code=400, detail="Session name must be URL-safe (letters, numbers, '_', '-').")
    except session_db.SessionNameLengthInvalid:
        raise HTTPException(status_code=400, detail="Session name less than 50 characters in length.")
    except session_db.SessionAlreadyExists:
        raise HTTPException(status_code=409, detail="Session already exists.")
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Unable to create session: {e}")
    return session


@session_router.put("/{session_name}", response_model=Session, operation_id="sessionUpdate")
def update_session(session_name: str, data: Dict = Body(...)) -> Session:
    existing = http_safe_find_session(session_name)

    try:
        session_dict = existing.model_dump()
        
        for key, value in data.items():
            # Don't allow overriding creation date
            if key == 'created':
                continue

            # Don't allow updating the name
            if key == 'name':
                continue
                
            # Allow updating any other field
            if key:
                session_dict[key] = value
        logger.info(f"Creating session from {session_dict}")
        updated_session = Session.model_validate(session_dict)
        session_db.update_session(updated_session)
    except ValidationError as e:
        raise HTTPException(status_code=400, detail=f"Invalid session data: {e}")

    return updated_session


@session_router.delete("/{session_name}", response_model=SessionDeleteResponse, operation_id="sessionDelete")
def delete_session(session_name: str) -> SessionDeleteResponse:
    try:
        session_db.delete_session(session_name)
        return SessionDeleteResponse(detail=f"Session '{session_name}' deleted.")
    except session_db.SessionNotFound:
        raise HTTPException(status_code=404, detail="Session not found.")
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Unable to delete session: {e}")
