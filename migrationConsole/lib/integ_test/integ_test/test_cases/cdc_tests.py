import base64
import json
import logging
import subprocess
import time
from http import HTTPStatus

from kubernetes import client, config as k8s_config, watch

from console_link.models.cluster import Cluster

from ..cluster_version import (
    ElasticsearchV7_X,
    OpensearchV1_X, OpensearchV2_X, OpensearchV3_X,
)
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)

PROXY_DEPLOYMENT_NAME = "capture-proxy"
KAFKA_CLUSTER_NAME = "default"
KAFKA_POD_NAME = f"{KAFKA_CLUSTER_NAME}-dual-role-0"
KAFKA_BOOTSTRAP = f"{KAFKA_CLUSTER_NAME}-kafka-bootstrap:9092"
KAFKA_TOPIC_NAME = "capture-proxy"
TARGET_LABEL = "target1"
REPLAYER_CONSUMER_GROUP = f"replayer-{TARGET_LABEL}"
REPLAYER_LABEL_SELECTOR = "app=replayer"
BACKFILL_NUM_DOCS = 10
CDC_NUM_DOCS = 10


def _load_k8s_config():
    """Load K8s config once. Safe to call multiple times as it is idempotent."""
    try:
        k8s_config.load_incluster_config()
    except k8s_config.ConfigException:
        k8s_config.load_kube_config()


def _log_cdc_diagnostics(namespace: str):
    """Log Kafka consumer lag and replayer heartbeat for debugging CDC failures.
    Uses kubectl subprocess for log tailing (no Python K8s client equivalent).
    """
    try:
        result = subprocess.run(
            ["kubectl", "exec", KAFKA_POD_NAME, "-n", namespace, "--",
             "bin/kafka-consumer-groups.sh", "--bootstrap-server", "localhost:9092",
             "--describe", "--group", REPLAYER_CONSUMER_GROUP],
            capture_output=True, text=True, timeout=30
        )
        logger.info("Kafka consumer group status:\n%s", result.stdout.strip())
    except Exception as e:
        logger.warning("Could not check Kafka consumer group: %s", e)
    try:
        result = subprocess.run(
            ["kubectl", "logs", "-l", REPLAYER_LABEL_SELECTOR, "-n", namespace, "--tail=1"],
            capture_output=True, text=True, timeout=10
        )
        logger.info("Replayer heartbeat: %s", result.stdout.strip())
    except Exception as e:
        logger.warning("Could not check replayer heartbeat: %s", e)


class Test0030CdcDataMigrationThenLiveTraffic(MATestBase):
    """CDC pipeline test: backfill + live traffic capture/replay via proxy.

    The inner migration-workflow runs the replayer indefinitely. Instead of waiting
    for the workflow to end, we wait for the replayer pod to start (backfill done),
    send CDC traffic, verify doc counts, and return. The pytest teardown fixture
    in ma_workflow_test.py handles stopping/deleting the workflow.
    """
    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="Data migration then live traffic capture and replay via proxy.",
            migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL,
                                 MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=[
                (ElasticsearchV7_X, OpensearchV1_X),
                (ElasticsearchV7_X, OpensearchV2_X),
                (ElasticsearchV7_X, OpensearchV3_X),
            ],
        )
        self.backfill_index = f"cdc0030-backfill-{self.unique_id}"
        self.cdc_index = f"cdc0030-captureproxy-{self.unique_id}"

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = "cdc-migration-imported-clusters"

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        """Wait for replayer pod to be Running instead of waiting for workflow end."""
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        logger.info("Waiting for replayer to start (signals backfill completion)...")
        self._wait_for_replayer_running(timeout_seconds=timeout_seconds)
        logger.info("Replayer is running, ready for CDC traffic")

    def prepare_clusters(self):
        logger.info("Creating %d documents in '%s' on source...", BACKFILL_NUM_DOCS, self.backfill_index)
        for i in range(BACKFILL_NUM_DOCS):
            self.source_operations.create_document(
                cluster=self.source_cluster,
                index_name=self.backfill_index,
                doc_id=f"backfill_doc_{i}",
            )
        self.source_operations.get_document(
            cluster=self.source_cluster,
            index_name=self.backfill_index,
            doc_id="backfill_doc_0",
        )

    def post_migration_actions(self):
        logger.info("Enabling capture mode on proxy...")
        self._patch_proxy_capture_mode()

        proxy_config = {**self.source_cluster.config, "endpoint": "http://capture-proxy:9201"}
        proxy_cluster = Cluster(config=proxy_config)
        logger.info("Creating %d CDC documents through proxy...", CDC_NUM_DOCS)
        for i in range(CDC_NUM_DOCS):
            self.source_operations.create_document(
                cluster=proxy_cluster,
                index_name=self.cdc_index,
                doc_id=f"cdc_doc_{i}",
                expected_status_code=HTTPStatus.CREATED,
            )

        # Verify CDC docs exist on source (catches proxy misconfiguration)
        self.source_operations.check_doc_counts_match(
            cluster=self.source_cluster,
            expected_index_details={self.cdc_index: {"count": CDC_NUM_DOCS}},
            max_attempts=5,
            delay=2.0,
        )
        logger.info("Verified %d CDC docs on source cluster", CDC_NUM_DOCS)

        _log_cdc_diagnostics(self.argo_service.namespace)

    def verify_clusters(self):
        logger.info("Verifying backfill docs on target...")
        self.target_operations.check_doc_counts_match(
            cluster=self.target_cluster,
            expected_index_details={self.backfill_index: {"count": BACKFILL_NUM_DOCS}},
            max_attempts=30,
            delay=10.0,
        )
        logger.info("Verifying CDC docs on target...")
        self.target_operations.check_doc_counts_match(
            cluster=self.target_cluster,
            expected_index_details={self.cdc_index: {"count": CDC_NUM_DOCS}},
            max_attempts=120,
            delay=10.0,
        )

    def test_after(self):
        # Always log CDC diagnostics for debugging, regardless of pass/fail.
        _log_cdc_diagnostics(self.argo_service.namespace)
        # CDC workflow may still be running (replayer runs indefinitely).
        # Success is determined by verify_clusters(), not workflow phase.

    # --- Private helpers ---

    def _patch_proxy_capture_mode(self):
        """Patch capture-proxy deployment to enable traffic capture to Kafka."""
        namespace = self.argo_service.namespace
        _load_k8s_config()

        apps_v1 = client.AppsV1Api()
        dep = apps_v1.read_namespaced_deployment(PROXY_DEPLOYMENT_NAME, namespace)

        container = dep.spec.template.spec.containers[0]
        patched = False
        for i, arg in enumerate(container.args):
            if arg == "---INLINE-JSON" and i + 1 < len(container.args):
                proxy_config = json.loads(base64.b64decode(container.args[i + 1]))
                proxy_config["noCapture"] = False
                proxy_config["kafkaConnection"] = KAFKA_BOOTSTRAP
                container.args[i + 1] = base64.b64encode(json.dumps(proxy_config).encode()).decode()
                patched = True
                break
        if not patched:
            raise RuntimeError("Could not find ---INLINE-JSON arg in capture-proxy deployment")

        apps_v1.patch_namespaced_deployment(PROXY_DEPLOYMENT_NAME, namespace, dep)
        logger.info("Patched capture-proxy: noCapture=false, kafkaConnection=%s", KAFKA_BOOTSTRAP)

        # Wait for the deployment controller to observe the new spec
        for _ in range(30):
            dep = apps_v1.read_namespaced_deployment(PROXY_DEPLOYMENT_NAME, namespace)
            if (dep.status.observed_generation or 0) >= (dep.metadata.generation or 0):
                break
            time.sleep(1)

        # Check if rollout already completed (new pod is ready)
        dep = apps_v1.read_namespaced_deployment(PROXY_DEPLOYMENT_NAME, namespace)
        ready = dep.status.ready_replicas or 0
        desired = dep.spec.replicas or 1
        updated = dep.status.updated_replicas or 0
        unavailable = dep.status.unavailable_replicas or 0
        if ready >= desired and updated >= desired and unavailable == 0:
            logger.info("Capture-proxy rollout complete (%d/%d ready)", ready, desired)
            return

        # Use K8s Watch API for rollout
        w = watch.Watch()
        for event in w.stream(apps_v1.list_namespaced_deployment,
                              namespace=namespace,
                              field_selector=f"metadata.name={PROXY_DEPLOYMENT_NAME}",
                              timeout_seconds=150):
            dep = event["object"]
            ready = dep.status.ready_replicas or 0
            desired = dep.spec.replicas or 1
            updated = dep.status.updated_replicas or 0
            unavailable = dep.status.unavailable_replicas or 0
            if ready >= desired and updated >= desired and unavailable == 0:
                logger.info("Capture-proxy rollout complete (%d/%d ready)", ready, desired)
                w.stop()
                return
            logger.debug("Waiting for proxy rollout: %d/%d ready, %d updated, %d unavailable",
                         ready, desired, updated, unavailable)
        raise RuntimeError("Capture-proxy rollout did not complete within 150s")

    def _wait_for_replayer_running(self, timeout_seconds: int = 1200):
        """Wait until a replayer pod is Running and ready, signaling backfill is done.

        Uses the K8s Watch API for event-driven detection instead of polling.
        User-facing equivalent: kubectl wait --for=condition=Ready pod -l app=replayer -n <ns> --timeout=1200s
        """
        namespace = self.argo_service.namespace
        _load_k8s_config()

        v1 = client.CoreV1Api()
        label_selector = REPLAYER_LABEL_SELECTOR

        logger.info("Waiting for replayer pod to be Ready (timeout=%ds)...", timeout_seconds)

        # Quick check if a replayer pod is already Ready
        pods = v1.list_namespaced_pod(namespace, label_selector=label_selector)
        for pod in pods.items:
            if self._is_pod_ready(pod):
                logger.info("Replayer pod %s is already Running and Ready", pod.metadata.name)
                return

        # Watch for pod events until one becomes Ready
        w = watch.Watch()
        for event in w.stream(v1.list_namespaced_pod,
                              namespace=namespace,
                              label_selector=label_selector,
                              timeout_seconds=timeout_seconds):
            pod = event["object"]
            phase = pod.status.phase or "Unknown"
            name = pod.metadata.name
            event_type = event["type"]

            if self._is_pod_ready(pod):
                logger.info("Replayer pod %s is Running and Ready", name)
                w.stop()
                return

            logger.debug("Replayer event %s pod=%s phase=%s ready=%s",
                         event_type, name, phase, self._is_pod_ready(pod))

        raise TimeoutError(f"No replayer pod reached Running+Ready state within {timeout_seconds}s")

    @staticmethod
    def _is_pod_ready(pod) -> bool:
        """Check if a pod has phase=Running and condition Ready=True."""
        if pod.status.phase != "Running":
            return False
        for condition in (pod.status.conditions or []):
            if condition.type == "Ready" and condition.status == "True":
                return True
        return False
