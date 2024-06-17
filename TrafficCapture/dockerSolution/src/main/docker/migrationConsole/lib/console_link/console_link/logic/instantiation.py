import logging
from console_link.models.cluster import Cluster
from lib.console_link.console_link.models.backfill_osi import OpenSearchIngestionMigration
from console_link.models.metrics_source import MetricsSource, get_metrics_source
import yaml
from cerberus import Validator

logger = logging.getLogger(__name__)

SCHEMA = {
    "source_cluster": {"type": "dict", "required": True},
    "target_cluster": {"type": "dict", "required": True},
    "replayer": {"type": "dict", "required": False},
    "backfill": {"type": "dict", "required": False},
    "metrics_source": {"type": "dict", "required": False},
}


class Environment:
    def __init__(self, config_file: str):
        self.config_file = config_file
        with open(self.config_file) as f:
            self.config = yaml.safe_load(f)
        v = Validator(SCHEMA)
        if not v.validate(self.config):
            logger.error(f"Config file validation errors: {v.errors}")
            raise ValueError("Invalid config file", v.errors)

        self.source_cluster: Cluster = Cluster(self.config["source_cluster"])
        logger.debug(f"Source cluster initialized: {self.source_cluster.endpoint}")

        # At some point, target and replayers should be stored as pairs, but for the time being
        # we can probably assume one target cluster.
        self.target_cluster: Cluster = Cluster(self.config["target_cluster"])
        logger.debug(f"Target cluster initialized: {self.target_cluster.endpoint}")

        self.metrics_source: MetricsSource = get_metrics_source(
            self.config.get("metrics_source")
        )
        logger.debug(f"Metrics source initialized: {self.metrics_source}")

        backfill = self.config.get('backfill')
        if backfill:
            osi_backfill = backfill.get('opensearch_ingestion')
            if osi_backfill:
                self.backfill = OpenSearchIngestionMigration(config=osi_backfill,
                                                             source_cluster=self.source_cluster,
                                                             target_cluster=self.target_cluster)
            else:
                self.backfill = None
        else:
            self.backfill = None
        logger.debug(f"Backfill migration initialized: {self.backfill}")
