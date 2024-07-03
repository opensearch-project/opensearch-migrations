from enum import Enum
import json
import logging
from typing import Dict, List, Optional, Tuple, Callable, Any
from console_link.models.command_result import CommandResult
from console_link.models.utils import ExitCode
from console_link.models.backfill_osi import OpenSearchIngestionBackfill
from console_link.models.backfill_rfs import DockerRFSBackfill, ECSRFSBackfill
from console_link.models.cluster import Cluster
from console_link.models.backfill_base import Backfill, BackfillStatus
import yaml


logger = logging.getLogger(__name__)


BackfillType = Enum("BackfillType",
                    ["opensearch_ingestion", "reindex_from_snapshot"])


class UnsupportedBackfillTypeError(Exception):
    def __init__(self, supplied_backfill: str):
        super().__init__("Unsupported backfill type", supplied_backfill)


def get_backfill(config: Dict, source_cluster: Optional[Cluster], target_cluster: Optional[Cluster]) -> Backfill:
    if BackfillType.opensearch_ingestion.name in config:
        if source_cluster is None:
            raise ValueError("source_cluster must be provided for OpenSearch Ingestion backfill")
        if target_cluster is None:
            raise ValueError("target_cluster must be provided for OpenSearch Ingestion backfill")
        logger.debug("Creating OpenSearch Ingestion backfill instance")
        return OpenSearchIngestionBackfill(config=config,
                                           source_cluster=source_cluster,
                                           target_cluster=target_cluster)
    elif BackfillType.reindex_from_snapshot.name in config:
        if target_cluster is None:
            raise ValueError("target_cluster must be provided for RFS backfill")

        if 'docker' in config[BackfillType.reindex_from_snapshot.name]:
            logger.debug("Creating Docker RFS backfill instance")
            return DockerRFSBackfill(config=config,
                                     target_cluster=target_cluster)
        elif 'ecs' in config[BackfillType.reindex_from_snapshot.name]:
            logger.debug("Creating ECS RFS backfill instance")
            return ECSRFSBackfill(config=config,
                                  target_cluster=target_cluster)

    logger.error(f"An unsupported metrics source type was provided: {config.keys()}")
    if len(config.keys()) > 1:
        raise UnsupportedBackfillTypeError(', '.join(config.keys()))
    raise UnsupportedBackfillTypeError(next(iter(config.keys())))


def handle_errors(on_success: Callable[[Any], Tuple[ExitCode, str]]) -> Callable[[Any], Tuple[ExitCode, str]]:
    def decorator(func: Callable[[Any], Tuple[ExitCode, str]]) -> Callable[[Any], Tuple[ExitCode, str]]:
        def wrapper(backfill: Backfill, *args, **kwargs) -> Tuple[ExitCode, str]:
            try:
                result = func(backfill, *args, **kwargs)
            except NotImplementedError:
                logger.error(f"{func.__name__} is not implemented for backfill {type(backfill).__name__}")
                return ExitCode.FAILURE, f"{func.__name__} is not implemented for backfill {type(backfill).__name__}"
            except Exception as e:
                logger.error(f"Failed to {func.__name__} backfill: {e}")
                return ExitCode.FAILURE, f"Failure on {func.__name__} for backfill: {type(e).__name__} {e}"
            return on_success(result.value)
        return wrapper
    return decorator


def support_json_return() -> Callable[[Tuple[ExitCode, Dict | List | str]], Tuple[ExitCode, str]]:
    def decorator(func: Callable[[Tuple[ExitCode, Dict | List | str]], Tuple[ExitCode, str]]) \
            -> Callable[[Any], Tuple[ExitCode, str]]:
        def wrapper(backfill: Backfill, *args, as_json=False, **kwargs) -> Tuple[ExitCode, str]:
            result = func(backfill, *args, **kwargs)
            if as_json:
                return (result[0], json.dumps(result))
            return (result[0], yaml.safe_dump(result[1]))
        return wrapper
    return decorator


@support_json_return()
def describe(backfill: Backfill, as_json=False) -> Tuple[ExitCode, Dict]:
    response = backfill.describe()
    return (ExitCode.SUCCESS, response)


@handle_errors(on_success=lambda result: (ExitCode.SUCCESS, "Backfill created successfully." + "\n" + result.display()))
def create(backfill: Backfill, *args, **kwargs) -> CommandResult[str]:
    logger.info(f"Creating backfill with {args=} and {kwargs=}")
    return backfill.create(*args, **kwargs)


@handle_errors(on_success=lambda result: (ExitCode.SUCCESS, "Backfill started successfully." + "\n" + result.display()))
def start(backfill: Backfill, *args, **kwargs) -> CommandResult[str]:
    logger.info("Starting backfill")
    return backfill.start(*args, **kwargs)


@handle_errors(on_success=lambda result: (ExitCode.SUCCESS, "Backfill stopped successfully." + "\n" + result.display()))
def stop(backfill: Backfill, *args, **kwargs) -> CommandResult[str]:
    logger.info("Stopping backfill")
    return backfill.stop(*args, **kwargs)


@handle_errors(on_success=lambda status: (ExitCode.SUCCESS, f"{status[0]}\n{status[1]}"))
def status(backfill: Backfill, deep_check: bool, *args, **kwargs) -> CommandResult[Tuple[BackfillStatus, str]]:
    logger.info(f"Getting backfill status with {deep_check=}")
    return backfill.get_status(deep_check, *args, **kwargs)


@handle_errors(on_success=lambda status: (ExitCode.SUCCESS, f"{status[0]}\n{status[1]}"))
def scale(backfill: Backfill, units: int, *args, **kwargs) -> CommandResult[str]:
    logger.info(f"Scaling backfill to {units} units")
    return backfill.scale(units, *args, **kwargs)
