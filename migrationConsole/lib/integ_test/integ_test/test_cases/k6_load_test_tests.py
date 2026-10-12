"""Load-test integration case (Shape A): a k6 load test layered on a live CDC migration.

Instead of `generate-data`, this drives real load through the capture proxy with a short k6 run (an
Argo Workflow that creates a k6-operator TestRun and waits for it), then asserts the
capture-and-replay pipeline moved that traffic to the target — i.e. the pipeline works *under load*,
not just for a handful of hand-written docs.

Requires the standalone k6LoadTest chart to be installed (operator + per-scenario WorkflowTemplates
+ RBAC) and the migrations/k6_runner image, which carries the pinned runtime, scenarios, and
presets. The test runner installs the chart on its own when a load-test ID (0080-0089)
is selected; the case is explicit-selection only so it never runs in a normal migration suite.
"""

import json
import logging
import math
from pathlib import Path
import subprocess
import time
import uuid

import boto3
from kubernetes import client
from kubernetes.client.rest import ApiException

from console_link.models.cluster import AuthMethod

from ..cluster_version import ElasticsearchV7_X
from .cdc_base import (
    MATestBase, MigrationType, MATestUserArguments,
    CDC_SOURCE_TARGET_COMBINATIONS,
    PROXY_DEPLOYMENT_NAME, REPLAYER_LABEL_SELECTOR,
    wait_for_proxy_ready, wait_for_replayer_consuming, assert_replay_drained,
)

logger = logging.getLogger(__name__)

# The ingest scenario still uses the nyc_taxis schema; each test gets a fresh index name.
K6_INDEX_PREFIX = "k6-nyc-taxis"
K6_INDEX_MAPPING_PATH = (
    Path(__file__).resolve().parents[1]
    / "resources/nyc_taxis_mapping.json"
)
K6_TESTERS = (
    ("pinned", {}),
    ("spread", {"CONNECTION_MODE": "spread"}),
    ("no-reuse", {"CONNECTION_MODE": "spread", "NO_CONNECTION_REUSE": "true"}),
)
K6_TEST_FLEET_READY_FRACTION = 0.9
K6_TEST_FLEET_READY_TIMEOUT_SECONDS = 20 * 60
K6_TEST_FLEET_READY_POLL_SECONDS = 10


def _k6_auth_secret_data(source_cluster, session=None):
    """Build runner-only environment from the source cluster's configured authentication."""
    if source_cluster.auth_type == AuthMethod.NO_AUTH:
        return {}
    if source_cluster.auth_type == AuthMethod.BASIC_AUTH:
        auth = source_cluster.get_basic_auth_details()
        return {
            "K6_AUTH_MODE": "basic",
            "K6_AUTH_USERNAME": auth.username,
            "K6_AUTH_PASSWORD": auth.password,
        }
    if source_cluster.auth_type != AuthMethod.SIGV4:
        raise ValueError(f"Unsupported source authentication: {source_cluster.auth_type}")

    session = session or boto3.Session()
    credentials = session.get_credentials()
    if credentials is None:
        raise RuntimeError("SigV4 source requires AWS credentials for the k6 runner")
    frozen = credentials.get_frozen_credentials()
    details = source_cluster.auth_details or {}
    region = details.get("region") or session.region_name
    if not region:
        raise RuntimeError("SigV4 source requires an AWS region for the k6 runner")

    data = {
        "K6_AUTH_MODE": "sigv4",
        "AWS_ACCESS_KEY_ID": frozen.access_key,
        "AWS_SECRET_ACCESS_KEY": frozen.secret_key,
        "AWS_REGION": region,
        "SIGV4_SERVICE": details.get("service", "es"),
        "SIGV4_SIGNING_ENDPOINT": source_cluster.endpoint,
    }
    if frozen.token:
        data["AWS_SESSION_TOKEN"] = frozen.token
    return data


def _create_k6_auth_secret(namespace, name, data):
    secret = client.V1Secret(
        metadata=client.V1ObjectMeta(name=name, labels={"migration-test": "true"}),
        string_data=data,
        type="Opaque",
    )
    client.CoreV1Api().create_namespaced_secret(namespace=namespace, body=secret)


def _delete_k6_auth_secret(namespace, name):
    try:
        client.CoreV1Api().delete_namespaced_secret(name=name, namespace=namespace)
    except ApiException as e:
        if e.status != 404:
            raise


def _assert_console_cluster_access(expected_index: str) -> None:
    """Require the deployed console configuration to reach both benchmark clusters."""
    command = ["console", "--json", "clusters", "cat-indices"]
    result = subprocess.run(command, capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        raise AssertionError(
            "console clusters cat-indices failed before k6 launch: "
            f"exit_code={result.returncode} stderr={result.stderr.strip()!r}"
        )

    try:
        response = json.loads(result.stdout)
    except json.JSONDecodeError as e:
        raise AssertionError(
            "console clusters cat-indices returned invalid JSON before k6 launch: "
            f"{result.stdout.strip()!r}"
        ) from e

    for cluster_name in ("source_cluster", "target_cluster"):
        indices = response.get(cluster_name)
        if not isinstance(indices, list):
            raise AssertionError(
                "console clusters cat-indices could not read "
                f"{cluster_name}: {indices!r}"
            )
        index_names = {
            item.get("index")
            for item in indices
            if isinstance(item, dict)
        }
        if expected_index not in index_names:
            raise AssertionError(
                "console clusters cat-indices did not find the prepared benchmark "
                f"index {expected_index!r} on {cluster_name}: {sorted(index_names)}"
            )

    logger.info(
        "console clusters cat-indices confirmed benchmark index %s on source and target",
        expected_index,
    )


def _deployment_has_test_capacity(deployment) -> bool:
    """Return whether enough of a Deployment is ready to begin a k6 test."""
    desired = deployment.spec.replicas or 0
    required = max(1, math.ceil(desired * K6_TEST_FLEET_READY_FRACTION))
    return (
        desired > 0
        and (deployment.status.observed_generation or 0) >= (deployment.metadata.generation or 0)
        and (deployment.status.updated_replicas or 0) >= required
        and (deployment.status.ready_replicas or 0) >= required
        and (deployment.status.available_replicas or 0) >= required
    )


def wait_for_k6_test_fleet_capacity(
    namespace: str,
    timeout_seconds: int = K6_TEST_FLEET_READY_TIMEOUT_SECONDS,
) -> None:
    """Test-only gate that waits for most proxy and replayer replicas.

    Production fleet readiness belongs to the managed-fleet design. This
    deliberately narrower check only prevents a k6 test from starting while
    most of its requested load-bearing pods are still unavailable.
    """
    apps = client.AppsV1Api()
    deadline = time.monotonic() + timeout_seconds
    latest_states = {}

    while time.monotonic() < deadline:
        try:
            proxy_deployments = [
                apps.read_namespaced_deployment(PROXY_DEPLOYMENT_NAME, namespace)
            ]
        except ApiException as e:
            if e.status != 404:
                raise
            proxy_deployments = []
        replayer_deployments = apps.list_namespaced_deployment(
            namespace,
            label_selector=REPLAYER_LABEL_SELECTOR,
        ).items

        fleets = {
            "proxy": proxy_deployments,
            "replayer": replayer_deployments,
        }
        latest_states = {
            fleet_name: [
                {
                    "name": deployment.metadata.name,
                    "desired": deployment.spec.replicas or 0,
                    "updated": deployment.status.updated_replicas or 0,
                    "ready": deployment.status.ready_replicas or 0,
                    "available": deployment.status.available_replicas or 0,
                }
                for deployment in deployments
            ]
            for fleet_name, deployments in fleets.items()
        }
        if all(
            deployments
            and all(_deployment_has_test_capacity(deployment) for deployment in deployments)
            for deployments in fleets.values()
        ):
            logger.info(
                "k6 test fleet capacity reached %.0f%% threshold: %s",
                K6_TEST_FLEET_READY_FRACTION * 100,
                latest_states,
            )
            return

        logger.info(
            "Waiting for %.0f%% of proxy and replayer replicas before k6: %s",
            K6_TEST_FLEET_READY_FRACTION * 100,
            latest_states,
        )
        time.sleep(K6_TEST_FLEET_READY_POLL_SECONDS)

    raise TimeoutError(
        f"Proxy and replayer fleets did not reach the "
        f"{K6_TEST_FLEET_READY_FRACTION:.0%} k6 test threshold within "
        f"{timeout_seconds}s: {latest_states}"
    )


class Test0080CdcK6LoadTest(MATestBase):
    """Routine CDC-only check using three low-rate k6 connection testers.

    No snapshot or backfill is configured. Every k6 document reaches the target through live
    capture and replay, making the source/target counts directly comparable.
    """
    requires_explicit_selection = True
    K6_DURATION = "20s"
    K6_RUN_TIMEOUT_SECONDS = 300
    K6_DRAIN_TIMEOUT_SECONDS = 300
    K6_WORKFLOW_PARAMETERS = {
        "replayer-config-overrides": '{"numClientThreads":1}',
    }
    K6_PARAMETERS = {}
    SOURCE_TARGET_COMBINATIONS = CDC_SOURCE_TARGET_COMBINATIONS
    # pinned, spread, no-reuse: (rate, runner pods, preallocated VUs, max VUs)
    K6_LOAD = (("8", 1, "2", "10"), ("1", 1, "1", "5"), ("1", 1, "1", "5"))

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="CDC-only migration driven by a k6 load test through the capture proxy.",
            migrations_required=[MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=self.SOURCE_TARGET_COMBINATIONS,
        )
        self._index_name = f"{K6_INDEX_PREFIX}-{uuid.uuid4().hex[:12]}"
        self._source_count = None

    def _prepare_index_directly(self):
        """Create the benchmark index on both clusters without sending setup traffic through capture."""
        shard_count = int(self.K6_PARAMETERS.get("INDEX_NUMBER_OF_SHARDS", "1"))
        index_definition = json.loads(K6_INDEX_MAPPING_PATH.read_text())
        index_definition.setdefault("settings", {})["number_of_shards"] = shard_count
        serialized_definition = json.dumps(index_definition)

        # Create the target first. If it is unavailable or still resizing, fail before changing the
        # source; a fresh test invocation will use a fresh index name.
        clusters = (
            ("target", self.target_operations, self.target_cluster),
            ("source", self.source_operations, self.source_cluster),
        )
        for label, operations, cluster in clusters:
            logger.info(
                "Creating %s index %s directly with %d primary shards...",
                label,
                self._index_name,
                shard_count,
            )
            operations.create_index(
                index_name=self._index_name,
                cluster=cluster,
                data=serialized_definition,
                max_attempts=1,
            )
            response = operations.get_index(
                index_name=self._index_name,
                cluster=cluster,
                max_attempts=3,
                delay=1.0,
            )
            actual_shards = int(
                response.json()[self._index_name]["settings"]["index"]["number_of_shards"]
            )
            if actual_shards != shard_count:
                raise AssertionError(
                    f"{label} index '{self._index_name}' has {actual_shards} primary shards; "
                    f"expected {shard_count}"
                )

    def prepare_workflow_snapshot_and_migration_config(self):
        self.workflow_snapshot_and_migration_config = []

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = (
            "cdc-only-imported-clusters" if self.imported_clusters
            else "cdc-e2e-migration-with-clusters"
        )
        self.parameters["capture-proxy-service-type"] = self.capture_proxy_service_type
        self.parameters.update(self.K6_WORKFLOW_PARAMETERS)

    def prepare_clusters(self):
        pass

    def _run_k6(self, namespace, target_url, load):
        """Run the pinned, spread, and no-reuse testers at the requested load."""
        # Imported inside the method so the case only depends on the k6 module when actually run.
        from console_link.loadtest.health import HealthWatcher
        from console_link.loadtest.testrun_utils import create_workflow
        from console_link.loadtest.utils import load_k8s_config
        from console_link.loadtest.runs import wait_for_run, SUCCESS_PHASE
        load_k8s_config()
        secret_name = f"k6-auth-{uuid.uuid4().hex[:12]}"
        secret_data = _k6_auth_secret_data(self.source_cluster)
        if secret_data:
            _create_k6_auth_secret(namespace, secret_name, secret_data)

        try:
            runs = []
            for (tester, tester_overrides), (
                rate, parallelism, vus, max_vus,
            ) in zip(K6_TESTERS, load, strict=True):
                runs.append({
                    "name": tester,
                    "rate": rate,
                    "parallelism": str(parallelism),
                    "vus": vus,
                    "maxVus": max_vus,
                    "connectionMode": tester_overrides.get("CONNECTION_MODE", "pinned"),
                    "noConnectionReuse": tester_overrides.get("NO_CONNECTION_REUSE", "false"),
                })
            parameters = {
                "CAPTURE_PROXY_URL": target_url,
                "INDEX_NAME": self._index_name,
                # The integration test provisions identical source and target indices directly,
                # before capture starts. Do not issue even the setup GET through the proxy.
                "PREPARE_INDEX": "false",
                "DURATION": self.K6_DURATION,
                "authSecretName": secret_name,
                "runs": json.dumps(runs, separators=(",", ":")),
                **self.K6_PARAMETERS,
            }
            name = create_workflow(namespace, {
                "apiVersion": "argoproj.io/v1alpha1",
                "kind": "Workflow",
                "metadata": {
                    "generateName": "k6-ingest-connection-modes-",
                    "labels": {
                        "app": "k6-load-test",
                        "k6-scenario": "ingest",
                    },
                },
                "spec": {
                    "workflowTemplateRef": {"name": "k6-ingest-connection-modes"},
                    "arguments": {"parameters": [
                        {"name": key, "value": value} for key, value in parameters.items()
                    ]},
                },
            })
            logger.info("Submitted k6 connection-mode suite %s", name)
            phase = wait_for_run(
                namespace, name, timeout=self.K6_RUN_TIMEOUT_SECONDS, interval=5,
            )
            if phase != SUCCESS_PHASE:
                raise AssertionError(
                    f"k6 connection-mode suite {name} ended in phase '{phase}' "
                    f"(expected '{SUCCESS_PHASE}')"
                )

            for tester, _ in K6_TESTERS:
                run_name = f"{name}-{tester}"
                verdict = HealthWatcher(namespace, run_name).verdict()
                if any((
                    not verdict["known"],
                    verdict["failed_runners"],
                    verdict["thresholds_crossed"],
                )):
                    raise AssertionError(
                        f"k6 tester '{tester}' run {run_name} failed its request checks: {verdict}"
                    )
                logger.info(
                    "k6 tester=%s run=%s finished with verdict %s",
                    tester, run_name, verdict,
                )
        finally:
            if secret_data:
                _delete_k6_auth_secret(namespace, secret_name)

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        ns = self.argo_service.namespace

        self._prepare_index_directly()

        if not self.imported_clusters:
            logger.info("Resuming workflow past cluster setup to start CDC-only capture and replay...")
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)

        logger.info("Waiting for capture-proxy to be ready...")
        proxy_endpoint = wait_for_proxy_ready(
            ns,
            timeout_seconds,
            workflow_name=self.workflow_name,
        )

        logger.info("Waiting for replayer to join Kafka consumer group...")
        wait_for_replayer_consuming(namespace=ns, workflow_name=self.workflow_name)

        wait_for_k6_test_fleet_capacity(ns)
        _assert_console_cluster_access(self._index_name)

        # Drive load through the proxy with k6; the replayer will replay it to the target.
        self._run_k6(ns, proxy_endpoint, self.K6_LOAD)

        # Capture assertion: k6 wrote through the proxy to the source.
        self.source_operations.refresh_index(self._index_name, self.source_cluster)
        details = self.source_operations.get_all_index_details(cluster=self.source_cluster)
        self._source_count = int(details.get(self._index_name, {}).get("count", 0))
        logger.info("Source %s doc count after k6: %d", self._index_name, self._source_count)
        if self._source_count <= 0:
            raise AssertionError(
                f"k6 wrote no docs to source index '{self._index_name}' via the proxy"
            )

        if not self.imported_clusters:
            logger.info("Waiting for workflow to reach the CDC verification suspend...")
            self.argo_service.wait_for_suspend(workflow_name=self.workflow_name, timeout_seconds=600)

    def post_migration_actions(self):
        pass

    def verify_clusters(self):
        logger.info(
            "Verifying %s replayed to target (expect %d)...",
            self._index_name,
            self._source_count,
        )
        assert_replay_drained(
            label="replay-end",
            timeout_seconds=self.K6_DRAIN_TIMEOUT_SECONDS,
        )
        self.target_operations.check_doc_counts_match(
            cluster=self.target_cluster,
            expected_index_details={self._index_name: {"count": self._source_count}},
            max_attempts=120, delay=10.0,
        )


class Test0081CdcK6StressTest(Test0080CdcK6LoadTest):
    """Ten-minute 4-proxy/3-broker/8-partition/4-replayer pod recovery stress."""

    K6_DURATION = "10m"
    K6_RUN_TIMEOUT_SECONDS = 2_400
    K6_DRAIN_TIMEOUT_SECONDS = 1_800
    K6_WORKFLOW_PARAMETERS = {
        **Test0080CdcK6LoadTest.K6_WORKFLOW_PARAMETERS,
        "capture-proxy-pod-replicas": "4",
        "kafka-broker-replicas": "3",
        "traffic-topic-partitions": "8",
        "replayer-pod-replicas": "4",
    }
    K6_PARAMETERS = {
        "RETRY_ENABLED": "true",
        "RETRY_WINDOW_SECONDS": "300",
        "GRACEFUL_STOP": "5m30s",
        "HTTP_REQ_FAILED_THRESHOLD": "rate<1.01",
        "INGEST_ERROR_THRESHOLD": "rate==0",
        "DROPPED_ITERATIONS_THRESHOLD": "count>=0",
        "podChurnEnabled": "true",
        "podChurnTargets": "kafka,replayer,proxy",
        "podChurnExpectedKafkaBrokers": "3",
    }
    K6_LOAD = (
        ("8", 1, "20", "2400"),
        ("1", 1, "5", "300"),
        ("1", 1, "5", "300"),
    )


class Test0082CdcK6HighLoadStressTest(Test0080CdcK6LoadTest):
    """Two-hour 160K-request/s stress run with approximately 2 KiB requests."""

    # The dedicated high-load environment benchmarks an Elasticsearch 7.10
    # source against an Amazon OpenSearch Service domain running the
    # Elasticsearch 7.10 engine. Keep that benchmark-only topology out of the
    # general CDC migration compatibility matrix.
    SOURCE_TARGET_COMBINATIONS = [
        *CDC_SOURCE_TARGET_COMBINATIONS,
        (ElasticsearchV7_X, ElasticsearchV7_X),
    ]
    K6_DURATION = "2h"
    K6_RUN_TIMEOUT_SECONDS = 14_400
    K6_DRAIN_TIMEOUT_SECONDS = 7_200
    K6_WORKFLOW_PARAMETERS = {
        "capture-proxy-pod-replicas": "128",
        "kafka-broker-replicas": "12",
        "kafka-node-pool-spec-overrides": json.dumps(
            {
                "resources": {
                    "requests": {"cpu": "12", "memory": "24Gi"},
                    "limits": {"cpu": "12", "memory": "24Gi"},
                },
                "storage": {
                    "type": "persistent-claim",
                    "size": "1Ti",
                    "deleteClaim": True,
                },
            },
            separators=(",", ":"),
        ),
        "traffic-topic-partitions": "480",
        "replayer-pod-replicas": "162",
        "speedup-factor": "1.5",
        "replayer-config-overrides": (
            '{"readyRequestsBufferPerThread":256,'
            '"resources":{"requests":{"cpu":"3500m","memory":"7000Mi",'
            '"ephemeral-storage":"2Gi"},"limits":{"cpu":"3500m","memory":"7000Mi"}}}'
        ),
    }
    K6_PARAMETERS = {
        # One NYC-taxi bulk item plus 1.3 KiB in its mapped vendor_name field
        # produces an approximately 2 KiB HTTP request without multiplying the
        # target document rate above the requested 160K requests/second.
        "BULK_BATCH_SIZE": "1",
        "DOCUMENT_PADDING_BYTES": "1300",
        # Exercise the full benchmark source and target fleets instead of concentrating
        # the two-hour run on a small fraction of their data nodes.
        "INDEX_NUMBER_OF_SHARDS": "200",
    }
    K6_LOAD = (
        # k6-operator validates parallelism against the initializer's inspected default
        # MaxVUs (100); runner environment overrides are not visible during that check.
        ("123077", 100, "65536", "131072"),
        ("30769", 64, "24576", "49152"),
        ("6154", 32, "8192", "16384"),
    )
