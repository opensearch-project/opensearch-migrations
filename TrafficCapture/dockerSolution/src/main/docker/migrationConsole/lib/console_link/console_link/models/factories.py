from enum import Enum
from typing import Dict, Optional

from console_link.models.replayer_docker import DockerReplayer
from console_link.models.metrics_source import CloudwatchMetricsSource, PrometheusMetricsSource
from console_link.models.backfill_base import Backfill
from console_link.models.backfill_osi import OpenSearchIngestionBackfill
from console_link.models.backfill_rfs import DockerRFSBackfill, ECSRFSBackfill
from console_link.models.cluster import Cluster
from console_link.models.kafka import MSK, StandardKafka
from console_link.models.replayer_ecs import ECSReplayer
from console_link.models.snapshot import FileSystemSnapshot, S3Snapshot
import logging

logger = logging.getLogger(__name__)


class UnsupportedMetricsSourceError(Exception):
    def __init__(self, supplied_metrics_source: str):
        super().__init__("Unsupported metrics source type", supplied_metrics_source)


class UnsupportedReplayerError(Exception):
    def __init__(self, supplied_replayer: str):
        super().__init__("Unsupported replayer type", supplied_replayer)


class UnsupportedKafkaError(Exception):
    def __init__(self, supplied_kafka: str):
        super().__init__("Unsupported kafka type", supplied_kafka)


class UnsupportedSnapshotError(Exception):
    def __init__(self, supplied_snapshot: str):
        super().__init__("Unsupported snapshot type", supplied_snapshot)


BackfillType = Enum("BackfillType",
                    ["opensearch_ingestion", "reindex_from_snapshot"])


class UnsupportedBackfillTypeError(Exception):
    def __init__(self, supplied_backfill: str):
        super().__init__("Unsupported backfill type", supplied_backfill)


def get_snapshot(config: Dict, source_cluster: Cluster):
    if 'fs' in config:
        return FileSystemSnapshot(config, source_cluster)
    elif 's3' in config:
        return S3Snapshot(config, source_cluster)
    logger.error(f"An unsupported snapshot type was provided: {config.keys()}")
    if len(config.keys()) > 1:
        raise UnsupportedSnapshotError(', '.join(config.keys()))
    raise UnsupportedSnapshotError(next(iter(config.keys())))


def get_replayer(config: Dict):
    if 'ecs' in config:
        return ECSReplayer(config)
    if 'docker' in config:
        return DockerReplayer(config)
    logger.error(f"An unsupported replayer type was provided: {config.keys()}")
    raise UnsupportedReplayerError(next(iter(config.keys())))


def get_kafka(config: Dict):
    if 'msk' in config:
        return MSK(config)
    return StandardKafka(config)


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


def get_metrics_source(config):
    if 'prometheus' in config:
        return PrometheusMetricsSource(config)
    elif 'cloudwatch' in config:
        return CloudwatchMetricsSource(config)
    else:
        logger.error(f"An unsupported metrics source type was provided: {config.keys()}")
        if len(config.keys()) > 1:
            raise UnsupportedMetricsSourceError(', '.join(config.keys()))
        raise UnsupportedMetricsSourceError(next(iter(config.keys())))
