"""CDC test base: shared constants, K8s helpers, and proxy utilities.

Test IDs 0031-0039 are reserved for CDC variants.
"""
import logging
import subprocess
import time

from kubernetes import client, config as k8s_config
from kubernetes.client.rest import ApiException

from console_link.models.cluster import Cluster, SIGV4_SIGNING_ENDPOINT_KEY
from console_link.workflow.commands.crd_utils import CRD_GROUP, CRD_VERSION

from ..cluster_version import CDC_MIGRATION_COMBINATIONS
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments  # noqa: F401 (re-exported)

logger = logging.getLogger(__name__)

# --- Constants shared across all CDC tests ---
PROXY_DEPLOYMENT_NAME = "capture-proxy"
PROXY_RESOURCE_NAME = "capture-proxy"
PROXY_SERVICE_NAME = "capture-proxy"
REPLAYER_LABEL_SELECTOR = "app=replayer"
PROXY_LABEL_SELECTOR = "migrations/proxy=capture-proxy"
PROXY_ENDPOINT = "https://capture-proxy:9201"
CDC_SOURCE_TARGET_COMBINATIONS = CDC_MIGRATION_COMBINATIONS
PROXY_READY_TIMEOUT_SECONDS = 3600
REPLAYER_POD_READY_BUFFER_SECONDS = 20 * 60
DEFAULT_REPLAYER_POD_READY_TIMEOUT_SECONDS = PROXY_READY_TIMEOUT_SECONDS + REPLAYER_POD_READY_BUFFER_SECONDS


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


_POD_READY_HEARTBEAT_SECONDS = 30


def wait_for_pod_ready(namespace: str, label_selector: str, timeout_seconds: int = 1200,
                       dependency_error_check=None):
    """Wait until a pod matching the label selector is Running and Ready.

    Heartbeats once per `_POD_READY_HEARTBEAT_SECONDS` so a stuck wait surfaces
    in the pytest log instead of a 60-minute silent gap, and dumps
    `kubectl describe`/events on timeout so the eventual TimeoutError carries
    enough context to identify the cause (ImagePullBackOff, FailedScheduling,
    parent CR never created the pod, etc.).
    """
    load_k8s_config()
    v1 = client.CoreV1Api()

    logger.info("Waiting for pod with label '%s' to be Ready (timeout=%ds)...",
                label_selector, timeout_seconds)

    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if dependency_error_check:
            dependency_error_check()
        pods = v1.list_namespaced_pod(namespace, label_selector=label_selector)
        for pod in pods.items:
            if is_pod_ready(pod):
                logger.info("Pod %s is Running and Ready", pod.metadata.name)
                return
        elapsed = int(time.monotonic() - (deadline - timeout_seconds))
        logger.info("Still waiting for %s ready (elapsed=%ds, timeout=%ds); pods=%s",
                    label_selector, elapsed, timeout_seconds,
                    [(p.metadata.name, p.status.phase) for p in pods.items] or "<none>")
        time.sleep(_POD_READY_HEARTBEAT_SECONDS)

    _dump_pod_diagnostics(namespace, label_selector)
    raise TimeoutError(f"No pod with label '{label_selector}' reached Ready within {timeout_seconds}s")


def wait_for_proxy_ready(namespace: str, timeout_seconds: int = 1200):
    """Wait until the CaptureProxy CR is Ready.

    The workflow owns pod, Service, endpoint, and load-balancer readiness.
    Tests gate only on the parent CaptureProxy CR status and inspect lower-level
    objects only when collecting diagnostics after an Error or timeout.
    """
    load_k8s_config()

    logger.info("Waiting for captureproxy/%s to be Ready (timeout=%ds)...", PROXY_RESOURCE_NAME, timeout_seconds)

    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        phase, message, service_endpoint, load_balancer_endpoint = _get_capture_proxy_readiness_status(namespace)

        if phase == "Ready":
            logger.info(
                "captureproxy/%s is Ready: serviceEndpoint=%s loadBalancerEndpoint=%s",
                PROXY_RESOURCE_NAME, service_endpoint or "<unset>", load_balancer_endpoint or "<unset>",
            )
            return
        if phase == "Error":
            _dump_proxy_readiness_diagnostics(namespace)
            raise RuntimeError(f"captureproxy/{PROXY_RESOURCE_NAME} entered Error phase: {message}")
        _raise_if_kafka_cluster_error(namespace)

        elapsed = int(time.monotonic() - (deadline - timeout_seconds))
        logger.info(
            "Still waiting for captureproxy/%s Ready (elapsed=%ds, timeout=%ds): phase=%s message=%s "
            "serviceEndpoint=%s loadBalancerEndpoint=%s",
            PROXY_RESOURCE_NAME, elapsed, timeout_seconds, phase, message or "<none>",
            service_endpoint or "<unset>", load_balancer_endpoint or "<unset>",
        )
        time.sleep(_POD_READY_HEARTBEAT_SECONDS)

    _dump_proxy_readiness_diagnostics(namespace)
    raise TimeoutError(f"captureproxy/{PROXY_RESOURCE_NAME} did not reach Ready within {timeout_seconds}s")


def _get_capture_proxy_readiness_status(namespace: str) -> tuple[str, str, str, str]:
    custom = client.CustomObjectsApi()
    try:
        proxy = custom.get_namespaced_custom_object(
            group=CRD_GROUP,
            version=CRD_VERSION,
            namespace=namespace,
            plural="captureproxies",
            name=PROXY_RESOURCE_NAME,
        )
    except ApiException as e:
        return (
            "NotFound" if e.status == 404 else f"ApiException({e.status})",
            e.reason,
            "",
            "",
        )

    status = proxy.get("status", {})
    return (
        status.get("phase", "Unknown"),
        status.get("message", ""),
        status.get("serviceEndpoint", ""),
        status.get("loadBalancerEndpoint", ""),
    )


def _raise_if_cdc_dependency_error(namespace: str) -> None:
    phase, message, _, _ = _get_capture_proxy_readiness_status(namespace)
    if phase == "Error":
        _dump_proxy_readiness_diagnostics(namespace)
        raise RuntimeError(f"captureproxy/{PROXY_RESOURCE_NAME} entered Error phase: {message}")
    _raise_if_kafka_cluster_error(namespace)


def _raise_if_kafka_cluster_error(namespace: str) -> None:
    errors = _get_kafka_cluster_errors(namespace)
    if not errors:
        return
    _dump_kafka_readiness_diagnostics(namespace)
    formatted = ", ".join(f"{name}: {message or '<none>'}" for name, message in errors)
    raise RuntimeError(f"KafkaCluster dependency entered Error phase: {formatted}")


def _get_kafka_cluster_errors(namespace: str) -> list[tuple[str, str]]:
    custom = client.CustomObjectsApi()
    try:
        response = custom.list_namespaced_custom_object(
            group=CRD_GROUP,
            version=CRD_VERSION,
            namespace=namespace,
            plural="kafkaclusters",
        )
    except ApiException:
        return []

    errors = []
    for item in response.get("items", []):
        status = item.get("status", {})
        if status.get("phase") == "Error":
            errors.append((item.get("metadata", {}).get("name", "<unknown>"), status.get("message", "")))
    return errors


def _dump_proxy_readiness_diagnostics(namespace: str) -> None:
    """Dump details that explain why the parent CaptureProxy CR is not Ready."""
    _dump_capture_proxy_diagnostics(namespace, PROXY_RESOURCE_NAME)
    _dump_pod_diagnostics(namespace, PROXY_LABEL_SELECTOR)
    _dump_service_diagnostics(namespace, PROXY_SERVICE_NAME)


def _dump_pod_diagnostics(namespace: str, label_selector: str) -> None:
    """Best-effort diagnostic dump on pod-readiness timeout.

    `kubectl describe` exposes container waiting-reason + recent pod events;
    `kubectl get events --field-selector=type!=Normal` exposes scheduler /
    kubelet errors (FailedScheduling, FailedMount). Failures here are
    swallowed so they can't mask the underlying TimeoutError.
    """
    cmds = (
        ["kubectl", "describe", "pod", "-l", label_selector, "-n", namespace],
        ["kubectl", "get", "events", "-n", namespace,
         "--sort-by=.lastTimestamp", "--field-selector=type!=Normal"],
    )
    for cmd in cmds:
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
            if result.stdout.strip():
                logger.warning("$ %s\n%s", " ".join(cmd), result.stdout.rstrip())
        except Exception as e:  # noqa: BLE001 — diagnostic must not raise
            logger.warning("$ %s failed: %s", " ".join(cmd), e)


def _dump_capture_proxy_diagnostics(namespace: str, proxy_name: str) -> None:
    cmds = (
        ["kubectl", "get", "captureproxies", proxy_name, "-n", namespace, "-o", "yaml"],
        ["kubectl", "describe", "captureproxies", proxy_name, "-n", namespace],
    )
    for cmd in cmds:
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
            if result.stdout.strip():
                logger.warning("$ %s\n%s", " ".join(cmd), result.stdout.rstrip())
            if result.stderr.strip():
                logger.warning("$ %s stderr\n%s", " ".join(cmd), result.stderr.rstrip())
        except Exception as e:  # noqa: BLE001 - diagnostic must not raise
            logger.warning("$ %s failed: %s", " ".join(cmd), e)


def _dump_service_diagnostics(namespace: str, service_name: str) -> None:
    cmds = (
        ["kubectl", "get", "service", service_name, "-n", namespace, "-o", "wide"],
        ["kubectl", "describe", "service", service_name, "-n", namespace],
        ["kubectl", "get", "endpoints", service_name, "-n", namespace, "-o", "yaml"],
        ["kubectl", "get", "endpointslices", "-n", namespace,
         "-l", f"kubernetes.io/service-name={service_name}", "-o", "yaml"],
        ["kubectl", "get", "events", "-n", namespace,
         "--sort-by=.lastTimestamp", "--field-selector=type!=Normal"],
    )
    for cmd in cmds:
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
            if result.stdout.strip():
                logger.warning("$ %s\n%s", " ".join(cmd), result.stdout.rstrip())
            if result.stderr.strip():
                logger.warning("$ %s stderr\n%s", " ".join(cmd), result.stderr.rstrip())
        except Exception as e:  # noqa: BLE001 - diagnostic must not raise
            logger.warning("$ %s failed: %s", " ".join(cmd), e)


def wait_for_replayer_consuming(
    namespace: str,
    timeout_seconds: int = 120,
    interval: int = 5,
    pod_ready_timeout_seconds: int = DEFAULT_REPLAYER_POD_READY_TIMEOUT_SECONDS,
):
    """Wait until the replayer has joined the Kafka consumer group."""
    logger.info("Waiting for replayer pod readiness before checking Kafka consumer group...")
    try:
        wait_for_pod_ready(
            namespace,
            REPLAYER_LABEL_SELECTOR,
            timeout_seconds=pod_ready_timeout_seconds,
            dependency_error_check=lambda: _raise_if_cdc_dependency_error(namespace),
        )
    except TimeoutError:
        _dump_capture_proxy_diagnostics(namespace, PROXY_RESOURCE_NAME)
        _dump_kafka_readiness_diagnostics(namespace)
        raise

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


def _dump_kafka_readiness_diagnostics(namespace: str) -> None:
    cmds = (
        ["kubectl", "get", "kafkaclusters.migrations.opensearch.org", "-n", namespace, "-o", "yaml"],
        ["kubectl", "get", "kafkas.kafka.strimzi.io", "-n", namespace, "-o", "yaml"],
        ["kubectl", "get", "kafkanodepools.kafka.strimzi.io", "-n", namespace, "-o", "yaml"],
        ["kubectl", "get", "pods", "-n", namespace, "-l", "strimzi.io/cluster", "-o", "wide"],
        ["kubectl", "describe", "pods", "-n", namespace, "-l", "strimzi.io/cluster"],
        ["kubectl", "get", "events", "-n", namespace, "--sort-by=.lastTimestamp", "--field-selector=type!=Normal"],
    )
    for cmd in cmds:
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
            if result.stdout.strip():
                logger.warning("$ %s\n%s", " ".join(cmd), result.stdout.rstrip())
            if result.stderr.strip():
                logger.warning("$ %s stderr\n%s", " ".join(cmd), result.stderr.rstrip())
        except Exception as e:  # noqa: BLE001 - diagnostic must not raise
            logger.warning("$ %s failed: %s", " ".join(cmd), e)


def make_proxy_cluster(source_cluster):
    """Create a Cluster pointing at the capture-proxy endpoint, inheriting source auth."""
    proxy_config = {
        **source_cluster.config,
        "endpoint": PROXY_ENDPOINT,
        "allow_insecure": True,
    }
    if "sigv4" in proxy_config:
        proxy_config[SIGV4_SIGNING_ENDPOINT_KEY] = source_cluster.endpoint
    return Cluster(config=proxy_config)


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
