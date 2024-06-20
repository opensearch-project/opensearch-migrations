import logging
from typing import Optional
from console_link.models.cluster import Cluster
from console_link.models.metrics_source import MetricsSource
from console_link.logic.metrics import get_metrics_source
from console_link.logic.backfill import get_backfill
from console_link.models.backfill_base import Backfill
from console_link.models.snapshot import Snapshot, S3Snapshot
import yaml
from cerberus import Validator


logger = logging.getLogger(__name__)

SCHEMA = {
    "source_cluster": {"type": "dict", "required": False},
    "target_cluster": {"type": "dict", "required": True},
    "replayer": {"type": "dict", "required": False},
    "backfill": {"type": "dict", "required": False},
    "metrics_source": {"type": "dict", "required": False},
    "snapshot": {"type": "dict", "required": False},
}


class Environment:
    source_cluster: Optional[Cluster] = None
    target_cluster: Optional[Cluster] = None
    backfill: Optional[Backfill] = None
    metrics_source: Optional[MetricsSource] = None
    snapshot: Optional[Snapshot] = None

    def __init__(self, config_file: str):
        self.config_file = config_file
        with open(self.config_file) as f:
            self.config = yaml.safe_load(f)
        v = Validator(SCHEMA)
        if not v.validate(self.config):
            logger.error(f"Config file validation errors: {v.errors}")
            raise ValueError("Invalid config file", v.errors)

        if 'source_cluster' in self.config:
            self.source_cluster = Cluster(self.config["source_cluster"])
            logger.info(f"Source cluster initialized: {self.source_cluster.endpoint}")
        else:
            logger.info("No source cluster provided")

        # At some point, target and replayers should be stored as pairs, but for the time being
        # we can probably assume one target cluster.
        self.target_cluster: Cluster = Cluster(self.config["target_cluster"])
        logger.info(f"Target cluster initialized: {self.target_cluster.endpoint}")

        if 'metrics_source' in self.config:
            self.metrics_source: MetricsSource = get_metrics_source(
                self.config["metrics_source"]
            )
            logger.info(f"Metrics source initialized: {self.metrics_source}")
        else:
            logger.info("No metrics source provided")

        if 'backfill' in self.config:
            self.backfill: Backfill = get_backfill(self.config["backfill"],
                                                   source_cluster=self.source_cluster,
                                                   target_cluster=self.target_cluster)
            logger.info(f"Backfill migration initialized: {self.backfill}")
        else:
            logger.info("No backfill provided")

        if 'snapshot' in self.config:
            self.snapshot: Snapshot = S3Snapshot(self.config["snapshot"],
                                                 source_cluster=self.source_cluster,
                                                 target_cluster=self.target_cluster)
            logger.info(f"Snapshot initialized: {self.snapshot}")
        else:
            logger.info("No snapshot provided")
