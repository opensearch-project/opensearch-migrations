from datetime import datetime
from typing import Optional
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
    eta_ms: float | None
    started: datetime | None = None
    finished: datetime | None = None

    class Config:
        orm_mode = True

    @field_serializer("started", "finished")
    def serialize_completed(self, dt: datetime | None) -> str | None:
        return dt.isoformat() if dt else None

    @classmethod
    def from_snapshot_info(cls, snapshot_info: dict) -> "SnapshotStatus":
        # 1) Extract progress metrics
        total_units = processed_units = 0
        if stats := snapshot_info.get("stats"):
            # OpenSearch: byte-level stats
            total_units = stats.get("total", {}).get("size_in_bytes", 0)
            processed_units = (
                (stats.get("processed", {}).get("size_in_bytes", 0) +
                 stats.get("incremental", {}).get("size_in_bytes", 0))
            )
            start_ms = stats.get("start_time_in_millis", 0)
            elapsed_ms = stats.get("time_in_millis", 0)
        elif shards_stats := snapshot_info.get("shards_stats"):
            # ES ≥7.8 / OS: shard-level stats
            total_units = shards_stats.get("total", 0)
            processed_units = shards_stats.get("done", 0)
            # these sometimes live at the top level
            start_ms = snapshot_info.get("start_time_in_millis", 0)
            elapsed_ms = snapshot_info.get("time_in_millis", 0)
        else:
            # ES <7.8: simple shards summary
            shards = snapshot_info.get("shards", {})
            total_units = shards.get("total", 0)
            processed_units = shards.get("successful", 0)
            start_ms = snapshot_info.get("start_time_in_millis", 0)
            elapsed_ms = snapshot_info.get("time_in_millis", 0)

        # 2) Compute percentage complete
        percentage = (processed_units / total_units * 100) if total_units else 0.0

        # 3) Compute ETA in ms (only once we’ve made some progress)
        eta_ms: Optional[float] = None
        if 0 < percentage < 100:
            eta_ms = (elapsed_ms / percentage) * (100 - percentage)

        # 4) Normalize finished time
        finished_ms = start_ms + elapsed_ms

        # 5) Map snapshot state to your StepState enum
        raw_state = snapshot_info.get("state", "")
        state = convert_snapshot_state_to_step_state(raw_state)

        # 6) If it’s already done, clamp to 100%
        if state == StepState.COMPLETED:
            percentage = 100.0
            eta_ms = 0.0

        return cls(
            status=state,
            percentage_completed=percentage,
            eta_ms=eta_ms,
            started=start_ms,
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
    session = existance_check(find_session(session_name))
    env = session.env

    if not env.snapshot:
        raise HTTPException(status_code=400,
                            detail=f"No snapshot defined in the configuration: {env}")
    if not env.source_cluster:
        raise HTTPException(status_code=400,
                            detail=f"No source cluster defined in the configuration: {env}")

    snapshot = get_snapshot(env.snapshot.config, env.source_cluster)
    try:
        lastest_status = get_latest_snapshot_status_raw(snapshot.source_cluster,
                                                        snapshot.snapshot_name,
                                                        snapshot.snapshot_repo_name,
                                                        deep=True)
        return SnapshotStatus.from_snapshot_info(lastest_status.details)
    except SnapshotNotStarted:
        return SnapshotStatus(status=StepState.PENDING, percentage_completed=0, eta_ms=None)
    except SnapshotStatusUnavaliable:
        raise HTTPException(status_code=500, detail="Snapshot status not available")
    except Exception as e:
        logger.error(f"Unable to lookup snapshot information: {type(e).__name__} {str(e)}")
        raise HTTPException(status_code=500, detail=f"Failed to get full snapshot status: {type(e).__name__} {str(e)}")
