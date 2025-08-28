import logging
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Optional

from console_link.api.sessions import http_safe_find_session
from console_link.models.factories import get_snapshot
from console_link.models.snapshot import (
    FailedToCreateSnapshot, FailedToDeleteSnapshot, Snapshot, SnapshotConfig, SnapshotNotStarted, SnapshotStatus,
    SnapshotStatusUnavailable, get_latest_snapshot_status_raw, S3Snapshot, FileSystemSnapshot, SnapshotSourceType,
    S3SnapshotSource, FileSystemSnapshotSource, SnapshotIndexes,
)
from console_link.models.step_state import StepState

logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

snapshot_router = APIRouter(
    prefix="/snapshot",
    tags=["snapshot"],
)


class SnapshotCreateResponse(BaseModel):
    detail: str


class SnapshotDeleteResponse(BaseModel):
    detail: str


def _get_snapshot_from_session(session_name):
    session = http_safe_find_session(session_name)
    env = session.env

    if not env or not env.snapshot:
        raise HTTPException(status_code=400,
                            detail=f"No snapshot defined in the configuration: {env}")

    snapshot_obj = get_snapshot(env.snapshot.config, env.source_cluster)
    if not snapshot_obj.source_cluster:
        raise HTTPException(status_code=400,
                            detail=f"Source cluster was unable to be used to get snapshot indexes: {env}")
                            
    return snapshot_obj


# Snapshot status endpoint
@snapshot_router.get("/status", response_model=SnapshotStatus, operation_id="snapshotStatus")
def get_snapshot_status(session_name: str):
    snapshot_obj = _get_snapshot_from_session(session_name)
    try:
        # Get the snapshot status details
        latest_status = get_latest_snapshot_status_raw(snapshot_obj.source_cluster,  # type: ignore
                                                       snapshot_obj.snapshot_name,
                                                       snapshot_obj.snapshot_repo_name,
                                                       True)
        
        # Create the status object - index statuses are now handled within the from_snapshot_info method
        return SnapshotStatus.from_snapshot_info(latest_status.details)
    except SnapshotNotStarted:
        return SnapshotStatus(status=StepState.PENDING, percentage_completed=0, eta_ms=None)
    except SnapshotStatusUnavailable:
        raise HTTPException(status_code=500, detail="Snapshot status not available")
    except Exception as e:
        logger.error(f"Unable to lookup snapshot information: {type(e).__name__} {str(e)}")
        raise HTTPException(status_code=500, detail=f"Failed to get full snapshot status: {type(e).__name__} {str(e)}")


def convert_from_snapshot(snapshot: Snapshot) -> SnapshotConfig:
    logger.info(f"Checking snapshot object {snapshot}")
    if isinstance(snapshot, FileSystemSnapshot):
        source = FileSystemSnapshotSource(
            type=SnapshotSourceType.filesystem,
            path=snapshot.repo_path
        )
    elif isinstance(snapshot, S3Snapshot):
        source = S3SnapshotSource(
            type=SnapshotSourceType.s3,
            uri=snapshot.s3_repo_uri,
            region=snapshot.s3_region
        )
    else:
        raise ValueError(f"Unsupported snapshot type: {type(snapshot).__name__}")
    
    return SnapshotConfig(
        snapshot_name=snapshot.snapshot_name,
        repository_name=snapshot.snapshot_repo_name,
        index_allow=[],
        source=source
    )


@snapshot_router.get("/", response_model=SnapshotConfig, operation_id="snapshotConfig")
def get_snapshot_config(session_name: str):
    snapshot_obj = _get_snapshot_from_session(session_name)
    try:
        return convert_from_snapshot(snapshot_obj)
    except ValueError as e:
        logger.error(f"Unable to convert snapshot to config: {str(e)}")
        raise HTTPException(status_code=500, detail=f"Failed to convert snapshot to config: {str(e)}")
    except Exception as e:
        logger.error(f"Unexpected error converting snapshot: {type(e).__name__} {str(e)}")
        raise HTTPException(status_code=500, detail=f"Unexpected error: {type(e).__name__} {str(e)}")


@snapshot_router.get("/indexes", response_model=SnapshotIndexes, operation_id="snapshotIndexes")
def get_snapshot_indexes(session_name: str, index_pattern: Optional[str] = None):
    snapshot_obj = _get_snapshot_from_session(session_name)
    
    try:
        # Convert comma-separated string to list if provided
        index_patterns = None
        if index_pattern:
            index_patterns = [pattern.strip() for pattern in index_pattern.split(',')]
        
        return snapshot_obj.get_snapshot_indexes(index_patterns)
    except Exception as e:
        logger.error(f"Failed to get snapshot indexes: {type(e).__name__} {str(e)}")
        raise HTTPException(status_code=500,
                            detail=f"Failed to get snapshot indexes: {type(e).__name__} {str(e)}")


@snapshot_router.post("/create", response_model=SnapshotCreateResponse, operation_id="snapshotCreate")
def snapshot_create(session_name: str):
    snapshot_obj = _get_snapshot_from_session(session_name)
    try:
        return SnapshotCreateResponse(detail=snapshot_obj.create())
    except FailedToCreateSnapshot as ftcs:
        raise HTTPException(status_code=400, detail=f"Failed to create snapshot {str(ftcs)}")
    except Exception as e:
        logger.error(f"Failed to get snapshot indexes: {type(e).__name__} {str(e)}")
        raise HTTPException(status_code=500,
                            detail=f"Failed to get snapshot indexes: {type(e).__name__} {str(e)}")


@snapshot_router.post("/delete", response_model=SnapshotDeleteResponse, operation_id="snapshotDelete")
def snapshot_delete(session_name: str):
    snapshot_obj = _get_snapshot_from_session(session_name)
    try:
        return SnapshotDeleteResponse(detail=snapshot_obj.delete())
    except FailedToDeleteSnapshot as ftds:
        raise HTTPException(status_code=400, detail=f"Failed to delete snapshot {str(ftds)}")
    except Exception as e:
        logger.error(f"Failed to get snapshot indexes: {type(e).__name__} {str(e)}")
        raise HTTPException(status_code=500,
                            detail=f"Failed to get snapshot indexes: {type(e).__name__} {str(e)}")
