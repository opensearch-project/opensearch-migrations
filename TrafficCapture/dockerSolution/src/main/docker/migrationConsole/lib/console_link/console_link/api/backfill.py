import logging
from fastapi import APIRouter, HTTPException
from typing import Dict

from console_link.models import backfill_base as backfill
from console_link.models.step_state import StepStateWithPause
from console_link.api.sessions import http_safe_find_session

logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

backfill_router = APIRouter(
    prefix="/backfill",
    tags=["backfill"],
)


def _get_backfill(session_name):
    session = http_safe_find_session(session_name)
    env = session.env

    if not env or not env.backfill:
        raise HTTPException(status_code=400,
                            detail=f"No backfill defined in the configuration: {env}")

    if not env or not env.target_cluster:
        raise HTTPException(status_code=400,
                            detail=f"Cannot run backfill without a target cluster defined in the configuration: {env}")
                            
    return env.backfill


@backfill_router.get("/status",
                     response_model=backfill.BackfillOverallStatus,
                     operation_id="backfillStatus")
def get_backfill_status(session_name: str):
    backfill_obj = _get_backfill(session_name)

    try:
        return backfill_obj.build_backfill_status()
    except backfill.DeepStatusNotYetAvailable:
        return backfill.BackfillOverallStatus(status=StepStateWithPause.PENDING,
                                              percentage_completed=0)
    except Exception as e:
        logger.error(f"Failed to get backfill status: {type(e).__name__} {str(e)}")
        raise HTTPException(status_code=500, detail=f"Failed to get backfill status: {type(e).__name__} {str(e)}")


@backfill_router.post("/start",
                      response_model=Dict[str, str],
                      operation_id="backfillStart")
def start_backfill(session_name: str) -> Dict:
    backfill_obj = _get_backfill(session_name)

    try:
        result = backfill_obj.start()
        if not result.success:
            if isinstance(result.value, Exception):
                raise result.value
            raise HTTPException(status_code=500, detail=f"Failed to start backfill: {result.value}")
        
        return {
            "status": "success",
            "message": f"Backfill process started successfully: {result.display()}"
        }
    except Exception as e:
        logger.error(f"Failed to start backfill: {type(e).__name__} {str(e)}")
        raise HTTPException(status_code=500, detail=f"Failed to start backfill: {type(e).__name__} {str(e)}")


@backfill_router.post("/pause",
                      response_model=Dict[str, str],
                      operation_id="backfillPause")
def pause_backfill(session_name: str) -> Dict:
    backfill_obj = _get_backfill(session_name)

    try:
        result = backfill_obj.pause()
        if not result.success:
            if isinstance(result.value, Exception):
                raise result.value
            raise HTTPException(status_code=500, detail=f"Failed to pause backfill: {result.value}")
        
        return {
            "status": "success",
            "message": f"Backfill process paused successfully: {result.display()}"
        }
    except Exception as e:
        logger.error(f"Failed to pause backfill: {type(e).__name__} {str(e)}")
        raise HTTPException(status_code=500, detail=f"Failed to pause backfill: {type(e).__name__} {str(e)}")


@backfill_router.post("/stop",
                      response_model=Dict[str, str],
                      operation_id="backfillStop")
def stop_backfill(session_name: str) -> Dict:
    backfill_obj = _get_backfill(session_name)

    try:
        result = backfill_obj.stop()
        if not result.success:
            if isinstance(result.value, Exception):
                raise result.value
            raise HTTPException(status_code=500, detail=f"Failed to stop backfill: {result.value}")
        
        return {
            "status": "success",
            "message": f"Backfill process stopped successfully: {result.display()}"
        }
    except Exception as e:
        logger.error(f"Failed to stop backfill: {type(e).__name__} {str(e)}")
        raise HTTPException(status_code=500, detail=f"Failed to stop backfill: {type(e).__name__} {str(e)}")
