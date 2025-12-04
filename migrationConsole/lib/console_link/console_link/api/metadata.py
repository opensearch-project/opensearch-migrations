import logging
from fastapi import APIRouter, HTTPException, Body
from datetime import datetime, timezone

from console_link.db import metadata_db
from console_link.models import metadata
from console_link.models.step_state import StepState
from console_link.api.sessions import http_safe_find_session

logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

metadata_router = APIRouter(
    prefix="/metadata",
    tags=["metadata"],
)


@metadata_router.post("/migrate",
                      response_model=metadata.MetadataStatus,
                      operation_id="metadataMigrate")
def migrate_metadata(session_name: str, request: metadata.MetadataMigrateRequest = Body(...)):
    """
    Migrate metadata for the given session.
    If dry_run=True, only evaluates the migration without making changes.
    """
    session = http_safe_find_session(session_name)
    env = session.env

    if not env or not env.metadata:
        raise HTTPException(
            status_code=400,
            detail="Metadata migration is not configured in the environment"
        )

    try:
        # Build extra arguments from request
        extra_args = metadata.extra_args_from_request(request)

        # Execute metadata migration or evaluation based on dry_run
        operation_type = "evaluation" if request.dryRun else "migration"
        logger.info(f"Starting metadata {operation_type} for session {session_name}")

        start_time = datetime.now(timezone.utc)
        result = env.metadata.migrate_or_evaluate("migrate" if not request.dryRun else "evaluate", extra_args)
        end_time = datetime.now(timezone.utc)

        parsed_data = metadata.parse_metadata_result(result)
        result = metadata.store_metadata_result(session_name,
                                                parsed_data,
                                                start_time,
                                                end_time,
                                                dry_run=request.dryRun)

        return metadata.build_status_from_entry(result)

    except Exception as e:
        logger.error(f"Unexpected error during metadata {operation_type} for session {session_name}: {e}")
        raise HTTPException(
            status_code=500,
            detail=f"Unexpected error during metadata {operation_type}: {str(e)}"
        )


@metadata_router.get("/status",
                     response_model=metadata.MetadataStatus,
                     operation_id="metadataStatus")
def get_metadata_status(session_name: str):
    """Get the status of the most recent metadata operation for the session."""
    http_safe_find_session(session_name)

    try:
        latest_result = metadata_db.get_latest(session_name)
        return metadata.build_status_from_entry(latest_result)
    except metadata_db.MetadataNotAvailable:
        return metadata.MetadataStatus(session_name=session_name, status=StepState.PENDING)
    except Exception as e:
        logger.error(f"Failed to get metadata status: {type(e).__name__} {str(e)}")
        raise HTTPException(status_code=500, detail=f"Failed to get metadata status: {type(e).__name__} {str(e)}")
