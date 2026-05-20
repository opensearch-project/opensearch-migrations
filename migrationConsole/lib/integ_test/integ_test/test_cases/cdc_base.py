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


def _log_pod_diagnostics(namespace: str, label_selector: str, why: str) -> None:
    """Dump pod state, container status, and recent namespace events.

    Called when a pod-readiness wait fails or stalls so the pytest log
    contains the actual reason (ImagePullBackOff, init failure, missing
    parent CR, scheduler pressure, etc.) instead of a bare TimeoutError.
    Best-effort: any kubectl/API failure here is swallowed so we never
    mask the underlying error.
    """
    logger.warning("Pod-readiness diagnostics (%s) for namespace=%s selector=%s",
                   why, namespace, label_selector)
    try:
        v1 = client.CoreV1Api()
        pods = v1.list_namespaced_pod(namespace, label_selector=label_selector)
        if not pods.items:
            logger.warning("  No pods match selector %r in namespace %r — "
                           "the controller (Argo workflow / Strimzi / migration operator) "
                           "likely never created them. Dumping owner-deployment state.",
                           label_selector, namespace)
        for pod in pods.items:
            phase = (pod.status.phase or "Unknown")
            conds = ", ".join(
                f"{c.type}={c.status}" + (f" ({c.reason})" if c.reason else "")
                for c in (pod.status.conditions or [])
            )
            logger.warning("  pod=%s phase=%s conditions=[%s]",
                           pod.metadata.name, phase, conds)
            for cs in (pod.status.container_statuses or []):
                state = cs.state
                detail = "running" if state.running else \
                    (f"waiting reason={state.waiting.reason} msg={state.waiting.message}"
                     if state.waiting else
                     (f"terminated reason={state.terminated.reason} "
                      f"exit={state.terminated.exit_code} msg={state.terminated.message}"
                      if state.terminated else "unknown"))
                logger.warning("    container=%s ready=%s restartCount=%s state=%s",
                               cs.name, cs.ready, cs.restart_count, detail)
            for cs in (pod.status.init_container_statuses or []):
                state = cs.state
                detail = "running" if state.running else \
                    (f"waiting reason={state.waiting.reason} msg={state.waiting.message}"
                     if state.waiting else
                     (f"terminated reason={state.terminated.reason} "
                      f"exit={state.terminated.exit_code}"
                      if state.terminated else "unknown"))
                logger.warning("    init=%s ready=%s restartCount=%s state=%s",
                               cs.name, cs.ready, cs.restart_count, detail)
    except Exception as e:  # noqa: BLE001 — diagnostic must never raise
        logger.warning("  pod listing failed: %s", e)

    # Recent namespace events — the most useful single signal for
    # 'pod stuck Pending' (FailedScheduling / FailedMount / ImagePullBackOff).
    diag_cmds = (
        ["kubectl", "-n", namespace, "get", "pods", "-o", "wide"],
        ["kubectl", "-n", namespace, "get", "deployments,statefulsets,replicasets"],
        ["kubectl", "-n", namespace, "get",
         "kafkaclusters.migrations.opensearch.org,"
         "captureproxies.migrations.opensearch.org,"
         "trafficreplays.migrations.opensearch.org,"
         "kafkas.kafka.strimzi.io,kafkanodepools.kafka.strimzi.io,"
         "kafkatopics.kafka.strimzi.io,kafkausers.kafka.strimzi.io"],
        ["kubectl", "-n", namespace, "get", "events",
         "--sort-by=.lastTimestamp", "--field-selector=type!=Normal"],
    )
    for cmd in diag_cmds:
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
            if result.stdout:
                logger.warning("  $ %s\n%s", " ".join(cmd), result.stdout.rstrip())
            if result.stderr:
                logger.warning("  $ %s (stderr)\n%s", " ".join(cmd), result.stderr.rstrip())
        except Exception as e:  # noqa: BLE001
            logger.warning("  $ %s failed: %s", " ".join(cmd), e)


def wait_for_pod_ready(namespace: str, label_selector: str, timeout_seconds: int = 1200):
    """Wait until a pod matching the label selector is Running and Ready.

    Uses the K8s Watch API for event-driven detection, but emits an
    INFO-level heartbeat every ``_HEARTBEAT_SECONDS`` so a stuck wait is
    visible in the pytest log instead of leaving a 60-minute silent gap.
    On timeout, dumps namespace/pod/event diagnostics before raising so
    the next CI failure shows the actual reason (ImagePullBackOff,
    FailedScheduling, missing parent CR, etc.).
    Equivalent in spirit to: ``kubectl wait --for=condition=Ready pod -l <label> -n <ns>``.
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

    # Heartbeat cadence: short enough that a stuck wait surfaces inside
    # 30s polling windows, long enough that healthy waits don't spam.
    _HEARTBEAT_SECONDS = 30
    deadline = time.monotonic() + timeout_seconds
    last_heartbeat = time.monotonic()
    last_event_summary: tuple = ()
    resource_version = pods.metadata.resource_version

    while time.monotonic() < deadline:
        # Each watch.stream call may close before our outer deadline (the
        # apiserver enforces its own min-request-timeout, typically 1800s).
        # Loop until our deadline or readiness, re-listing on each pass to
        # refresh resource_version after a watch close.
        remaining = max(1, int(deadline - time.monotonic()))
        watch_window = min(remaining, _HEARTBEAT_SECONDS)
        w = watch.Watch()
        try:
            for event in w.stream(v1.list_namespaced_pod,
                                  namespace=namespace,
                                  label_selector=label_selector,
                                  resource_version=resource_version,
                                  timeout_seconds=watch_window):
                pod = event["object"]
                resource_version = pod.metadata.resource_version or resource_version
                phase = pod.status.phase or "Unknown"
                # Per-event INFO log so the pytest log shows pod lifecycle
                # progress (Pending -> ContainerCreating -> Running -> Ready)
                # in real time. Cheap; one line per state change.
                logger.info("Pod event %s pod=%s phase=%s",
                            event["type"], pod.metadata.name, phase)
                if is_pod_ready(pod):
                    logger.info("Pod %s is Running and Ready", pod.metadata.name)
                    w.stop()
                    return
        except Exception as e:  # noqa: BLE001
            # Treat watch failures (410 Gone, transient HTTP) as a re-list cue.
            logger.warning("Watch stream error (%s); re-listing pods", e)
            try:
                pods = v1.list_namespaced_pod(namespace, label_selector=label_selector)
                resource_version = pods.metadata.resource_version
                for pod in pods.items:
                    if is_pod_ready(pod):
                        logger.info("Pod %s is Running and Ready (post re-list)",
                                    pod.metadata.name)
                        return
            except Exception as e2:  # noqa: BLE001
                logger.warning("Re-list also failed: %s", e2)

        # Heartbeat: summarize current pods so a hung wait is loud.
        if time.monotonic() - last_heartbeat >= _HEARTBEAT_SECONDS:
            try:
                snapshot = v1.list_namespaced_pod(namespace, label_selector=label_selector)
                summary = tuple(
                    (p.metadata.name,
                     p.status.phase or "Unknown",
                     tuple((cs.name, cs.ready,
                            (cs.state.waiting.reason if cs.state.waiting else None))
                           for cs in (p.status.container_statuses or [])))
                    for p in snapshot.items
                )
            except Exception as e:  # noqa: BLE001
                summary = (("<list-failed>", str(e), ()),)
            elapsed = int(time.monotonic() - (deadline - timeout_seconds))
            if summary != last_event_summary:
                logger.info(
                    "Still waiting for %s ready (elapsed=%ds, timeout=%ds); current pods: %s",
                    label_selector, elapsed, timeout_seconds,
                    summary if summary else "<none — controller has not created any yet>")
                last_event_summary = summary
            else:
                logger.info(
                    "Still waiting for %s ready (elapsed=%ds); state unchanged",
                    label_selector, elapsed)
            last_heartbeat = time.monotonic()

    _log_pod_diagnostics(namespace, label_selector,
                         why=f"timed out after {timeout_seconds}s")
    raise TimeoutError(
        f"No pod with label '{label_selector}' reached Ready within {timeout_seconds}s")


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


def log_kafka_consumer_group_state(label: str, group_name: str = "logging-group-default",
                                   timeout_seconds: int = 120) -> None:
    """Run 'console kafka describe-consumer-group' and log its output (including
    the per-partition TIME LAG section) at a labelled point in a CDC test.

    Records consumer-group offset/lag posture at well-known points (typically
    just after the replayer joins the group, and after replay verification
    completes) so post-mortem of a failed run can read the lag trajectory from
    logs alone.

    Failures here are non-fatal: this is an observability helper, not a gate. A
    missing 'console' CLI, broker auth issue, or probe timeout must not break a
    passing migration test.
    """
    cmd = ["console", "kafka", "describe-consumer-group", group_name]
    logger.info("[%s] Running: %s", label, " ".join(cmd))
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        logger.warning("[%s] '%s' timed out after %ds; continuing.",
                       label, " ".join(cmd), timeout_seconds)
        return
    except FileNotFoundError:
        logger.warning("[%s] 'console' CLI not found on PATH; skipping consumer-group describe.",
                       label)
        return

    stdout = (result.stdout or "").rstrip()
    stderr = (result.stderr or "").rstrip()
    if result.returncode != 0:
        logger.warning("[%s] describe-consumer-group exited with %d. stdout:\n%s\nstderr:\n%s",
                       label, result.returncode, stdout, stderr)
        return
    if stderr:
        logger.info("[%s] describe-consumer-group stderr:\n%s", label, stderr)
    logger.info("[%s] describe-consumer-group output:\n%s", label, stdout)


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
