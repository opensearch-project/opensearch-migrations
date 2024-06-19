from enum import Enum
import json
import logging
from typing import Dict, Optional, Tuple

from console_link.models.backfill_osi import OpenSearchIngestionBackfill
from console_link.models.backfill_rfs import DockerRFSBackfill, ECSRFSBackfill
from console_link.models.cluster import Cluster
from console_link.models.backfill_base import Backfill
import yaml


logger = logging.getLogger(__name__)


class ExitCode(Enum):
    SUCCESS = 0
    FAILURE = 1


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


def describe(backfill: Backfill, as_json=False) -> str:
    response = backfill.describe()
    if as_json:
        return json.dumps(response)
    return yaml.safe_dump(response)


def create(backfill: Backfill, *args, **kwargs) -> Tuple[ExitCode, str]:
    logger.info(f"Creating backfill with {args=} and {kwargs=}")
    try:
        result = backfill.create(*args, **kwargs)
    except NotImplementedError:
        logger.error(f"Create is not implemented for backfill {type(backfill).__name__}")
        return ExitCode.FAILURE, f"Create is not implemented for backfill {type(backfill).__name__}"
    except Exception as e:
        logger.error(f"Failed to create backfill: {e}")
        return ExitCode.FAILURE, f"Failure when creating backfill: {type(e).__name__} {e}"
    
    if result.success:
        return ExitCode.SUCCESS, "Backfill created successfully." + "\n" + result.display()
    return ExitCode.FAILURE, "Backfill creation failed." + "\n" + result.display()


def start(backfill: Backfill, *args, **kwargs) -> Tuple[ExitCode, str]:
    try:
        result = backfill.start(*args, **kwargs)
    except NotImplementedError:
        logger.error(f"Start is not implemented for backfill {type(backfill).__name__}")
        return ExitCode.FAILURE, f"Start is not implemented for backfill {type(backfill).__name__}"
    except Exception as e:
        logger.error(f"Failed to start backfill: {e}")
        return ExitCode.FAILURE, f"Failure when starting backfill: {type(e).__name__} {e}"
    
    if result.success:
        return ExitCode.SUCCESS, "Backfill started successfully." + "\n" + result.display()
    return ExitCode.FAILURE, "Backfill start failed." + "\n" + result.display()


def stop(backfill: Backfill, *args, **kwargs) -> Tuple[ExitCode, str]:
    logger.info("Stopping backfill")
    try:
        result = backfill.stop(*args, **kwargs)
    except NotImplementedError:
        logger.error(f"Stop is not implemented for backfill {type(backfill).__name__}")
        return ExitCode.FAILURE, f"Stop is not implemented for backfill {type(backfill).__name__}"
    except Exception as e:
        logger.error(f"Failed to stop backfill: {e}")
        return ExitCode.FAILURE, f"Failure when stopping backfill: {type(e).__name__} {e}"
    if result.success:
        return ExitCode.SUCCESS, "Backfill stopped successfully." + "\n" + result.display()
    return ExitCode.FAILURE, "Backfill stop failed." + "\n" + result.display()


def scale(backfill: Backfill, units: int, *args, **kwargs) -> Tuple[ExitCode, str]:
    logger.info(f"Scaling backfill to {units} units")
    try:
        result = backfill.scale(units, *args, **kwargs)
    except NotImplementedError:
        logger.error(f"Scale is not implemented for backfill {type(backfill).__name__}")
        return ExitCode.FAILURE, f"Scale is not implemented for backfill {type(backfill).__name__}"
    except Exception as e:
        logger.error(f"Failed to scale backfill: {e}")
        return ExitCode.FAILURE, f"Failure when scaling backfill: {type(e).__name__} {e}"
    if result.success:
        return ExitCode.SUCCESS, "Backfill scaled successfully." + "\n" + result.display()
    return ExitCode.FAILURE, "Backfill scale failed." + "\n" + result.display()


def status(backfill: Backfill, *args, **kwargs) -> Tuple[ExitCode, str]:
    logger.info("Getting backfill status")
    try:
        result = backfill.get_status(*args, **kwargs)
    except NotImplementedError:
        logger.error(f"Status is not implemented for backfill {type(backfill).__name__}")
        return ExitCode.FAILURE, f"Status is not implemented for backfill: {type(backfill).__name__}"
    except Exception as e:
        logger.error(f"Failed to get status of backfill: {e}")
        return ExitCode.FAILURE, f"Failure when getting status of backfill: {type(e).__name__} {e}"
    if result.success:
        return ExitCode.SUCCESS, result.value
    return ExitCode.FAILURE, "Backfill status retrieval failed." + "\n" + result
