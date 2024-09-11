import logging
from typing import Optional
from console_link.models.factories import get_replayer, get_backfill, get_kafka, get_snapshot, \
    get_metrics_source
from console_link.models.cluster import Cluster
from console_link.models.metrics_source import MetricsSource
from console_link.models.backfill_base import Backfill
from console_link.models.snapshot import Snapshot
from console_link.models.replayer_base import Replayer
from console_link.models.kafka import Kafka
from console_link.models.client_options import ClientOptions

import yaml
from cerberus import Validator

from console_link.models.metadata import Metadata

logger = logging.getLogger(__name__)


SCHEMA = {
    "source_cluster": {"type": "dict", "required": False},
    "target_cluster": {"type": "dict", "required": False},
    "backfill": {"type": "dict", "required": False},
    "metrics_source": {"type": "dict", "required": False},
    "snapshot": {"type": "dict", "required": False},
    "metadata_migration": {"type": "dict", "required": False},
    "replay": {"type": "dict", "required": False},
    "kafka": {"type": "dict", "required": False},
    "client_options": {"type": "dict", "required": False},
}


class Environment:
    source_cluster: Optional[Cluster] = None
    target_cluster: Optional[Cluster] = None
    backfill: Optional[Backfill] = None
    metrics_source: Optional[MetricsSource] = None
    snapshot: Optional[Snapshot] = None
    metadata: Optional[Metadata] = None
    replay: Optional[Replayer] = None
    kafka: Optional[Kafka] = None
    client_options: Optional[ClientOptions] = None

    def __init__(self, config_file: str):
        logger.info(f"Loading config file: {config_file}")
        self.config_file = config_file
        with open(self.config_file) as f:
            self.config = yaml.safe_load(f)
            logger.info(f"Loaded config file: {self.config}")
        v = Validator(SCHEMA)
        if not v.validate(self.config):
            logger.error(f"Config file validation errors: {v.errors}")
            raise ValueError("Invalid config file", v.errors)

        if 'client_options' in self.config:
            self.client_options: ClientOptions = ClientOptions(self.config["client_options"])

        if 'source_cluster' in self.config:
            self.source_cluster = Cluster(config=self.config["source_cluster"],
                                          client_options=self.client_options)
            logger.info(f"Source cluster initialized: {self.source_cluster.endpoint}")
        else:
            logger.info("No source cluster provided")

        # At some point, target and replayers should be stored as pairs, but for the time being
        # we can probably assume one target cluster.
        if 'target_cluster' in self.config:
            self.target_cluster: Cluster = Cluster(config=self.config["target_cluster"],
                                                   client_options=self.client_options)
            logger.info(f"Target cluster initialized: {self.target_cluster.endpoint}")
        else:
            logger.warning("No target cluster provided. This may prevent other actions from proceeding.")

        if 'metrics_source' in self.config:
            self.metrics_source: MetricsSource = get_metrics_source(
                config=self.config["metrics_source"],
                client_options=self.client_options
            )
            logger.info(f"Metrics source initialized: {self.metrics_source}")
        else:
            logger.info("No metrics source provided")

        if 'backfill' in self.config:
            self.backfill: Backfill = get_backfill(self.config["backfill"],
                                                   source_cluster=self.source_cluster,
                                                   target_cluster=self.target_cluster,
                                                   client_options=self.client_options)
            logger.info(f"Backfill migration initialized: {self.backfill}")
        else:
            logger.info("No backfill provided")

        if 'replay' in self.config:
            self.replay: Replayer = get_replayer(self.config["replay"], client_options=self.client_options)
            logger.info(f"Replay initialized: {self.replay}")

        if 'snapshot' in self.config:
            self.snapshot: Snapshot = get_snapshot(self.config["snapshot"],
                                                   source_cluster=self.source_cluster)
            logger.info(f"Snapshot initialized: {self.snapshot}")
        else:
            logger.info("No snapshot provided")
        if 'metadata_migration' in self.config:
            self.metadata: Metadata = Metadata(self.config["metadata_migration"],
                                               target_cluster=self.target_cluster,
                                               snapshot=self.snapshot)
        if 'kafka' in self.config:
            self.kafka: Kafka = get_kafka(self.config["kafka"])
            logger.info(f"Kafka initialized: {self.kafka}")
