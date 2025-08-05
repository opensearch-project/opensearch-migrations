from datetime import datetime
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, field_serializer
from console_link.models.factories import get_snapshot
from console_link.models.snapshot import SnapshotNotStarted, SnapshotStatusUnavaliable, get_latest_snapshot_status_raw
from console_link.api.sessions import StepState, existance_check, find_session
import logging

logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)


snapshot_router = APIRouter(
    prefix="/snapshot",
    tags=["snapshot"],
)


class SnapshotStatus(BaseModel):
    status: StepState
    percentage_completed: float
    eta_ms: int | None
    started: datetime | None = None
    finished: datetime | None = None

    class Config:
        orm_mode = True

    @field_serializer("started", "finished")
    def serialize_completed(self, dt: datetime | None) -> str | None:
        return dt.isoformat() if dt else None

    @classmethod
    def from_snapshot_info(cls, snapshot_info: dict) -> "SnapshotStatus":
        # Calculate the percentage of completion
        total_bytes = snapshot_info.get("stats", {}).get("total", {}).get("size_in_bytes", 0)
        processed_bytes = snapshot_info.get("stats", {}).get("processed", {}).get("size_in_bytes", 0)
        incremental_bytes = snapshot_info.get("stats", {}).get("incremental", {}).get("size_in_bytes", 0)
        percentage = ((processed_bytes + incremental_bytes) / total_bytes) * 100 if total_bytes > 0 else 0

        # Collect timing information
        started_ms = snapshot_info.get("stats", {}).get("start_time_in_millis", 0)
        finished_ms = started_ms + snapshot_info.get("stats", {}).get("time_in_millis", 0)

        # Calculate ETA for completion
        eta = None
        if percentage > 0:
            duration_in_millis = snapshot_info.get("stats", {}).get("time_in_millis", 0)
            remaining_duration_in_millis = (duration_in_millis / percentage) * (100 - percentage)
            eta = remaining_duration_in_millis

        state = convert_snapshot_state_to_step_state(snapshot_info.get("state", "Unknown"))

        return cls(
            status=state,
            percentage_completed=percentage,
            eta_ms=eta,
            started=started_ms,
            finished=finished_ms,
        )


def convert_snapshot_state_to_step_state(snapshot_state: str) -> StepState:
    state_mapping = {
        "FAILED": StepState.FAILED,
        "IN_PROGRESS": StepState.RUNNING,
        "PARTIAL": StepState.FAILED,
        "SUCCESS": StepState.COMPLETED,
    }

    return state_mapping.get(snapshot_state, StepState.FAILED)


# Snapshot status endpoint
@snapshot_router.get("/status", response_model=SnapshotStatus, operation_id="snapshotStatus")
def get_snapshot_status(session_name: str):
    if session_name == 'fake':
        return SnapshotStatus.from_snapshot_info({
            "snapshot": "rfs-snapshot",
            "repository": "migration_assistant_repo",
            "uuid": "7JFrWqraSJ20anKfiSIj1Q",
            "state": "SUCCESS",
            "include_global_state": True,
            "shards_stats": {
                "initializing": 0,
                "started": 0,
                "finalizing": 0,
                "done": 304,
                "failed": 0,
                "total": 304
            },
            "stats": {
                "incremental": {
                    "file_count": 67041,
                    "size_in_bytes": 67108864
                },
                "total": {
                    "file_count": 67041,
                    "size_in_bytes": 67108864
                },
                "start_time_in_millis": 1719343996753,
                "time_in_millis": 79426
            }
        })

    session = existance_check(find_session(session_name))
    env = session.env
    if not env.source_cluster:
        raise HTTPException(status_code=400,
                            detail=f"No source cluster defined in the configuration: {env}")

    snapshot = get_snapshot(env.snapshot, env.source_cluster)
    try:
        lastest_status = get_latest_snapshot_status_raw(snapshot.source_cluster,
                                                        snapshot.snapshot_name,
                                                        snapshot.snapshot_repo_name,
                                                        deep=True)
        return SnapshotStatus.from_snapshot_info(lastest_status.details)
    except SnapshotNotStarted:
        return SnapshotStatus(status=StepState.PENDING, percentage_completed=0, eta_ms=None)
    except SnapshotStatusUnavaliable:
        return HTTPException(status_code=500, detail="Snapshot status not available")
    except Exception as e:
        logger.error(f"Unable to lookup snapshot information: {type(e).__name__} {str(e)}")
        return HTTPException(status_code=500, detail=f"Failed to get full snapshot status: {type(e).__name__} {str(e)}")
