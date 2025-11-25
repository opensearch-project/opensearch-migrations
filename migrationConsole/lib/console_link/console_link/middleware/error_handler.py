import logging
from typing import Any, Callable, Tuple

from console_link.models.cluster import Cluster
from console_link.models.kafka import Kafka
from console_link.models.metadata import Metadata
from console_link.models.metrics_source import MetricsSource
from console_link.models.replayer_base import Replayer
from console_link.models.snapshot import Snapshot
from console_link.models.backfill_base import Backfill
from console_link.models.utils import ExitCode

logger = logging.getLogger(__name__)

Service = Cluster | Backfill | Kafka | Metadata | MetricsSource | Replayer | Snapshot


def handle_errors(service_type: str,
                  on_success: Callable[[Any], Tuple[ExitCode, str]] = lambda status: (ExitCode.SUCCESS, status),
                  on_failure: Callable[[Any], Tuple[ExitCode, str]] = lambda status: (ExitCode.FAILURE, status)
                  ) -> Callable[[Any], Tuple[ExitCode, str]]:
    def decorator(func: Callable[[Any], Tuple[ExitCode, str]]) -> Callable[[Any], Tuple[ExitCode, str]]:
        def wrapper(service: Service, *args, **kwargs) -> Tuple[ExitCode, str]:
            try:
                result = func(service, *args, **kwargs)
            except NotImplementedError:
                logger.error(f"{func.__name__} is not implemented for {service_type} {type(service).__name__}")
                return (ExitCode.FAILURE,
                        f"{func.__name__} is not implemented for {service_type} {type(service).__name__}")
            except Exception as e:
                logger.error(f"Failed to {func.__name__} {service_type}: {e}")
                return ExitCode.FAILURE, f"Failure on {func.__name__} for {service_type}: {type(e).__name__} {e}"
            if result.success:
                return on_success(result.value)
            return on_failure(result.value)
        return wrapper
    return decorator
