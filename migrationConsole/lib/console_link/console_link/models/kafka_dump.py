"""Launch a one-shot Kafka "dump" pod using the traffic-replayer image.

The traffic-replayer already supports non-replay dump modes
(`--mode dump-raw|dump-http|dump-both`) that read a topic with NO consumer
group (see KafkaTopicDumper.java: it removes group.id, assigns partitions,
seeks to the requested start, then polls until it reaches the end-of-log it
captured at startup — printing the whole stream and exiting). Because it never
joins a consumer group and never commits, it is safe to run against a live
migration topic without disturbing the replayer's offsets.

This module renders a Pod that runs that dumper, applies it via `kubectl`,
streams its logs straight to the console, and tears the pod down. It is
designed to work in any EKS deployment that has Kafka deployed by the
migration workflow:

  * the replayer image is read from the Helm-deployed `migration-image-config`
    ConfigMap (key `trafficReplayerImage`) — never hardcoded;
  * for SCRAM auth, the SCRAM password is injected by Kubernetes from the same
    Secret the replayer pod uses (`secretKeyRef`), so the password never passes
    through the console or the pod's argv;
  * the static `client.properties` (PEM truststore -> /config/kafka-ca/ca.crt)
    and the cluster CA cert are reused from the workflow's existing
    `<proxy>-kafka-auth` ConfigMap and `<cluster>-cluster-ca-cert` Secret.
"""
import json
import logging
import subprocess
import time
from typing import List, Optional

from console_link.models.command_result import CommandResult
from console_link.models.kafka import Kafka, ScramKafka, MSK

logger = logging.getLogger(__name__)

# Matches the replayer pod's mounts (replayer.ts / setupCapture.ts).
KAFKA_AUTH_CONFIG_MOUNT_PATH = "/config/kafka-auth"
KAFKA_AUTH_CONFIG_FILE_PATH = f"{KAFKA_AUTH_CONFIG_MOUNT_PATH}/client.properties"
KAFKA_CA_MOUNT_PATH = "/config/kafka-ca"

IMAGE_CONFIGMAP_NAME = "migration-image-config"
IMAGE_CONFIGMAP_KEY = "trafficReplayerImage"
PULL_POLICY_CONFIGMAP_KEY = "trafficReplayerPullPolicy"

# Same env var the replayer reads; EnvVarParameterPuller maps
# TRAFFIC_REPLAYER_KAFKA_TRAFFIC_PASSWORD -> --kafkaPassword before the
# dump/replay branch, so it works in dump mode too.
SCRAM_PASSWORD_ENV_VAR = "TRAFFIC_REPLAYER_KAFKA_TRAFFIC_PASSWORD"

DEFAULT_TOPIC = "capture-proxy"
VALID_MODES = ("dump-raw", "dump-http", "dump-both")

DUMP_OPERATION = "Dump Topic Records"


class KafkaDumpError(RuntimeError):
    """Raised when the dump pod cannot be configured or launched."""


def _load_k8s():
    """Import + initialize the k8s client lazily (mirrors environment.py)."""
    from kubernetes import client as k8s_client
    from console_link.workflow.models.utils import load_k8s_config
    load_k8s_config()
    return k8s_client


def _derive_cluster_name(kafka: Kafka) -> Optional[str]:
    """Best-effort recovery of the Strimzi Kafka cluster name.

    For SCRAM the username is `<cluster>-migration-app` (see environment.py),
    which is the most reliable signal. Otherwise fall back to the bootstrap
    host prefix `<cluster>-kafka-bootstrap...`.
    """
    if isinstance(kafka, ScramKafka) and kafka.username.endswith("-migration-app"):
        return kafka.username[: -len("-migration-app")]
    brokers = kafka.brokers or ""
    host = brokers.split(",")[0].split(":")[0]
    marker = "-kafka-bootstrap"
    if marker in host:
        return host.split(marker)[0]
    return None


def _resolve_replayer_image(k8s_client, namespace: str) -> tuple:
    """Read the replayer image + pull policy from the Helm image ConfigMap."""
    core = k8s_client.CoreV1Api()
    try:
        cm = core.read_namespaced_config_map(IMAGE_CONFIGMAP_NAME, namespace)
    except Exception as exc:  # noqa: BLE001
        raise KafkaDumpError(
            f"Could not read ConfigMap '{IMAGE_CONFIGMAP_NAME}' in namespace "
            f"'{namespace}' to resolve the replayer image: {exc}"
        ) from exc
    data = cm.data or {}
    image = data.get(IMAGE_CONFIGMAP_KEY)
    if not image:
        raise KafkaDumpError(
            f"ConfigMap '{IMAGE_CONFIGMAP_NAME}' has no key '{IMAGE_CONFIGMAP_KEY}'"
        )
    pull_policy = data.get(PULL_POLICY_CONFIGMAP_KEY, "IfNotPresent")
    return image, pull_policy


def _discover_kafka_auth_configmap(k8s_client, namespace: str) -> str:
    """Find the workflow's `<proxy>-kafka-auth` ConfigMap (holds the static
    PEM-truststore client.properties the replayer/proxy use for SCRAM).

    The console doesn't own this name, so we discover any ConfigMap whose name
    ends with `-kafka-auth`. If a proxy's config map and a replayer's config
    map both exist they carry identical content, so either works; we pick the
    shortest name (the proxy's `<proxy>-kafka-auth`) deterministically.
    """
    core = k8s_client.CoreV1Api()
    try:
        cms = core.list_namespaced_config_map(namespace)
    except Exception as exc:  # noqa: BLE001
        raise KafkaDumpError(
            f"Could not list ConfigMaps in namespace '{namespace}': {exc}"
        ) from exc
    names = sorted(
        (cm.metadata.name for cm in cms.items
         if cm.metadata and cm.metadata.name and cm.metadata.name.endswith("-kafka-auth")),
        key=lambda n: (len(n), n),
    )
    if not names:
        raise KafkaDumpError(
            f"No '*-kafka-auth' ConfigMap found in namespace '{namespace}'. The dump "
            "command reuses the capture-proxy/replayer Kafka client config, so a "
            "capture proxy or replayer must have been deployed by the workflow."
        )
    logger.info("Using kafka-auth ConfigMap '%s' for the dump pod", names[0])
    return names[0]


def _build_dump_args(
    kafka: Kafka,
    topic: str,
    mode: str,
    start_offset: Optional[int],
    end_offset: Optional[int],
    start_time: Optional[int],
    end_time: Optional[int],
    auth_configmap_mounted: bool,
) -> List[str]:
    """Assemble the TrafficReplayer CLI args for dump mode.

    NOTE: the SCRAM password is intentionally NOT placed here — it is injected
    via the SCRAM_PASSWORD_ENV_VAR environment variable so it never appears in
    the pod spec's argv.
    """
    args = [
        "--mode", mode,
        "--kafka-traffic-brokers", kafka.brokers,
        "--kafka-traffic-topic", topic,
    ]
    if isinstance(kafka, ScramKafka):
        args += ["--kafkaAuthType", "scram-sha-512", "--kafkaUserName", kafka.username]
        if auth_configmap_mounted:
            args += ["--kafka-traffic-property-file", KAFKA_AUTH_CONFIG_FILE_PATH]
    elif isinstance(kafka, MSK):
        args += ["--kafkaAuthType", "msk-iam"]
    # StandardKafka: no auth flags (plaintext listener).

    if start_offset is not None:
        args += ["--start-offset", str(start_offset)]
    if end_offset is not None:
        args += ["--end-offset", str(end_offset)]
    if start_time is not None:
        args += ["--start-time", str(start_time)]
    if end_time is not None:
        args += ["--end-time", str(end_time)]
    return args


def _build_scram_pod_extras(
    is_scram: bool,
    cluster_name: Optional[str],
    mount_auth: bool,
    kafka_auth_configmap: Optional[str],
) -> tuple:
    """Build (env, volumes, volume_mounts) for the dump pod's SCRAM wiring.

    Returns empty lists for non-SCRAM auth. The SCRAM password is injected by k8s
    from the same Secret the replayer uses (secretKeyRef) — never handled by the
    console or placed in argv. The CA cert + client.properties are mounted from the
    workflow's existing kafka-auth ConfigMap / cluster-ca-cert Secret.
    """
    env: List[dict] = []
    volumes: List[dict] = []
    volume_mounts: List[dict] = []
    if not is_scram:
        return env, volumes, volume_mounts

    scram_secret = f"{cluster_name}-migration-app" if cluster_name else None
    if scram_secret:
        env.append({
            "name": SCRAM_PASSWORD_ENV_VAR,
            "valueFrom": {"secretKeyRef": {"name": scram_secret, "key": "password"}},
        })
    if not mount_auth:
        return env, volumes, volume_mounts

    volumes.append({"name": "kafka-auth-config", "configMap": {"name": kafka_auth_configmap}})
    volume_mounts.append({
        "name": "kafka-auth-config",
        "mountPath": KAFKA_AUTH_CONFIG_MOUNT_PATH,
        "readOnly": True,
    })
    ca_secret = f"{cluster_name}-cluster-ca-cert" if cluster_name else None
    if ca_secret:
        volumes.append({"name": "kafka-ca", "secret": {"secretName": ca_secret}})
        volume_mounts.append({
            "name": "kafka-ca",
            "mountPath": KAFKA_CA_MOUNT_PATH,
            "readOnly": True,
        })
    return env, volumes, volume_mounts


def build_dump_pod_manifest(
    kafka: Kafka,
    *,
    pod_name: str,
    namespace: str,
    image: str,
    pull_policy: str,
    cluster_name: Optional[str],
    topic: str,
    mode: str,
    start_offset: Optional[int] = None,
    end_offset: Optional[int] = None,
    start_time: Optional[int] = None,
    end_time: Optional[int] = None,
    kafka_auth_configmap: Optional[str] = None,
) -> dict:
    """Render the one-shot dump Pod manifest as a plain dict (JSON-serializable
    for `kubectl apply -f -`). Pure function — no cluster calls — so it is
    directly unit-testable.
    """
    is_scram = isinstance(kafka, ScramKafka)
    mount_auth = bool(is_scram and kafka_auth_configmap)

    container = {
        "name": "kafka-dump",
        "image": image,
        "imagePullPolicy": pull_policy,
        "args": _build_dump_args(
            kafka, topic, mode,
            start_offset, end_offset, start_time, end_time,
            auth_configmap_mounted=mount_auth,
        ),
    }

    env, volumes, volume_mounts = _build_scram_pod_extras(
        is_scram, cluster_name, mount_auth, kafka_auth_configmap)

    if volume_mounts:
        container["volumeMounts"] = volume_mounts
    if env:
        container["env"] = env

    pod = {
        "apiVersion": "v1",
        "kind": "Pod",
        "metadata": {
            "name": pod_name,
            "namespace": namespace,
            "labels": {
                "app": "kafka-dump",
                "migrations.opensearch.org/task": "kafka-dump",
            },
            # The dump must read to end-of-log uninterrupted; don't let the
            # cluster autoscaler consolidate the node out from under it.
            "annotations": {"karpenter.sh/do-not-disrupt": "true"},
        },
        "spec": {
            "restartPolicy": "Never",
            "containers": [container],
        },
    }
    if volumes:
        pod["spec"]["volumes"] = volumes
    return pod


def _kubectl(args: List[str], namespace: str) -> List[str]:
    return ["kubectl", "-n", namespace] + args


def launch_dump_pod(
    kafka: Kafka,
    *,
    namespace: str = "ma",
    topic: str = DEFAULT_TOPIC,
    mode: str = "dump-both",
    start_offset: Optional[int] = None,
    end_offset: Optional[int] = None,
    start_time: Optional[int] = None,
    end_time: Optional[int] = None,
    pod_timeout_seconds: int = 600,
    pod_name_suffix: Optional[str] = None,
    echo=print,
) -> CommandResult:
    """Launch a one-shot dump pod, stream its logs to `echo`, and delete it.

    Returns a CommandResult whose value is a short summary; the actual record
    dump is streamed live to `echo` (console stdout) as the pod produces it.
    """
    if mode not in VALID_MODES:
        raise KafkaDumpError(f"Invalid mode '{mode}'. Expected one of {VALID_MODES}.")

    k8s_client = _load_k8s()
    image, pull_policy = _resolve_replayer_image(k8s_client, namespace)
    cluster_name = _derive_cluster_name(kafka)

    kafka_auth_configmap = None
    if isinstance(kafka, ScramKafka):
        kafka_auth_configmap = _discover_kafka_auth_configmap(k8s_client, namespace)
        if not cluster_name:
            raise KafkaDumpError(
                "Could not determine the Kafka cluster name for SCRAM auth "
                f"(username='{kafka.username}', brokers='{kafka.brokers}')."
            )

    # Deterministic-but-unique pod name. Math.random/Date are fine here (this
    # is plain runtime code, not a workflow script).
    suffix = pod_name_suffix or str(int(time.time()))
    safe_topic = topic.replace("_", "-").replace(".", "-").lower()[:30]
    pod_name = f"kafka-dump-{safe_topic}-{suffix}"[:63].rstrip("-")

    manifest = build_dump_pod_manifest(
        kafka,
        pod_name=pod_name,
        namespace=namespace,
        image=image,
        pull_policy=pull_policy,
        cluster_name=cluster_name,
        topic=topic,
        mode=mode,
        start_offset=start_offset,
        end_offset=end_offset,
        start_time=start_time,
        end_time=end_time,
        kafka_auth_configmap=kafka_auth_configmap,
    )

    bounds = []
    for label, val in (("start-offset", start_offset), ("end-offset", end_offset),
                       ("start-time", start_time), ("end-time", end_time)):
        if val is not None:
            bounds.append(f"{label}={val}")
    bounds_str = f", {', '.join(bounds)}" if bounds else ""
    echo(f"Launching dump pod '{pod_name}' (mode={mode}, topic={topic}, image={image}{bounds_str})")

    try:
        return _apply_and_stream_dump_pod(
            manifest, pod_name, namespace, topic, mode, pod_timeout_seconds, echo)
    except subprocess.TimeoutExpired:
        return CommandResult(
            success=False,
            value=f"Dump pod '{pod_name}' did not finish within {pod_timeout_seconds}s.",
        )
    finally:
        _delete_dump_pod(pod_name, namespace)


def _apply_and_stream_dump_pod(manifest, pod_name, namespace, topic, mode,
                               pod_timeout_seconds, echo) -> CommandResult:
    """Apply the dump pod, wait for it to start, and stream its logs to `echo`.
    Teardown is the caller's responsibility (finally block)."""
    apply = subprocess.run(
        _kubectl(["apply", "-f", "-"], namespace),
        input=json.dumps(manifest), capture_output=True, text=True,
    )
    if apply.returncode != 0:
        return CommandResult(
            success=False,
            value=f"Failed to create dump pod: {apply.stderr.strip() or apply.stdout.strip()}",
        )

    # Wait for the pod to start (or fail to schedule) before tailing. `kubectl wait`
    # returns non-zero if the pod already completed (Ready never goes true for a
    # short-lived pod). That's fine — fall through to logs, which replay from the start.
    wait = subprocess.run(
        _kubectl(["wait", f"pod/{pod_name}", "--for=condition=Ready",
                  f"--timeout={pod_timeout_seconds}s"], namespace),
        capture_output=True, text=True,
    )
    if wait.returncode != 0:
        logger.debug("kubectl wait returned %d (pod may have already completed): %s",
                     wait.returncode, wait.stderr.strip())

    echo(f"--- begin {topic} dump ({mode}) ---")
    logs = subprocess.run(
        _kubectl(["logs", "-f", pod_name, "--all-containers=true"], namespace),
        capture_output=True, text=True, timeout=pod_timeout_seconds,
    )
    if logs.stdout:
        echo(logs.stdout.rstrip())
    if logs.returncode != 0 and logs.stderr:
        echo(f"(log stream warning: {logs.stderr.strip()})")
    echo(f"--- end {topic} dump ---")
    return CommandResult(success=True, value=f"Dump pod '{pod_name}' completed.")


def _delete_dump_pod(pod_name: str, namespace: str) -> None:
    """Best-effort teardown — also covers Ctrl-C during the log stream."""
    delete = subprocess.run(
        _kubectl(["delete", "pod", pod_name, "--ignore-not-found", "--wait=false"], namespace),
        capture_output=True, text=True,
    )
    if delete.returncode == 0:
        logger.info("Deleted dump pod '%s'", pod_name)
    else:
        logger.warning("Could not delete dump pod '%s': %s", pod_name, delete.stderr.strip())
