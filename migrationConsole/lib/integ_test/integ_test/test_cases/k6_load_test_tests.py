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
import uuid

import boto3
from kubernetes import client
from kubernetes.client.rest import ApiException

from console_link.models.cluster import AuthMethod

from .cdc_base import (
    MATestBase, MigrationType, MATestUserArguments,
    CDC_SOURCE_TARGET_COMBINATIONS, PROXY_ENDPOINT,
    wait_for_proxy_ready, wait_for_replayer_consuming, assert_replay_drained,
)

logger = logging.getLogger(__name__)

# The ingest scenario still uses the nyc_taxis schema; each test gets a fresh index name.
K6_INDEX_PREFIX = "k6-nyc-taxis"
K6_TESTERS = (
    ("pinned", {}),
    ("spread", {"CONNECTION_MODE": "spread"}),
    ("no-reuse", {"CONNECTION_MODE": "spread", "NO_CONNECTION_REUSE": "true"}),
)


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
    # pinned, spread, no-reuse: (rate, runner pods, preallocated VUs, max VUs)
    K6_LOAD = (("8", 1, "2", "10"), ("1", 1, "1", "5"), ("1", 1, "1", "5"))

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="CDC-only migration driven by a k6 load test through the capture proxy.",
            migrations_required=[MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=CDC_SOURCE_TARGET_COMBINATIONS,
        )
        self._index_name = f"{K6_INDEX_PREFIX}-{uuid.uuid4().hex[:12]}"
        self._source_count = None

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

        if not self.imported_clusters:
            logger.info("Resuming workflow past cluster setup to start CDC-only capture and replay...")
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)

        logger.info("Waiting for capture-proxy to be ready...")
        wait_for_proxy_ready(ns, timeout_seconds, workflow_name=self.workflow_name)

        logger.info("Waiting for replayer to join Kafka consumer group...")
        wait_for_replayer_consuming(namespace=ns, workflow_name=self.workflow_name)

        # Drive load through the proxy with k6; the replayer will replay it to the target.
        self._run_k6(ns, PROXY_ENDPOINT, self.K6_LOAD)

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


class Test0082CdcK6HighLoadStressTest(Test0081CdcK6StressTest):
    """Two-hour 160K-request/s stress run with approximately 2 KiB requests."""

    K6_DURATION = "2h"
    K6_RUN_TIMEOUT_SECONDS = 14_400
    K6_DRAIN_TIMEOUT_SECONDS = 7_200
    K6_WORKFLOW_PARAMETERS = {
        "capture-proxy-pod-replicas": "128",
        "kafka-broker-replicas": "12",
        "traffic-topic-partitions": "480",
        "replayer-pod-replicas": "320",
        "replayer-config-overrides": (
            '{"maxConcurrentRequests":64,"numClientThreads":4}'
        ),
    }
    K6_PARAMETERS = {
        **Test0081CdcK6StressTest.K6_PARAMETERS,
        # One NYC-taxi bulk item plus 1.3 KiB in its mapped vendor_name field
        # produces an approximately 2 KiB HTTP request without multiplying the
        # target document rate above the requested 160K requests/second.
        "BULK_BATCH_SIZE": "1",
        "DOCUMENT_PADDING_BYTES": "1300",
        # Spread this billion-document test across the six-node source cluster instead of
        # inheriting the cluster's one-primary default.
        "INDEX_NUMBER_OF_SHARDS": "24",
        # Sequences issue four HTTP requests per scheduled iteration. Disable
        # them here so the configured arrival rate is the actual request rate.
        "SEQUENCE_FRACTION": "0",
        "podChurnExpectedKafkaBrokers": "12",
        # Offered high-load arrivals are observational; accepted writes must still all succeed.
        "DROPPED_ITERATIONS_THRESHOLD": "count>=0",
    }
    K6_LOAD = (
        # k6-operator validates parallelism against the initializer's inspected default
        # MaxVUs (100); runner environment overrides are not visible during that check.
        ("123077", 100, "65536", "131072"),
        ("30769", 64, "24576", "49152"),
        ("6154", 32, "8192", "16384"),
    )
