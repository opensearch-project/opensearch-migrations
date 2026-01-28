import logging
from pathlib import Path
from typing import Dict, Optional, Union
from console_link.models.factories import get_replayer, get_backfill, get_kafka, get_snapshot, \
    get_metrics_source
from console_link.models.cluster import Cluster
from console_link.models.metrics_source import MetricsSource
from console_link.models.backfill_base import Backfill
from console_link.models.snapshot import Snapshot
from console_link.models.replayer_base import Replayer
from console_link.models.kafka import Kafka
from console_link.models.client_options import ClientOptions
from console_link.models.utils import map_cluster_from_workflow_config
from console_link.workflow.models.workflow_config_store import WorkflowConfigStore

import yaml
from cerberus import Validator

from console_link.models.metadata import Metadata


logger = logging.getLogger(__name__)


class WorkflowConfigException(Exception):
    """Workflow config object does not provide valid information"""

    def __init__(self, message):
        super().__init__(message)


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
    config: Dict

    def __init__(self, config: Optional[Dict] = None, config_file: Optional[Union[str, Path]] = None,
                 allow_empty: bool = False):
        """
        Initialize the environment either from a configuration file or a direct configuration object.

        :param config: Direct configuration object (overrides config_file).
        :param config_file: Path to the YAML config file.
        """
        if config_file:
            logger.info(f"Loading config file: {config_file}")
            try:
                with open(config_file) as f:
                    self.config = yaml.safe_load(f)
                logger.info(f"Loaded config file: {self.config}")
            except Exception:
                if allow_empty:
                    self.config = {}  # or {} or whatever default you want
                else:
                    logger.exception("Got an error while loading or parsing the config file")
                    raise
        elif isinstance(config, Dict):
            self.config = config
            logger.info(f"Using provided config: {self.config}")
        else:
            raise ValueError("Either config or config_file must be provided.")

        v = Validator(SCHEMA)
        if not v.validate(self.config):
            logger.error(f"Config file validation errors: {v.errors}")
            raise ValueError("Invalid config file", v.errors)

        if 'client_options' in self.config:
            self.client_options = ClientOptions(self.config["client_options"])

        if 'source_cluster' in self.config:
            self.source_cluster = Cluster(config=self.config["source_cluster"],
                                          client_options=self.client_options)
            logger.info(f"Source cluster initialized: {self.source_cluster.endpoint}")
        else:
            logger.info("No source cluster provided")

        # At some point, target and replayers should be stored as pairs, but for the time being
        # we can probably assume one target cluster.
        if 'target_cluster' in self.config:
            self.target_cluster = Cluster(config=self.config["target_cluster"],
                                          client_options=self.client_options)
            logger.info(f"Target cluster initialized: {self.target_cluster.endpoint}")
        else:
            logger.info("No target cluster provided. This may prevent other actions from proceeding.")

        if 'metrics_source' in self.config:
            self.metrics_source = get_metrics_source(
                config=self.config["metrics_source"],
                client_options=self.client_options
            )
            logger.info(f"Metrics source initialized: {self.metrics_source}")
        else:
            logger.info("No metrics source provided")

        if 'backfill' in self.config:
            if self.target_cluster is None:
                raise ValueError("target_cluster must be provided for RFS backfill")
            self.backfill = get_backfill(self.config["backfill"],
                                         target_cluster=self.target_cluster,
                                         client_options=self.client_options)
            logger.info(f"Backfill migration initialized: {self.backfill}")
        else:
            logger.info("No backfill provided")

        if 'replay' in self.config:
            self.replay = get_replayer(self.config["replay"], client_options=self.client_options)
            logger.info(f"Replay initialized: {self.replay}")

        if 'snapshot' in self.config:
            self.snapshot = get_snapshot(self.config["snapshot"],
                                         source_cluster=self.source_cluster)
            logger.info(f"Snapshot initialized: {self.snapshot}")
        else:
            logger.info("No snapshot provided")
        if 'metadata_migration' in self.config:
            self.metadata = Metadata(self.config["metadata_migration"],
                                     target_cluster=self.target_cluster,
                                     source_cluster=self.source_cluster,
                                     snapshot=self.snapshot)
        if 'kafka' in self.config:
            self.kafka = get_kafka(self.config["kafka"])
            logger.info(f"Kafka initialized: {self.kafka}")

    @classmethod
    def from_workflow_config(cls, namespace='ma', session_name='default', allow_empty=False) -> 'Environment':
        store = WorkflowConfigStore(namespace=namespace)
        config = store.load_config(session_name=session_name)
        logger.info(f"Loading workflow config with namespace {namespace} and session {session_name}")
        if config is None:
            if allow_empty:
                return super().__new__(cls)
            raise WorkflowConfigException(
                f"A workflow config can't be found for namespace `{namespace}` and session name `{session_name}`."
            )

        target_cluster = cls._get_cluster_from_workflow_config(config, "targetClusters", "target cluster")
        source_cluster = cls._get_cluster_from_workflow_config(config, "sourceClusters", "source cluster")

        instance = super().__new__(cls)

        instance.target_cluster = target_cluster
        instance.source_cluster = source_cluster
        return instance

    @classmethod
    def _get_cluster_from_workflow_config(cls, config: Dict, cluster_key: str, cluster_label: str) -> Optional[Cluster]:
        try:
            cluster_name, cluster_config = next(iter(config.get(cluster_key).items()))
        except (KeyError, AttributeError, StopIteration):
            logger.warning(f"No {cluster_label} is defined in the workflow config.")
            return None

        logger.info(f"Using {cluster_label}: {cluster_name}")

        if cluster_config:
            try:
                return Cluster(config=map_cluster_from_workflow_config(cluster_config))
            except ValueError as e:
                logger.warning(f"{cluster_label.capitalize()} config is not correctly defined: {e}")
                return None
        return None
