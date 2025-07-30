from enum import Enum
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Callable, Dict
import os

from console_link.models.container_utils import get_version_str

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


class VersionApiResponse(BaseModel):
    version: str


def check_shared_logs_config() -> str:
    path = "/shared-logs-output"
    if not os.path.exists(path):
        raise FileNotFoundError(f"Missing required path: {path}")
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


@system_router.get("/version", response_model=VersionApiResponse)
def version():
    version_str = get_version_str()
    return VersionApiResponse(version=version_str)
