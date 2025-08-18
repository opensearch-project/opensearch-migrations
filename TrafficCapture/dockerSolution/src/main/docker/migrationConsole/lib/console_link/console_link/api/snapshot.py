import logging
from fastapi import APIRouter, HTTPException

from console_link.api.sessions import http_safe_find_session
from console_link.models.factories import get_snapshot
from console_link.models.snapshot import (
    SnapshotNotStarted, SnapshotStatus, SnapshotStatusUnavailable, get_latest_snapshot_status_raw
)
from console_link.models.step_state import StepState

logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

snapshot_router = APIRouter(
    prefix="/snapshot",
    tags=["snapshot"],
)


# Snapshot status endpoint
@snapshot_router.get("/status", response_model=SnapshotStatus, operation_id="snapshotStatus")
def get_snapshot_status(session_name: str):
    session = http_safe_find_session(session_name)
    env = session.env

    if not env or not env.snapshot:
        raise HTTPException(status_code=400,
                            detail=f"No snapshot defined in the configuration: {env}")
    if not env.source_cluster:
        raise HTTPException(status_code=400,
                            detail=f"No source cluster defined in the configuration: {env}")

    snapshot_obj = get_snapshot(env.snapshot.config, env.source_cluster)
    if not snapshot_obj.source_cluster:
        raise HTTPException(status_code=400,
                            detail=f"Source cluster was unable to be use to get snapshot status: {env}")

    try:
        latest_status = get_latest_snapshot_status_raw(snapshot_obj.source_cluster,
                                                       snapshot_obj.snapshot_name,
                                                       snapshot_obj.snapshot_repo_name,
                                                       True)
        return SnapshotStatus.from_snapshot_info(latest_status.details)
    except SnapshotNotStarted:
        return SnapshotStatus(status=StepState.PENDING, percentage_completed=0, eta_ms=None)
    except SnapshotStatusUnavailable:
        raise HTTPException(status_code=500, detail="Snapshot status not available")
    except Exception as e:
        logger.error(f"Unable to lookup snapshot information: {type(e).__name__} {str(e)}")
        raise HTTPException(status_code=500, detail=f"Failed to get full snapshot status: {type(e).__name__} {str(e)}")
