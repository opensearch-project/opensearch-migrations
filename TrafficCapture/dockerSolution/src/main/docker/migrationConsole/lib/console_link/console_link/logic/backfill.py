from enum import Enum
import json
import logging
from typing import Dict, Optional

from console_link.models.backfill_osi import OpenSearchIngestionBackfill
from console_link.models.backfill_rfs import DockerRFSBackfill, ECSRFSBackfill
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
        return OpenSearchIngestionBackfill(config=config,
                                           source_cluster=source_cluster,
                                           target_cluster=target_cluster)
    elif BackfillType.reindex_from_snapshot.name in config:
        if target_cluster is None:
            raise ValueError("target_cluster must be provided for RFS backfill")

        if 'docker' in config[BackfillType.reindex_from_snapshot.name]:
            return DockerRFSBackfill(config=config,
                                     target_cluster=target_cluster)
        elif 'ecs' in config[BackfillType.reindex_from_snapshot.name]:
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
    return backfill.create(*args, **kwargs)


def start(backfill: Backfill, *args, **kwargs) -> str:
    return backfill.start(*args, **kwargs)


def stop(backfill: Backfill, *args, **kwargs) -> str:
    return backfill.stop(*args, **kwargs)


def scale(backfill: Backfill, units: int, *args, **kwargs) -> str:
    return backfill.scale(units, *args, **kwargs)


def status(backfill: Backfill, *args, **kwargs) -> str:
    return backfill.get_status(*args, **kwargs)
