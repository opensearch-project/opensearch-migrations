from enum import Enum
import json
import logging
from typing import Dict, Optional

from console_link.models.backfill_osi import OpenSearchIngestionBackfill
from console_link.models.backfill_rfs import DockerRFSBackfill, ECSRFSBackfill, RFSBackfill
from console_link.models.cluster import Cluster
from console_link.models.backfill_base import Backfill
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


def describe(backfill: Backfill, as_json=False) -> str:
    response = backfill.describe()
    if as_json:
        return json.dumps(response)
    return yaml.safe_dump(response)


def create(backfill: Backfill, *args, **kwargs) -> str:
    if isinstance(backfill, RFSBackfill):
        # Create is a no-op for an RFS Backfill backend.
        logger.info("RFS Backfill does not support create")
        return "No-op"  # TODO: figure out what (if anything) this should actually be.
    logger.info(f"Creating backfill with {args=} and {kwargs=}")
    try:
        return backfill.create(*args, **kwargs)
    except NotImplementedError:
        logger.error(f"Create is not implemented for backfill {type(backfill).__name__}")
        return f"Create is not implemented for backfill {type(backfill).__name__}"
    except Exception as e:
        logger.error(f"Failed to create backfill: {e}")
        return f"Failure when creating backfill: {type(e).__name__} {e}"


def start(backfill: Backfill, *args, **kwargs) -> str:
    # This logic will have to get a lot more sophisticated as we have more arguments for each command.
    # For the time-being, this is enough to meet our requirements.
    if isinstance(backfill, OpenSearchIngestionBackfill):
        logger.info(f"Starting OpenSearch Ingestion backfill with {args=} and {kwargs=}")
        return backfill.resume(*args, **kwargs)
    logger.info("Starting backfill with no args.")
    try:
        return backfill.start()
    except NotImplementedError:
        logger.error(f"Start is not implemented for backfill {type(backfill).__name__}")
        return f"Start is not implemented for backfill {type(backfill).__name__}"
    except Exception as e:
        logger.error(f"Failed to start backfill: {e}")
        return f"Failure when starting backfill: {type(e).__name__} {e}"


def stop(backfill: Backfill, *args, **kwargs) -> str:
    logger.info("Stopping backfill")
    try:
        return backfill.stop(*args, **kwargs)
    except NotImplementedError:
        logger.error(f"Stop is not implemented for backfill {type(backfill).__name__}")
        return f"Stop is not implemented for backfill {type(backfill).__name__}"
    except Exception as e:
        logger.error(f"Failed to stop backfill: {e}")
        return f"Failure when stopping backfill: {type(e).__name__} {e}"


def scale(backfill: Backfill, units: int, *args, **kwargs) -> str:
    logger.info(f"Scaling backfill to {units} units")
    try:
        return backfill.scale(units, *args, **kwargs)
    except NotImplementedError:
        logger.error(f"Scale is not implemented for backfill {type(backfill).__name__}")
        return f"Scale is not implemented for backfill {type(backfill).__name__}"
    except Exception as e:
        logger.error(f"Failed to scale backfill: {e}")
        return f"Failure when scaling backfill: {type(e).__name__} {e}"


def status(backfill: Backfill, *args, **kwargs) -> str:
    logger.info("Getting backfill status")
    try:
        return backfill.get_status(*args, **kwargs)
    except NotImplementedError:
        logger.error(f"Status is not implemented for backfill {type(backfill).__name__}")
        return f"Status is not implemented for backfill: {type(backfill).__name__}"
    except Exception as e:
        logger.error(f"Failed to get status of backfill: {e}")
        return f"Failure when getting status of backfill: {type(e).__name__} {e}"
