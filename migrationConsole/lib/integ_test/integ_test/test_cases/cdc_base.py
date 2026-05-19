"""CDC test base: shared constants, K8s helpers, and proxy utilities.

Test IDs 0031-0039 are reserved for CDC variants.
"""
import logging
import subprocess
import time

from kubernetes import client, config as k8s_config, watch

from console_link.models.cluster import Cluster

from ..cluster_version import CDC_MIGRATION_COMBINATIONS
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments  # noqa: F401 (re-exported)

logger = logging.getLogger(__name__)

# --- Constants shared across all CDC tests ---
PROXY_DEPLOYMENT_NAME = "capture-proxy"
REPLAYER_LABEL_SELECTOR = "app=replayer"
PROXY_LABEL_SELECTOR = "migrations/proxy=capture-proxy"
PROXY_ENDPOINT = "https://capture-proxy:9201"
CDC_SOURCE_TARGET_COMBINATIONS = CDC_MIGRATION_COMBINATIONS


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
                ["kubectl", "logs", "-l", REPLAYER_LABEL_SELECTOR, "-n", namespace, "--tail=100"],
                capture_output=True, text=True, timeout=15
            )
            for line in result.stdout.split("\n"):
                if "KafkaHeartbeat" in line and "partitions=" in line:
                    logger.info("Replayer is actively consuming from Kafka")
                    return
        except Exception as e:
            logger.debug("Replayer log check failed: %s", e)
        time.sleep(interval)
    log_replayer_diagnostics(namespace)
    raise TimeoutError(
        f"Replayer did not join Kafka consumer group within {timeout_seconds}s. "
        f"CDC docs sent after this point will not be replayed to target."
    )


def log_replayer_diagnostics(namespace: str):
    """Log replayer pod state and recent logs before a CDC wait times out."""
    commands = [
        ["get", "pods", "-l", REPLAYER_LABEL_SELECTOR, "-n", namespace, "-o", "wide"],
        ["describe", "pods", "-l", REPLAYER_LABEL_SELECTOR, "-n", namespace],
        ["logs", "-l", REPLAYER_LABEL_SELECTOR, "-n", namespace, "--tail=200"],
        ["logs", "-l", REPLAYER_LABEL_SELECTOR, "-n", namespace, "--previous", "--tail=200"],
    ]
    for args in commands:
        try:
            result = subprocess.run(["kubectl", *args], capture_output=True, text=True, timeout=30)
            command = "kubectl " + " ".join(args)
            if result.stdout.strip():
                logger.info("%s stdout:\n%s", command, result.stdout.strip())
            if result.stderr.strip():
                logger.info("%s stderr:\n%s", command, result.stderr.strip())
        except Exception as e:
            logger.info("Failed to collect replayer diagnostics for kubectl %s: %s", " ".join(args), e)


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
