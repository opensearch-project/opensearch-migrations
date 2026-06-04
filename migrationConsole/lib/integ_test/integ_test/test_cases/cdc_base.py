"""CDC test base: shared constants, K8s helpers, and proxy utilities.

Test IDs 0031-0039 are reserved for CDC variants.
"""
import logging
import subprocess
import time
from typing import Optional

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


def _run_describe_consumer_group(group_name: Optional[str],
                                 probe_timeout_seconds: int = 15) -> Optional[str]:
    """Run `console kafka describe-consumer-group` and return its stdout, or
    None if the group does not exist / the command failed / timed out.

    Caller decides what counts as a successful describe — this helper only
    handles transport-level failures.
    """
    cmd = ["console", "kafka", "describe-consumer-group"]
    if group_name is not None:
        cmd.append(group_name)
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=probe_timeout_seconds)
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return None
    if result.returncode != 0:
        return None
    out = (result.stdout or "")
    if "does not exist" in out:
        return None
    return out


def _iter_native_describe_rows(describe_output: str):
    """Yield column-name -> token dicts for each data row of the native
    `kafka-consumer-groups.sh --describe` table embedded in describe_output.

    The augmented `PARTITION TIME LAG` section is skipped because it has its
    own header. We anchor on the native header row (`GROUP TOPIC PARTITION
    CURRENT-OFFSET LOG-END-OFFSET LAG ...`) and split subsequent non-blank
    rows by whitespace; rows shorter than the header are ignored.
    """
    header_indices = None
    for raw in describe_output.splitlines():
        line = raw.strip()
        if not line:
            header_indices = None
            continue
        tokens = line.split()
        if header_indices is None:
            if tokens[0] == "GROUP" and "PARTITION" in tokens and "CURRENT-OFFSET" in tokens:
                header_indices = {tok: i for i, tok in enumerate(tokens)}
            continue
        # Skip the augmented "PARTITION TIME LAG" sub-table: its header starts
        # with TOPIC, not GROUP, so we drop the native header on a blank line
        # (above) and refuse to parse rows under a non-native header here.
        if "GROUP" not in header_indices:
            continue
        if len(tokens) <= max(header_indices.values()):
            continue
        yield {col: tokens[idx] for col, idx in header_indices.items()}


def _consumer_group_max_lag(group_name: Optional[str] = None,
                            probe_timeout_seconds: int = 15) -> Optional[int]:
    """Return the maximum LAG across all partitions in the consumer-group
    describe table, or None if the group is missing / the describe failed /
    no parseable LAG values are present.

    A row whose LAG is `-` (no committed offset yet) is treated as unbounded
    lag and forces the function to return None — callers will keep polling
    until at least one commit lands.
    """
    out = _run_describe_consumer_group(group_name, probe_timeout_seconds)
    if out is None:
        return None
    saw_row = False
    max_lag: Optional[int] = None
    for row in _iter_native_describe_rows(out):
        saw_row = True
        token = row.get("LAG", "-")
        if token in ("-", ""):
            return None
        try:
            value = int(token)
        except ValueError:
            return None
        if max_lag is None or value > max_lag:
            max_lag = value
    return max_lag if saw_row else None


class ReplayLagDrainTimeout(AssertionError):
    """Raised when the replayer consumer-group fails to drain to the requested
    max-lag threshold within the configured timeout.

    Inherits from AssertionError so it surfaces as a test failure in CI
    reports, not an infrastructure error.
    """


def _wait_for_consumer_group_caught_up(group_name: Optional[str], label: str,
                                       max_allowed_lag: int,
                                       timeout_seconds: int,
                                       interval_seconds: float = 3.0) -> tuple:
    """Bounded poll until the group's max per-partition LAG drops to <=
    `max_allowed_lag`. Returns (succeeded: bool, last_observed: Optional[int]).

    LAG=`-` on any partition (no commit yet) keeps the wait polling — the
    time-lag table is uninformative until at least one auto-commit lands.

    The replayer's in-order commit constraint (OffsetLifecycleTracker) can
    keep LAG=1 around well after every response landed on the target because
    the head-of-line TrafficStream may still be in flight. Callers usually
    pass `max_allowed_lag=1` so a fully-replayed run isn't reported as still
    behind.
    """
    deadline = time.monotonic() + timeout_seconds
    attempt = 0
    last_observed: Optional[int] = None
    display_name = group_name if group_name is not None else "<workflow-resolved>"
    while time.monotonic() < deadline:
        attempt += 1
        observed = _consumer_group_max_lag(group_name)
        last_observed = observed
        if observed is not None and observed <= max_allowed_lag:
            logger.info("[%s] Consumer group '%s' max LAG=%d (<=%d) after %d probe(s)",
                        label, display_name, observed, max_allowed_lag, attempt)
            return True, observed
        time.sleep(interval_seconds)
    logger.warning(
        "[%s] Consumer group '%s' did not reach LAG<=%d within %ds (last observed=%s); "
        "the snapshot below shows the still-behind state.",
        label, display_name, max_allowed_lag, timeout_seconds,
        "<unparsed>" if last_observed is None else last_observed,
    )
    return False, last_observed


def _emit_describe_snapshot(label: str, group_name: Optional[str],
                            timeout_seconds: int) -> None:
    """Run `console kafka describe-consumer-group` once and log the output.

    Infrastructure-level failures (timeout, missing CLI, non-zero exit) are
    logged but never raised — those would mask real test failures.
    """
    cmd = ["console", "kafka", "describe-consumer-group"]
    if group_name is not None:
        cmd.append(group_name)
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


def log_kafka_consumer_group_state(label: str, group_name: Optional[str] = None,
                                   timeout_seconds: int = 120) -> None:
    """Pure snapshot: run `console kafka describe-consumer-group` and log
    its output (including the per-partition TIME LAG section). Used for
    in-progress checkpoints like [replay-start] where the replayer hasn't
    drained yet — no waits, no asserts.

    `group_name=None` (the default) defers selection to the console CLI,
    which resolves the workflow-managed group (`replayer-<targetLabel>` in
    EKS) from the workflow config. Pass an explicit name to override.
    """
    _emit_describe_snapshot(label, group_name, timeout_seconds)


def log_topic_records(label: str, topic: Optional[str] = None,
                      timeout_seconds: int = 60) -> None:
    """Pure snapshot: run `console kafka describe-topic-records` and log the
    per-partition record counts (LOG-END-OFFSETs). Useful as a [pre-gen] /
    [post-gen] checkpoint around generate-data — before any consumer group
    exists, this is the only signal that the capture proxy is offloading
    records to the topic at all.

    Infrastructure-level failures are logged, never raised.
    """
    cmd = ["console", "kafka", "describe-topic-records"]
    if topic is not None:
        cmd.append(topic)
    logger.info("[%s] Running: %s", label, " ".join(cmd))
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        logger.warning("[%s] '%s' timed out after %ds; continuing.",
                       label, " ".join(cmd), timeout_seconds)
        return
    except FileNotFoundError:
        logger.warning("[%s] 'console' CLI not found on PATH; skipping topic-records.", label)
        return
    stdout = (result.stdout or "").rstrip()
    if result.returncode != 0:
        logger.warning("[%s] describe-topic-records exited with %d. stdout:\n%s\nstderr:\n%s",
                       label, result.returncode, stdout, (result.stderr or "").rstrip())
        return
    logger.info("[%s] describe-topic-records output:\n%s", label, stdout)


def _dump_topic_on_drain_failure(label: str, topic: Optional[str],
                                 timeout_seconds: int) -> None:
    """On a drain stall, dump the full reconstructed traffic (and raw records)
    still sitting in the topic, so the failing CI run captures exactly what the
    replayer never finished committing.

    Launches a one-shot `console kafka dump-topic-records` (replayer image,
    `--mode dump-both`, no consumer group — does not perturb the stuck
    replayer's offsets). Best-effort: any failure is logged, never raised, so
    it can't mask the ReplayLagDrainTimeout.
    """
    cmd = ["console", "kafka", "dump-topic-records"]
    if topic is not None:
        cmd.append(topic)
    cmd += ["--mode", "dump-both", "--pod-timeout", str(timeout_seconds)]
    logger.info("[%s] Drain failed — dumping topic for diagnostics: %s", label, " ".join(cmd))
    try:
        # Allow headroom over the pod timeout for image pull + pod teardown.
        result = subprocess.run(cmd, capture_output=True, text=True,
                                timeout=timeout_seconds + 180)
    except subprocess.TimeoutExpired:
        logger.warning("[%s] dump-topic-records timed out; continuing to raise drain failure.", label)
        return
    except FileNotFoundError:
        logger.warning("[%s] 'console' CLI not found on PATH; skipping topic dump.", label)
        return
    stdout = (result.stdout or "").rstrip()
    stderr = (result.stderr or "").rstrip()
    logger.info("[%s] dump-topic-records output:\n%s", label, stdout)
    if stderr:
        logger.info("[%s] dump-topic-records stderr:\n%s", label, stderr)


def assert_replay_drained(label: str = "replay-end",
                          group_name: Optional[str] = None,
                          max_lag: int = 1,
                          timeout_seconds: int = 120,
                          describe_timeout_seconds: int = 120,
                          dump_topic: Optional[str] = "capture-proxy",
                          dump_timeout_seconds: int = 300) -> None:
    """Wait for the replayer consumer-group to drain to max per-partition
    LAG <=`max_lag`, log the describe snapshot, and raise on timeout.

    Use at the post-verification checkpoint ([replay-end]). Without this
    assertion, `verify_clusters` happily passes when the target has the
    expected docs even though the replayer never committed past offset 0
    — a real failure mode we hit on EKS where a single in-flight
    TrafficStream stalled the in-order commit chain forever.

    `max_lag=1` (default) absorbs the in-order commit residue from
    OffsetLifecycleTracker: a head-of-line in-flight TrafficStream can
    keep LAG=1 indefinitely even after every response landed on the
    target. Set higher only if you've consciously decided the test
    tolerates more drift.

    `timeout_seconds=120` (2min): by [replay-end] every response has already
    landed on the target (verify_clusters passed), so a healthy run drains
    within seconds; if it hasn't caught up in 2 minutes the offset commit is
    genuinely stalled and waiting longer just delays the failure.

    On a drain stall, before raising we dump the still-uncommitted topic
    contents (`dump_topic`, full reconstructed HTTP via `dump-both`) so the
    failing run captures exactly what the replayer left behind. Set
    `dump_topic=None` to skip the dump. The dump reads with no consumer group
    and does not perturb the stalled replayer.

    Raises `ReplayLagDrainTimeout` (an AssertionError) on drain stall.
    Infrastructure-level describe/dump failures are logged but never raised.
    """
    drain_succeeded, last_lag = _wait_for_consumer_group_caught_up(
        group_name, label,
        max_allowed_lag=max_lag,
        timeout_seconds=timeout_seconds,
    )
    _emit_describe_snapshot(label, group_name, describe_timeout_seconds)
    if not drain_succeeded:
        # Native record counts + full reconstructed dump of what's stuck.
        log_topic_records(label, topic=dump_topic)
        if dump_topic is not None:
            _dump_topic_on_drain_failure(label, dump_topic, dump_timeout_seconds)
        raise ReplayLagDrainTimeout(
            f"[{label}] Replayer consumer-group did not drain to LAG<={max_lag} "
            f"within {timeout_seconds}s "
            f"(last observed max LAG={last_lag if last_lag is not None else '<unparsed>'}). "
            f"See the snapshot and topic dump logged above for per-partition state."
        )


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
