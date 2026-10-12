"""Kafka-side plumbing for the live replay-quality TUI.

Everything here shells out to `kubectl`, the same way 07-live-jaccard-kafka.sh did — this
runs on the operator's machine against whatever cluster their kubeconfig points at, not
inside the migration console pod, so there is no in-cluster Kafka client to reuse.

ensure_ready() does the one-time setup (consumer creds + topic) synchronously so failures
surface before the UI starts polling; stream() is a generator meant to be drained from a
background thread, yielding one decoded tuple dict per Kafka record.
"""
import base64
import json
import logging
import subprocess
from typing import Iterator, Optional

logger = logging.getLogger(__name__)

CONFIG_DIR = "/tmp/jaccard-kafka"
KAFKA_TOOLS = "/root/kafka-tools/kafka"


class KafkaReaderError(Exception):
    pass


def _kubectl(*args: str, input_bytes: Optional[bytes] = None, timeout: Optional[float] = None) -> str:
    proc = subprocess.run(
        ["kubectl", *args], input=input_bytes, capture_output=True, timeout=timeout,
    )
    if proc.returncode != 0:
        raise KafkaReaderError(
            f"kubectl {' '.join(args)} failed: {proc.stderr.decode(errors='replace').strip()}")
    return proc.stdout.decode(errors="replace")


class KafkaTupleReader:
    """Bootstraps consumer access to a Strimzi SASL_SSL topic, then streams it as JSON tuples."""

    def __init__(self, namespace: str, topic: str, *,
                 pod: str = "migration-console-0",
                 bootstrap: str = "default-kafka-bootstrap.ma.svc:9093",
                 kafka_cluster: str = "default",
                 secret_name: str = "default-migration-app",
                 ca_secret: str = "default-cluster-ca-cert",
                 retention_ms: int = 600_000,
                 auto_create_topic: bool = True):
        self.namespace = namespace
        self.topic = topic
        self.pod = pod
        self.bootstrap = bootstrap
        self.kafka_cluster = kafka_cluster
        self.secret_name = secret_name
        self.ca_secret = ca_secret
        self.retention_ms = retention_ms
        self.auto_create_topic = auto_create_topic
        self.errors = 0
        self._proc: Optional[subprocess.Popen] = None

    # --- One-time setup ---

    def ensure_ready(self) -> None:
        """Verify the pod exists, push consumer credentials to it, and make sure the topic is
        there. Raises KafkaReaderError with a message fit to show the user directly."""
        self._check_pod()
        self._push_consumer_config()
        if not self._topic_exists():
            if not self.auto_create_topic:
                raise KafkaReaderError(
                    f"Kafka topic '{self.topic}' does not exist yet. Run the TrafficReplayer "
                    f"with --tuple-kafka-topic {self.topic} first, or create it with a "
                    f"KafkaTopic CR (this Strimzi cluster does not auto-create topics on "
                    f"first produce).")
            self._create_topic()
        self._cap_retention()

    def _check_pod(self) -> None:
        proc = subprocess.run(
            ["kubectl", "get", "pod", self.pod, "-n", self.namespace],
            capture_output=True)
        if proc.returncode != 0:
            raise KafkaReaderError(f"pod '{self.pod}' not found in namespace '{self.namespace}'")

    def _push_consumer_config(self) -> None:
        password = _kubectl(
            "get", "secret", "-n", self.namespace, self.secret_name,
            "-o", "jsonpath={.data.password}").strip()
        if not password:
            raise KafkaReaderError(f"could not read Kafka password from secret '{self.secret_name}'")
        password = base64.b64decode(password).decode()

        ca_cert_b64 = _kubectl(
            "get", "secret", "-n", self.namespace, self.ca_secret,
            "-o", "jsonpath={.data.ca\\.crt}").strip()
        if not ca_cert_b64:
            raise KafkaReaderError(f"could not read CA cert from secret '{self.ca_secret}'")
        ca_cert = base64.b64decode(ca_cert_b64)

        _kubectl("exec", "-n", self.namespace, self.pod, "--",
                 "bash", "-c", f"mkdir -p {CONFIG_DIR}")
        _kubectl("exec", "-i", "-n", self.namespace, self.pod, "--",
                 "bash", "-c", f"cat > {CONFIG_DIR}/ca.crt", input_bytes=ca_cert)

        consumer_properties = (
            "ssl.truststore.type=PEM\n"
            f"ssl.truststore.location={CONFIG_DIR}/ca.crt\n"
            "security.protocol=SASL_SSL\n"
            "sasl.mechanism=SCRAM-SHA-512\n"
            "sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required "
            f'username="{self.secret_name}" password="{password}";\n'
        )
        _kubectl("exec", "-i", "-n", self.namespace, self.pod, "--",
                 "bash", "-c", f"cat > {CONFIG_DIR}/consumer.properties",
                 input_bytes=consumer_properties.encode())

    def _topic_exists(self) -> bool:
        out = _kubectl(
            "exec", "-n", self.namespace, self.pod, "--",
            f"{KAFKA_TOOLS}/bin/kafka-topics.sh",
            "--bootstrap-server", self.bootstrap,
            "--command-config", f"{CONFIG_DIR}/consumer.properties",
            "--list")
        return self.topic in out.splitlines()

    def _create_topic(self) -> None:
        """Create the topic via a KafkaTopic CR. Auto-creation on first produce does not happen
        on this cluster (auto.create.topics.enable is off), so the producer just spins retrying
        UNKNOWN_TOPIC_OR_PARTITION forever without this."""
        manifest = f"""\
apiVersion: kafka.strimzi.io/v1
kind: KafkaTopic
metadata:
  name: {self.topic}
  namespace: {self.namespace}
  labels:
    strimzi.io/cluster: {self.kafka_cluster}
spec:
  partitions: 1
  replicas: 3
  config:
    retention.ms: {self.retention_ms}
    segment.bytes: 1073741824
"""
        _kubectl("apply", "-f", "-", input_bytes=manifest.encode())
        _kubectl("wait", "-n", self.namespace, f"kafkatopic/{self.topic}",
                 "--for=condition=Ready", "--timeout=60s")

    def _cap_retention(self) -> None:
        """Nothing else prunes this topic; keep only the configured window so it never grows
        unbounded across a long-running demo."""
        _kubectl(
            "exec", "-n", self.namespace, self.pod, "--",
            f"{KAFKA_TOOLS}/bin/kafka-configs.sh",
            "--bootstrap-server", self.bootstrap,
            "--command-config", f"{CONFIG_DIR}/consumer.properties",
            "--alter", "--entity-type", "topics", "--entity-name", self.topic,
            "--add-config", f"retention.ms={self.retention_ms}")

    # --- Streaming ---

    def stream(self) -> Iterator[dict]:
        """Yield decoded tuple records as they arrive. Runs until stop() is called or the
        consumer process ends (e.g. its --timeout-ms is reached)."""
        cmd = [
            "kubectl", "exec", "-n", self.namespace, self.pod, "--",
            f"{KAFKA_TOOLS}/bin/kafka-console-consumer.sh",
            "--bootstrap-server", self.bootstrap,
            "--topic", self.topic,
            "--consumer.config", f"{CONFIG_DIR}/consumer.properties",
            "--timeout-ms", "3600000",
        ]
        self._proc = subprocess.Popen(
            cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=1, text=True)
        try:
            for line in self._proc.stdout:
                line = line.strip()
                if not line:
                    continue
                try:
                    yield json.loads(line)
                except Exception:
                    self.errors += 1
                    logger.debug("Failed to decode tuple line: %r", line[:200])
        finally:
            self.stop()

    def stop(self) -> None:
        if self._proc and self._proc.poll() is None:
            self._proc.terminate()
