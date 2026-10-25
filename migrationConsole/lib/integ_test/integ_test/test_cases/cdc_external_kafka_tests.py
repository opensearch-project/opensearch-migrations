"""CDC coverage for external Strimzi Kafka client properties.

Test0036 provisions Kafka directly in the test namespace, then makes the
migration workflow consume it as an external cluster.
"""
import json
import logging
import subprocess
import textwrap
import time
import uuid

from kubernetes import client
from kubernetes.client.rest import ApiException

from .cdc_tests import Test0031CdcOnlyLiveTraffic
from .cdc_base import (
    CDC_SOURCE_TARGET_COMBINATIONS,
    MigrationType,
    MATestUserArguments,
    load_k8s_config,
)

logger = logging.getLogger(__name__)

EXTERNAL_KAFKA_CLUSTER = "external-kafka"
EXTERNAL_KAFKA_NODE_POOL = "external-kafka-dual-role"
EXTERNAL_KAFKA_TOPIC = "logging-traffic-topic"
EXTERNAL_KAFKA_USER = "external-kafka-migration-app"
PROXY_KAFKA_CONFIG_MAP = "capture-proxy-kafka-auth"
REPLAYER_KAFKA_CONFIG_MAP = "capture-proxy-target1-replay1-kafka-auth"


def _kubectl_apply(namespace: str, manifest: str) -> None:
    subprocess.run(
        ["kubectl", "apply", "-n", namespace, "-f", "-"],
        input=manifest,
        text=True,
        check=True,
        timeout=120,
    )


def _kubectl_delete_kafka_resources(namespace: str) -> None:
    subprocess.run(
        [
            "kubectl", "delete", "-n", namespace,
            "kafkatopic", EXTERNAL_KAFKA_TOPIC,
            "kafkauser", EXTERNAL_KAFKA_USER,
            "kafka", EXTERNAL_KAFKA_CLUSTER,
            "kafkanodepool", EXTERNAL_KAFKA_NODE_POOL,
            "--ignore-not-found=true",
            "--wait=false",
        ],
        check=False,
        timeout=120,
    )


def _external_kafka_manifest() -> str:
    return textwrap.dedent(f"""
    apiVersion: kafka.strimzi.io/v1
    kind: KafkaNodePool
    metadata:
      name: {EXTERNAL_KAFKA_NODE_POOL}
      labels:
        strimzi.io/cluster: {EXTERNAL_KAFKA_CLUSTER}
    spec:
      replicas: 1
      roles:
        - controller
        - broker
      storage:
        type: persistent-claim
        size: 1Gi
        deleteClaim: true
    ---
    apiVersion: kafka.strimzi.io/v1
    kind: Kafka
    metadata:
      name: {EXTERNAL_KAFKA_CLUSTER}
      annotations:
        strimzi.io/node-pools: enabled
        strimzi.io/kraft: enabled
    spec:
      kafka:
        version: 4.0.0
        metadataVersion: 4.0-IV3
        listeners:
          - name: tls
            port: 9093
            type: internal
            tls: true
            authentication:
              type: scram-sha-512
        config:
          offsets.topic.replication.factor: 1
          transaction.state.log.replication.factor: 1
          transaction.state.log.min.isr: 1
          default.replication.factor: 1
          min.insync.replicas: 1
      entityOperator:
        topicOperator: {{}}
        userOperator: {{}}
    ---
    apiVersion: kafka.strimzi.io/v1
    kind: KafkaTopic
    metadata:
      name: {EXTERNAL_KAFKA_TOPIC}
      labels:
        strimzi.io/cluster: {EXTERNAL_KAFKA_CLUSTER}
    spec:
      partitions: 1
      replicas: 1
      config:
        retention.ms: 604800000
        segment.bytes: 1073741824
    ---
    apiVersion: kafka.strimzi.io/v1
    kind: KafkaUser
    metadata:
      name: {EXTERNAL_KAFKA_USER}
      labels:
        strimzi.io/cluster: {EXTERNAL_KAFKA_CLUSTER}
    spec:
      authentication:
        type: scram-sha-512
    """).strip()


def _wait_for_strimzi_ready(namespace: str, plural: str, name: str, timeout_seconds: int = 1200) -> None:
    load_k8s_config()
    custom = client.CustomObjectsApi()
    deadline = time.monotonic() + timeout_seconds
    last_conditions = []

    while time.monotonic() < deadline:
        try:
            resource = custom.get_namespaced_custom_object(
                group="kafka.strimzi.io",
                version="v1",
                namespace=namespace,
                plural=plural,
                name=name,
            )
        except ApiException as e:
            if e.status != 404:
                raise
            time.sleep(10)
            continue

        last_conditions = resource.get("status", {}).get("conditions", [])
        if any(c.get("type") == "Ready" and c.get("status") == "True" for c in last_conditions):
            logger.info("%s/%s is Ready", plural, name)
            return
        logger.info("Waiting for %s/%s Ready; conditions=%s", plural, name, last_conditions)
        time.sleep(20)

    raise TimeoutError(f"{plural}/{name} did not become Ready within {timeout_seconds}s; last={last_conditions}")


def _wait_for_secret(namespace: str, name: str, timeout_seconds: int = 300) -> None:
    load_k8s_config()
    core = client.CoreV1Api()
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            core.read_namespaced_secret(name=name, namespace=namespace)
            logger.info("secret/%s is present", name)
            return
        except ApiException as e:
            if e.status != 404:
                raise
        time.sleep(5)
    raise TimeoutError(f"secret/{name} was not created within {timeout_seconds}s")


def _read_config_map_data(namespace: str, name: str) -> str:
    load_k8s_config()
    config_map = client.CoreV1Api().read_namespaced_config_map(name=name, namespace=namespace)
    return (config_map.data or {}).get("client.properties", "")


def _list_migration_kafka_clusters(namespace: str) -> list[str]:
    load_k8s_config()
    custom = client.CustomObjectsApi()
    try:
        response = custom.list_namespaced_custom_object(
            group="migrations.opensearch.org",
            version="v1alpha1",
            namespace=namespace,
            plural="kafkaclusters",
        )
    except ApiException as e:
        if e.status == 404:
            return []
        raise
    return [item.get("metadata", {}).get("name", "") for item in response.get("items", [])]


class Test0036CdcExternalKafkaClientProperties(Test0031CdcOnlyLiveTraffic):
    """CDC-only test using a test-owned external Kafka cluster and client properties."""
    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(user_args=user_args)
        self.description = "CDC-only live traffic through external Kafka with producer/consumer client properties."
        self.migrations_required = [MigrationType.CAPTURE_AND_REPLAY]
        self.allow_source_target_combinations = CDC_SOURCE_TARGET_COMBINATIONS
        self.cdc_index = f"cdc0036-external-kafka-{self.unique_id}-{uuid.uuid4().hex[:4]}"
        self.keep_workflows = False

    def test_before(self):
        namespace = self.argo_service.namespace
        _kubectl_delete_kafka_resources(namespace)
        _kubectl_apply(namespace, _external_kafka_manifest())
        _wait_for_strimzi_ready(namespace, "kafkas", EXTERNAL_KAFKA_CLUSTER)
        _wait_for_secret(namespace, f"{EXTERNAL_KAFKA_CLUSTER}-cluster-ca-cert")
        _wait_for_secret(namespace, EXTERNAL_KAFKA_USER)
        _wait_for_strimzi_ready(namespace, "kafkatopics", EXTERNAL_KAFKA_TOPIC)
        _wait_for_strimzi_ready(namespace, "kafkausers", EXTERNAL_KAFKA_USER)

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.keep_workflows = keep_workflows
        self.parameters["kafka-cluster-config"] = json.dumps({
            "default": {
                "existing": {
                    "kafkaConnection": f"{EXTERNAL_KAFKA_CLUSTER}-kafka-bootstrap:9093",
                    "kafkaTopic": EXTERNAL_KAFKA_TOPIC,
                    "auth": {
                        "type": "scram-sha-512",
                        "secretName": EXTERNAL_KAFKA_USER,
                        "caSecretName": f"{EXTERNAL_KAFKA_CLUSTER}-cluster-ca-cert",
                        "kafkaUserName": EXTERNAL_KAFKA_USER,
                    },
                    "clientProperties": {
                        "producer": {
                            "max.request.size": 8388608,
                        },
                        "consumer": {
                            "fetch.max.bytes": 8388608,
                            "max.partition.fetch.bytes": 8388608,
                        },
                    },
                },
            },
        }, separators=(",", ":"))

    def verify_clusters(self):
        super().verify_clusters()

        namespace = self.argo_service.namespace
        proxy_properties = _read_config_map_data(namespace, PROXY_KAFKA_CONFIG_MAP)
        replayer_properties = _read_config_map_data(namespace, REPLAYER_KAFKA_CONFIG_MAP)

        assert "max.request.size=8388608" in proxy_properties
        assert "ssl.truststore.location=/config/kafka-ca/ca.crt" in proxy_properties
        assert "fetch.max.bytes=8388608" in replayer_properties
        assert "max.partition.fetch.bytes=8388608" in replayer_properties
        assert "ssl.truststore.location=/config/kafka-ca/ca.crt" in replayer_properties

        assert _list_migration_kafka_clusters(namespace) == [], (
            "External-Kafka CDC test should not create workflow-managed KafkaCluster CRs"
        )

    def cleanup(self):
        if not self.keep_workflows:
            _kubectl_delete_kafka_resources(self.argo_service.namespace)
