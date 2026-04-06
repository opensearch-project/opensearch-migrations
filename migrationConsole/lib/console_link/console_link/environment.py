import logging
from pathlib import Path
from typing import Dict, Optional, Union
from console_link.models.factories import get_replayer, get_backfill, get_kafka, get_snapshot, \
    get_metrics_source
from console_link.models.cluster import Cluster, SourceCluster
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


def normalize_console_config(config: Dict) -> Dict:
    normalized = dict(config or {})
    if "source_cluster" not in normalized and "source" in normalized:
        normalized["source_cluster"] = normalized.pop("source")
    if "target_cluster" not in normalized and "target" in normalized:
        normalized["target_cluster"] = normalized.pop("target")
    return normalized


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
    source_cluster: Optional[SourceCluster] = None
    target_cluster: Optional[Cluster] = None
    proxy: Optional[Cluster] = None
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
                    self.config = normalize_console_config(yaml.safe_load(f))
                logger.info(f"Loaded config file: {self.config}")
            except Exception:
                if allow_empty:
                    self.config = {}  # or {} or whatever default you want
                else:
                    logger.exception("Got an error while loading or parsing the config file")
                    raise
        elif isinstance(config, Dict):
            self.config = normalize_console_config(config)
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
            self.source_cluster = SourceCluster(config=self.config["source_cluster"],
                                                client_options=self.client_options)
            self.proxy = self.source_cluster.proxy
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
        if self.proxy is not None:
            logger.info(f"Proxy initialized: {self.proxy.endpoint}")
        else:
            logger.info("No proxy provided")

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
        source_cluster = cls._get_source_cluster_from_workflow_config(config)

        instance = super().__new__(cls)

        instance.target_cluster = target_cluster
        instance.source_cluster = source_cluster
        instance.proxy = getattr(source_cluster, "proxy", None)
        instance.kafka = cls._get_kafka_from_workflow_config(config)

        # Wire up Solr-specific metadata and backfill when source is Solr
        instance.metadata = None
        instance.backfill = None
        instance.snapshot = None
        if (source_cluster and isinstance(source_cluster.version, str) and
                source_cluster.version.upper().startswith("SOLR") and target_cluster):
            from console_link.models.solr_metadata import SolrMetadata
            from console_link.models.solr_backfill import SolrBackfill
            instance.metadata = SolrMetadata(source_cluster, target_cluster)
            instance.backfill = SolrBackfill(source_cluster, target_cluster)

        return instance

    @classmethod
    def _get_source_cluster_from_workflow_config(cls, config: Dict) -> Optional[SourceCluster]:
        try:
            source_name, source_config = next(iter(config.get("sourceClusters").items()))
        except (KeyError, AttributeError, StopIteration):
            logger.warning("No source cluster is defined in the workflow config.")
            return None

        logger.info(f"Using source cluster: {source_name}")

        if not source_config:
            return None

        try:
            mapped_source = map_cluster_from_workflow_config(source_config)
            proxy_config = cls._get_source_proxy_from_workflow_config(config, source_name)
            if proxy_config is not None:
                mapped_source["proxy"] = proxy_config
            return SourceCluster(config=mapped_source)
        except ValueError as e:
            logger.warning(f"Source cluster config is not correctly defined: {e}")
            return None

    @classmethod
    def _get_source_proxy_from_workflow_config(cls, config: Dict, source_name: str) -> Optional[Dict]:
        proxies = ((config.get("traffic") or {}).get("proxies") or {})
        matching_proxies = [
            (proxy_name, proxy_config)
            for proxy_name, proxy_config in proxies.items()
            if proxy_config.get("source") == source_name
        ]

        if not matching_proxies:
            return None
        if len(matching_proxies) > 1:
            proxy_names = ", ".join(proxy_name for proxy_name, _ in matching_proxies)
            raise ValueError(
                f"Source '{source_name}' maps to multiple proxies ({proxy_names}). "
                f"Console test routing requires exactly zero or one proxy per source."
            )

        proxy_name, proxy_config = matching_proxies[0]
        proxy_options = proxy_config.get("proxyConfig") or {}
        listen_port = proxy_options.get("listenPort")
        if listen_port is None:
            raise ValueError(f"Proxy '{proxy_name}' is missing proxyConfig.listenPort")

        has_tls = proxy_options.get("tls") is not None
        return {
            "name": proxy_name,
            "endpoint": f"{'https' if has_tls else 'http'}://{proxy_name}:{listen_port}",
            "allow_insecure": has_tls,
        }

    @classmethod
    def _get_kafka_from_workflow_config(cls, config: Dict) -> Optional[Kafka]:
        kafka_clusters = config.get("kafkaClusterConfiguration") or {}
        if not kafka_clusters:
            logger.info("No kafka cluster configuration is defined in the workflow config.")
            return None

        proxies = ((config.get("traffic") or {}).get("proxies") or {})
        referenced_clusters = {
            proxy_config.get("kafka", "default")
            for proxy_config in proxies.values()
        }

        if not referenced_clusters:
            if "default" in kafka_clusters:
                cluster_name = "default"
            elif len(kafka_clusters) == 1:
                cluster_name = next(iter(kafka_clusters))
            else:
                logger.warning("Multiple kafka clusters are defined but no proxy references specify which to use.")
                return None
        elif len(referenced_clusters) == 1:
            cluster_name = next(iter(referenced_clusters))
        else:
            cluster_names = ", ".join(sorted(referenced_clusters))
            raise ValueError(
                f"Workflow config references multiple kafka clusters ({cluster_names}). "
                f"Console kafka commands require exactly zero or one kafka cluster."
            )

        cluster_config = kafka_clusters.get(cluster_name)
        if cluster_config is None:
            raise ValueError(f"Kafka cluster '{cluster_name}' not found in kafkaClusterConfiguration")

        if "existing" in cluster_config:
            existing_config = cluster_config["existing"]
            kafka_config = {
                "broker_endpoints": existing_config["kafkaConnection"],
                "msk" if existing_config.get("enableMSKAuth") else "standard": None
            }
        elif "autoCreate" in cluster_config:
            auto_config = cluster_config["autoCreate"]
            auth_config = auto_config.get("auth") or {}
            auth_type = auth_config.get("type", "")
            if auth_type == "scram-sha-512":
                kafka_config = {
                    "broker_endpoints": f"{cluster_name}-kafka-bootstrap:9093",
                    "scram": {
                        "username": f"{cluster_name}-migration-app",
                        "password_env": "KAFKA_SCRAM_PASSWORD",
                        "ca_cert_path": "/config/kafka-ca/ca.crt",
                    }
                }
            else:
                kafka_config = {
                    "broker_endpoints": f"{cluster_name}-kafka-bootstrap:9092",
                    "standard": None
                }
        else:
            kafka_config = {
                "broker_endpoints": f"{cluster_name}-kafka-bootstrap:9092",
                "standard": None
            }

        return get_kafka(kafka_config)

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
