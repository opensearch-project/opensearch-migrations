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
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments  # noqa: F401 (re-exported)

logger = logging.getLogger(__name__)

# --- Constants shared across all CDC tests ---
PROXY_DEPLOYMENT_NAME = "capture-proxy"
KAFKA_CLUSTER_NAME = "default"
TARGET_LABEL = "target1"
REPLAYER_LABEL_SELECTOR = "app=replayer"
PROXY_LABEL_SELECTOR = "migrations/proxy=capture-proxy"
PROXY_ENDPOINT = "https://capture-proxy:9201"
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
    raise TimeoutError(
        f"Replayer did not join Kafka consumer group within {timeout_seconds}s. "
        f"CDC docs sent after this point will not be replayed to target."
    )


def make_proxy_cluster(source_cluster):
    """Create a Cluster pointing at the capture-proxy endpoint, inheriting source auth."""
    return Cluster(config={**source_cluster.config, "endpoint": PROXY_ENDPOINT,
                           "allow_insecure": True})


def run_generate_data(cluster: str, index_name: str, num_docs: int):
    """Run 'console clusters generate-data' CLI inside the migration console pod."""
    cmd = [
        "console", "clusters", "generate-data",
        "--cluster", cluster,
        "--index-name", index_name,
        "--num-docs", str(num_docs),
    ]
    logger.info("Running: %s", " ".join(cmd))
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    if result.returncode != 0:
        raise RuntimeError(f"generate-data failed (exit {result.returncode}): {result.stderr}")
    logger.info("generate-data output: %s", result.stdout.strip())


def send_bulk(cluster, index_name: str, start: int, count: int):
    """Send a batch of docs with sequential IDs via _bulk API.

    Doc IDs are doc_{start} through doc_{start+count-1}, each with
    title, value, and category fields.
    """
    import json
    from console_link.models.cluster import HttpMethod
    from ..common_utils import execute_api_call

    lines = []
    for i in range(start, start + count):
        action = json.dumps({"index": {"_index": index_name, "_id": f"doc_{i}"}})
        doc = json.dumps({"title": f"Bulk doc {i}", "value": i, "category": "A" if i % 2 == 0 else "B"})
        lines.append(action)
        lines.append(doc)
    body = "\n".join(lines) + "\n"
    headers = {'Content-Type': 'application/x-ndjson'}
    resp = execute_api_call(cluster=cluster, method=HttpMethod.POST, path="/_bulk",
                            data=body, headers=headers)
    bulk_result = resp.json()
    assert not bulk_result.get("errors"), f"Bulk indexing had errors: {bulk_result}"


def _kubectl_delete(resource: str, name: str, namespace: str, timeout: int = 30):
    """Delete a K8s resource with a hard timeout. Ignores not-found. Never blocks."""
    try:
        subprocess.run(
            ["kubectl", "delete", resource, name, "-n", namespace,
             "--ignore-not-found", f"--timeout={timeout}s", "--wait=false"],
            capture_output=True, text=True, timeout=timeout + 5
        )
    except subprocess.TimeoutExpired:
        logger.warning("kubectl delete %s/%s timed out after %ds, continuing", resource, name, timeout)
    except Exception as e:
        logger.warning("Failed to delete %s/%s: %s", resource, name, e)


def cleanup_cdc_resources(namespace: str):
    """Delete all CDC-specific K8s resources to allow sequential test runs.

    Uses kubectl with hard timeouts on every command so that nothing can block.
    Strimzi CRs are deleted first (while operator is running) so finalizers
    cascade-delete the Kafka pods, services, secrets, and configmaps.
    """
    logger.info("Cleaning up CDC resources in namespace %s...", namespace)

    # 1. Strimzi CRs — delete in order, operator handles cascade cleanup
    #    (secrets, configmaps, services, entity-operator, broker pods)
    for resource, name in [
        ("kafkatopic.kafka.strimzi.io", "capture-proxy"),
        ("kafkauser.kafka.strimzi.io", "default-migration-app"),
        ("kafka.kafka.strimzi.io", KAFKA_CLUSTER_NAME),
        ("kafkanodepool.kafka.strimzi.io", "dual-role"),
    ]:
        _kubectl_delete(resource, name, namespace, timeout=60)

    # 2. Wait briefly for Strimzi operator to process finalizers
    try:
        subprocess.run(
            ["kubectl", "wait", "--for=delete", f"kafka.kafka.strimzi.io/{KAFKA_CLUSTER_NAME}",
             "-n", namespace, "--timeout=90s"],
            capture_output=True, text=True, timeout=95
        )
        logger.info("Kafka CR deleted (Strimzi cascade complete)")
    except (subprocess.TimeoutExpired, Exception):
        logger.warning("Kafka CR deletion wait timed out, forcing")
        # Force-remove finalizers if stuck
        subprocess.run(
            ["kubectl", "patch", f"kafka.kafka.strimzi.io/{KAFKA_CLUSTER_NAME}",
             "-n", namespace, "--type=merge", "-p", '{"metadata":{"finalizers":[]}}'],
            capture_output=True, text=True, timeout=10
        )

    # 3. Proxy + replayer deployments and service
    _kubectl_delete("deployment", "capture-proxy", namespace)
    _kubectl_delete("deployment", f"capture-proxy-{TARGET_LABEL}-replayer", namespace)
    _kubectl_delete("service", "capture-proxy", namespace)

    # 4. Kafka PVCs (one per broker replica)
    for i in range(3):
        _kubectl_delete("pvc", f"data-default-dual-role-{i}", namespace)

    # 5. Workflow-created configmaps not owned by Strimzi
    for cm in ["approval-config", "concurrency-config",
               "capture-proxy-kafka-auth",
               f"capture-proxy-{TARGET_LABEL}-replayer-kafka-auth"]:
        _kubectl_delete("configmap", cm, namespace)

    # 6. Inner migration workflow (the outer CDC workflow is handled by the fixture)
    _kubectl_delete("workflow", "migration-workflow", namespace)

    logger.info("CDC resource cleanup complete")
