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

# The existing ingest scenario writes the nyc_taxis schema to this index by default.
K6_INDEX = "nyc_taxis"
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
    """Routine CDC check using three low-rate k6 connection testers.

    k6 runs in the replay-only (post-snapshot) phase so its docs reach the target via replay only
    (no snapshot/backfill duplication), making the source/target counts directly comparable.
    """
    requires_explicit_selection = True
    K6_DURATION = "20s"
    K6_RUN_TIMEOUT_SECONDS = 300
    K6_DRAIN_TIMEOUT_SECONDS = 300
    K6_WORKFLOW_PARAMETERS = {}
    # pinned, spread, no-reuse: (rate, runner pods, preallocated VUs, max VUs)
    K6_LOAD = (("8", 1, "2", "10"), ("1", 1, "1", "5"), ("1", 1, "1", "5"))

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="Full E2E CDC driven by a k6 load test through the capture proxy.",
            migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL,
                                 MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=CDC_SOURCE_TARGET_COMBINATIONS,
        )
        self._uid = f"{self.unique_id}-{uuid.uuid4().hex[:4]}"
        self._source_count = None

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = (
            "cdc-full-e2e-imported-clusters" if self.imported_clusters
            else "cdc-e2e-migration-with-clusters"
        )
        self.parameters["pre-snapshot-proxy-submit"] = "true"
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
                "INDEX_NAME": K6_INDEX,
                "DURATION": self.K6_DURATION,
                "authSecretName": secret_name,
                "runs": json.dumps(runs, separators=(",", ":")),
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
            logger.info("Resuming workflow past pause-for-test-data to start proxy-only capture...")
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)

        logger.info("Waiting for capture-proxy to be ready...")
        wait_for_proxy_ready(ns, timeout_seconds, workflow_name=self.workflow_name)

        # Advance to the full migration (snapshot + backfill + replay). No pre-snapshot load, so
        # everything k6 writes next reaches the target via replay only.
        logger.info("Waiting for workflow to pause before full migration submit...")
        self.argo_service.wait_for_suspend(workflow_name=self.workflow_name, timeout_seconds=600)
        logger.info("Resuming workflow to submit full migration...")
        self.argo_service.resume_workflow(workflow_name=self.workflow_name)

        logger.info("Waiting for replayer to join Kafka consumer group...")
        wait_for_replayer_consuming(namespace=ns, workflow_name=self.workflow_name)

        # Drive load through the proxy with k6; the replayer will replay it to the target.
        self._run_k6(ns, PROXY_ENDPOINT, self.K6_LOAD)

        # Capture assertion: k6 wrote through the proxy to the source.
        self.source_operations.refresh_index(K6_INDEX, self.source_cluster)
        details = self.source_operations.get_all_index_details(cluster=self.source_cluster)
        self._source_count = int(details.get(K6_INDEX, {}).get("count", 0))
        logger.info("Source %s doc count after k6: %d", K6_INDEX, self._source_count)
        if self._source_count <= 0:
            raise AssertionError(f"k6 wrote no docs to source index '{K6_INDEX}' via the proxy")

        if not self.imported_clusters:
            logger.info("Waiting for workflow to reach pause-for-migration-verification suspend...")
            self.argo_service.wait_for_suspend(workflow_name=self.workflow_name, timeout_seconds=600)

    def post_migration_actions(self):
        pass

    def verify_clusters(self):
        # Replay assertion: the k6-generated load was captured and replayed to the target.
        # This suite gives every bulk item an explicit distributed ID. At-least-once redelivery
        # therefore overwrites the same target document, so replay-only must reproduce the source
        # count without requiring replay deduplication.
        logger.info("Verifying %s replayed to target (expect %d)...", K6_INDEX, self._source_count)
        try:
            self.target_operations.check_doc_counts_match(
                cluster=self.target_cluster,
                expected_index_details={K6_INDEX: {"count": self._source_count}},
                max_attempts=120, delay=10.0,
            )
        finally:
            assert_replay_drained(
                label="replay-end",
                timeout_seconds=self.K6_DRAIN_TIMEOUT_SECONDS,
            )


class Test0081CdcK6ScaleTest(Test0080CdcK6LoadTest):
    """Costly CDC scale check using the same three-tester k6 path as Test0080."""

    K6_DURATION = "2m"
    K6_RUN_TIMEOUT_SECONDS = 1_200
    K6_DRAIN_TIMEOUT_SECONDS = 3_600
    K6_WORKFLOW_PARAMETERS = {
        "capture-proxy-pod-replicas": "10",
        "traffic-topic-partitions": "20",
        "replayer-pod-replicas": "10",
    }
    K6_LOAD = (
        ("400", 8, "400", "1200"),
        ("100", 4, "120", "400"),
        ("20", 2, "40", "120"),
    )
