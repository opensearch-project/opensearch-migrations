from enum import Enum
from typing import Dict, Optional

from console_link.models.client_options import ClientOptions
from console_link.models.replayer_docker import DockerReplayer
from console_link.models.replayer_k8s import K8sReplayer
from console_link.models.metrics_source import CloudwatchMetricsSource, PrometheusMetricsSource
from console_link.models.backfill_base import Backfill
from console_link.models.backfill_rfs import DockerRFSBackfill, ECSRFSBackfill, K8sRFSBackfill
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


def get_snapshot(config: Dict, source_cluster: Optional[Cluster]):
    if 'fs' in config:
        return FileSystemSnapshot(config, source_cluster)
    elif 's3' in config:
        return S3Snapshot(config, source_cluster)
    logger.error(f"An unsupported snapshot type was provided: {config.keys()}")
    if len(config.keys()) > 1:
        raise UnsupportedSnapshotError(', '.join(config.keys()))
    raise UnsupportedSnapshotError(next(iter(config.keys())))


def get_replayer(config: Dict, client_options: Optional[ClientOptions] = None):
    if 'ecs' in config:
        return ECSReplayer(config=config, client_options=client_options)
    if 'docker' in config:
        return DockerReplayer(config)
    if 'k8s' in config:
        return K8sReplayer(config=config, client_options=client_options)
    logger.error(f"An unsupported replayer type was provided: {config.keys()}")
    raise UnsupportedReplayerError(next(iter(config.keys())))


def get_kafka(config: Dict):
    if 'msk' in config:
        return MSK(config)
    if 'standard' in config:
        return StandardKafka(config)
    config.pop("broker_endpoints", None)
    logger.error(f"An unsupported kafka source type was provided: {config.keys()}")
    raise UnsupportedKafkaError(', '.join(config.keys()))


def get_backfill(config: Dict, target_cluster: Cluster,
                 client_options: Optional[ClientOptions] = None) -> Backfill:
    if BackfillType.reindex_from_snapshot.name in config:
        if 'docker' in config[BackfillType.reindex_from_snapshot.name]:
            logger.debug("Creating Docker RFS backfill instance")
            return DockerRFSBackfill(config=config,
                                     target_cluster=target_cluster)
        elif 'ecs' in config[BackfillType.reindex_from_snapshot.name]:
            logger.debug("Creating ECS RFS backfill instance")
            return ECSRFSBackfill(config=config,
                                  target_cluster=target_cluster,
                                  client_options=client_options)
        elif 'k8s' in config[BackfillType.reindex_from_snapshot.name]:
            logger.debug("Creating K8s RFS backfill instance with config=" + str(config))
            return K8sRFSBackfill(config=config,
                                  target_cluster=target_cluster,
                                  client_options=client_options)

    logger.error(f"An unsupported backfill source type was provided: {config.keys()}")
    raise UnsupportedBackfillTypeError(', '.join(config.keys()))


def get_metrics_source(config, client_options: Optional[ClientOptions] = None):
    if 'prometheus' in config:
        return PrometheusMetricsSource(config=config, client_options=client_options)
    elif 'cloudwatch' in config:
        return CloudwatchMetricsSource(config=config, client_options=client_options)
    else:
        logger.error(f"An unsupported metrics source type was provided: {config.keys()}")
        raise UnsupportedMetricsSourceError(', '.join(config.keys()))
