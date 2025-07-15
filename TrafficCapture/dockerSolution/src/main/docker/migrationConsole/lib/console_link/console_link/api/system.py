from enum import Enum
from fastapi import APIRouter, HTTPException, Depends
from pydantic import BaseModel
from typing import Callable, Dict
import os

system_router = APIRouter(
    prefix="/system",
    tags=["system"],
)


class HealthApiRequest(BaseModel):
    pass


class HealthStatus(str, Enum):
    ok = "ok"
    error = "error"


class HealthApiResponse(BaseModel):
    checks: Dict[str, str]
    status: HealthStatus


def check_shared_logs_config() -> str:
    path = "/shared-logs-output"
    if not os.path.exists(path):
        raise Exception(f"Missing required path: {path}")
    return HealthStatus.ok


HEALTH_CHECKS: Dict[str, Callable[[], str]] = {
    "shared_logs_config": check_shared_logs_config,
}


@system_router.get("/health", response_model=HealthApiResponse)
def health():
    results = {}
    status = HealthStatus.ok
    for name, fn in HEALTH_CHECKS.items():
        try:
            results[name] = fn()
        except Exception as e:
            results[name] = f"error: {e}"
            status = HealthStatus.error
    if status != HealthStatus.ok:
        raise HTTPException(status_code=503, detail=results)
    return HealthApiResponse(checks=results, status=status)
