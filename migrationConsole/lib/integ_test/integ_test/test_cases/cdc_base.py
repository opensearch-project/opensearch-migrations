"""CDC test base: shared constants, K8s helpers, and proxy utilities.

Test IDs 0030-0039 are reserved for CDC variants.
"""
import logging
import subprocess
import time

from kubernetes import client, config as k8s_config, watch

from console_link.models.cluster import Cluster

from ..cluster_version import (
    ElasticsearchV7_X,
    OpensearchV1_X, OpensearchV2_X, OpensearchV3_X,
)

logger = logging.getLogger(__name__)

# --- Constants shared across all CDC tests ---
PROXY_DEPLOYMENT_NAME = "capture-proxy"
KAFKA_CLUSTER_NAME = "default"
TARGET_LABEL = "target1"
REPLAYER_LABEL_SELECTOR = "app=replayer"
PROXY_LABEL_SELECTOR = "migrations/proxy=capture-proxy"
PROXY_ENDPOINT = "http://capture-proxy:9201"
CDC_SOURCE_TARGET_COMBINATIONS = [
    (ElasticsearchV7_X, OpensearchV1_X),
    (ElasticsearchV7_X, OpensearchV2_X),
    (ElasticsearchV7_X, OpensearchV3_X),
]


# --- Shared helpers ---

def load_k8s_config():
    """Load K8s config once. Safe to call multiple times (idempotent)."""
    try:
        k8s_config.load_incluster_config()
    except k8s_config.ConfigException:
        k8s_config.load_kube_config()


def is_pod_ready(pod) -> bool:
    """Check if a pod has phase=Running and condition Ready=True."""
    if pod.status.phase != "Running":
        return False
    for condition in (pod.status.conditions or []):
        if condition.type == "Ready" and condition.status == "True":
            return True
    return False


def wait_for_pod_ready(namespace: str, label_selector: str, timeout_seconds: int = 1200):
    """Wait until a pod matching the label selector is Running and Ready.

    Uses the K8s Watch API for event-driven detection.
    Equivalent to: kubectl wait --for=condition=Ready pod -l <label> -n <ns>
    """
    load_k8s_config()
    v1 = client.CoreV1Api()

    logger.info("Waiting for pod with label '%s' to be Ready (timeout=%ds)...",
                label_selector, timeout_seconds)

    pods = v1.list_namespaced_pod(namespace, label_selector=label_selector)
    for pod in pods.items:
        if is_pod_ready(pod):
            logger.info("Pod %s is already Running and Ready", pod.metadata.name)
            return

    # Resume watch from where the list left off
    w = watch.Watch()
    for event in w.stream(v1.list_namespaced_pod,
                          namespace=namespace,
                          label_selector=label_selector,
                          resource_version=pods.metadata.resource_version,
                          timeout_seconds=timeout_seconds):
        pod = event["object"]
        if is_pod_ready(pod):
            logger.info("Pod %s is Running and Ready", pod.metadata.name)
            w.stop()
            return
        logger.debug("Pod event %s pod=%s phase=%s",
                     event["type"], pod.metadata.name, pod.status.phase or "Unknown")

    raise TimeoutError(f"No pod with label '{label_selector}' reached Ready within {timeout_seconds}s")


def wait_for_replayer_consuming(namespace: str, timeout_seconds: int = 120, interval: int = 5):
    """Wait until the replayer has joined the Kafka consumer group."""
    start = time.time()
    while time.time() - start < timeout_seconds:
        try:
            result = subprocess.run(
                ["kubectl", "logs", "-l", REPLAYER_LABEL_SELECTOR, "-n", namespace, "--tail=5"],
                capture_output=True, text=True, timeout=15
            )
            for line in result.stdout.split("\n"):
                if "KafkaHeartbeat" in line and "partitions=" in line:
                    logger.info("Replayer is actively consuming from Kafka")
                    return
        except Exception as e:
            logger.debug("Replayer log check failed: %s", e)
        time.sleep(interval)
    logger.error(
        "LIKELY TEST FAILURE: Could not confirm replayer is consuming from Kafka within %ds. "
        "CDC docs sent after this point may not be replayed to target.", timeout_seconds
    )


def make_proxy_cluster(source_cluster):
    """Create a Cluster pointing at the capture-proxy endpoint, inheriting source auth."""
    return Cluster(config={**source_cluster.config, "endpoint": PROXY_ENDPOINT})
