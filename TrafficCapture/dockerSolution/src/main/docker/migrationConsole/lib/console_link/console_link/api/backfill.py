import logging
from fastapi import APIRouter, HTTPException

from console_link.models import backfill_base as backfill
from console_link.api.sessions import http_safe_find_session

logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

backfill_router = APIRouter(
    prefix="/backfill",
    tags=["backfill"],
)


@backfill_router.get("/status",
                     response_model=backfill.BackfillOverallStatus,
                     operation_id="backfillStatus")
def get_metadata_status(session_name: str):
    session = http_safe_find_session(session_name)
    env = session.env

    if not env or not env.backfill:
        raise HTTPException(status_code=400,
                            detail=f"No backfill defined in the configuration: {env}")

    if not env or not env.target_cluster:
        raise HTTPException(status_code=400,
                            detail=f"Cannot run backfill without a target cluster defined in the configuration: {env}")

    try:
        return env.backfill.build_backfill_status()
    except Exception as e:
        logger.error(f"Failed to get backfill status: {type(e).__name__} {str(e)}")
        raise HTTPException(status_code=500, detail=f"Failed to get backfill status: {type(e).__name__} {str(e)}")
