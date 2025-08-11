from fastapi import APIRouter, HTTPException
from console_link.models.factories import get_snapshot
from console_link.models.snapshot import (
    SnapshotNotStarted, SnapshotStatusUnavailable, get_latest_snapshot_status_raw,
    SnapshotStatus
)
from console_link.api.sessions import existence_check, find_session
import logging

logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)


snapshot_router = APIRouter(
    prefix="/snapshot",
    tags=["snapshot"],
)


# Snapshot status endpoint
@snapshot_router.get("/status", response_model=SnapshotStatus, operation_id="snapshotStatus")
def get_snapshot_status(session_name: str):
    session = existence_check(find_session(session_name))
    env = session.env

    if not env.snapshot:
        raise HTTPException(status_code=400,
                            detail=f"No snapshot defined in the configuration: {env}")
    if not env.source_cluster:
        raise HTTPException(status_code=400,
                            detail=f"No source cluster defined in the configuration: {env}")

    snapshot = get_snapshot(env.snapshot.config, env.source_cluster)
    try:
        latest_status = get_latest_snapshot_status_raw(snapshot.source_cluster,
                                                       snapshot.snapshot_name,
                                                       snapshot.snapshot_repo_name)
        return SnapshotStatus.from_snapshot_info(latest_status.details)
    except SnapshotNotStarted:
        return SnapshotStatus(status="PENDING", percentage_completed=0, eta_ms=None)
    except SnapshotStatusUnavailable:
        raise HTTPException(status_code=500, detail="Snapshot status not available")
    except Exception as e:
        logger.error(f"Unable to lookup snapshot information: {type(e).__name__} {str(e)}")
        raise HTTPException(status_code=500, detail=f"Failed to get full snapshot status: {type(e).__name__} {str(e)}")
