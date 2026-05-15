"""CDC tests targeting Amazon OpenSearch Serverless (AOSS) collections.

Test0034: CDC-only (proxy + replayer) with AOSS search collection target.
Test0041: Full E2E (CDC + RFS) with AOSS search collection target.

These tests deploy an ES 7.10 source cluster (via CDK) and an AOSS collection
(via CDK). Unlike the existing AOSS tests (0021-0023) which use BYOS snapshots
and no source cluster, these tests deploy a live source and route traffic
through the capture proxy.

Required environment variables (injected by Jenkins pipeline):
- AOSS_CDC_ENDPOINT: AOSS search collection endpoint
"""
import logging
import os
import time
import uuid

from console_link.models.cluster import Cluster

from .cdc_base import (
    MATestBase, MigrationType, MATestUserArguments,
    REPLAYER_LABEL_SELECTOR, PROXY_LABEL_SELECTOR,
    wait_for_pod_ready, wait_for_replayer_consuming,
    run_generate_data,
)

logger = logging.getLogger(__name__)

AOSS_CDC_ENDPOINT_ENV = "AOSS_CDC_ENDPOINT"


def _make_aoss_target_cluster():
    """Create a Cluster pointing at the AOSS endpoint with sigv4/aoss auth."""
    endpoint = os.environ.get(AOSS_CDC_ENDPOINT_ENV)
    if not endpoint:
        raise ValueError(
            f"{AOSS_CDC_ENDPOINT_ENV} environment variable is required. "
            f"Ensure the pipeline injects it via 'kubectl set env'."
        )
    region = os.environ.get('AWS_DEFAULT_REGION', 'us-east-1')
    return Cluster(config={
        "endpoint": endpoint,
        "allow_insecure": False,
        "sigv4": {"region": region, "service": "aoss"}
    })


class Test0034CdcOnlyAossTarget(MATestBase):
    """CDC-only test with AOSS search collection as target.

    Sends docs through capture proxy → Kafka → replayer → AOSS.
    Validates the replayer can authenticate to AOSS via SigV4 (service=aoss)
    and that documents appear in the AOSS collection.
    """
    requires_explicit_selection = True

    CDC_NUM_DOCS = 10

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="CDC-only with AOSS search collection target.",
            migrations_required=[MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=[],
        )
        self.cdc_index = f"cdc0034-aoss-{self.unique_id}-{uuid.uuid4().hex[:4]}"

    def import_existing_clusters(self):
        self.target_cluster = _make_aoss_target_cluster()
        # Source cluster imported from configmap (deployed by CDK as ES 7.10)
        source_cluster = self.argo_service.get_cluster_from_configmap(
            f"source-{self.source_version.full_cluster_type}-"
            f"{self.source_version.major_version}-{self.source_version.minor_version}")
        if not source_cluster:
            raise ValueError("Source cluster configmap not found. Ensure CDK deployed the source cluster.")
        self.source_cluster = source_cluster
        self.imported_clusters = True
        logger.info("AOSS target: %s", self.target_cluster.endpoint)
        logger.info("Source: %s", self.source_cluster.endpoint)

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = "cdc-only-imported-clusters"

    def prepare_clusters(self):
        pass

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        logger.info("Waiting for replayer to start...")
        wait_for_pod_ready(self.argo_service.namespace, REPLAYER_LABEL_SELECTOR, timeout_seconds)
        logger.info("Replayer is running")

    def post_migration_actions(self):
        logger.info("Waiting for replayer to join Kafka consumer group...")
        wait_for_replayer_consuming(namespace=self.argo_service.namespace)

        logger.info("Generating %d docs via proxy for AOSS target...", self.CDC_NUM_DOCS)
        run_generate_data("proxy", self.cdc_index, self.CDC_NUM_DOCS)

        self.source_operations.check_doc_counts_match(
            cluster=self.source_cluster,
            expected_index_details={self.cdc_index: {"count": self.CDC_NUM_DOCS}},
            max_attempts=5, delay=2.0,
        )

    def verify_clusters(self):
        logger.info("Verifying docs on AOSS target...")
        count = 0
        for attempt in range(30):
            try:
                resp = self.target_cluster.call_api(f"/{self.cdc_index}/_count", raise_error=False)
                if resp.status_code == 200:
                    count = resp.json().get("count", 0)
                    if count == self.CDC_NUM_DOCS:
                        logger.info("AOSS target: %s has %d docs ✓", self.cdc_index, count)
                        return
                logger.info("Attempt %d: %s status=%d, count=%d/%d",
                            attempt + 1, self.cdc_index, resp.status_code, count, self.CDC_NUM_DOCS)
            except Exception as e:
                logger.info("Attempt %d: %s", attempt + 1, e)
            time.sleep(10)
        raise AssertionError(f"{self.cdc_index}: expected {self.CDC_NUM_DOCS} docs, got {count}")


class Test0041CdcFullE2eAossTarget(MATestBase):
    """Full E2E (CDC + RFS) with AOSS search collection as target.

    Pre-snapshot: bulk docs into idx_pre through proxy (captured to snapshot + Kafka).
    Post-snapshot: bulk docs into idx_post through proxy (Kafka only).
    RFS backfills idx_pre from snapshot. Replayer replays all traffic to AOSS.
    """
    requires_explicit_selection = True

    PRE_SNAPSHOT_DOCS = 50
    POST_SNAPSHOT_DOCS = 25

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="Full E2E (CDC + RFS) with AOSS search collection target.",
            migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL,
                                 MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=[],
        )
        uid = f"{self.unique_id}-{uuid.uuid4().hex[:4]}"
        self.idx_pre = f"cdc0041-pre-{uid}"
        self.idx_post = f"cdc0041-post-{uid}"

    def import_existing_clusters(self):
        self.target_cluster = _make_aoss_target_cluster()
        source_cluster = self.argo_service.get_cluster_from_configmap(
            f"source-{self.source_version.full_cluster_type}-"
            f"{self.source_version.major_version}-{self.source_version.minor_version}")
        if not source_cluster:
            raise ValueError("Source cluster configmap not found. Ensure CDK deployed the source cluster.")
        self.source_cluster = source_cluster
        self.imported_clusters = True
        logger.info("AOSS target: %s", self.target_cluster.endpoint)
        logger.info("Source: %s", self.source_cluster.endpoint)

    def prepare_workflow_snapshot_and_migration_config(self):
        """Scope metadata + backfill to this test's own indices."""
        allowlist = [self.idx_pre, self.idx_post]
        self.workflow_snapshot_and_migration_config = [{
            "migrations": [{
                "metadataMigrationConfig": {"indexAllowlist": allowlist},
                "documentBackfillConfig": {"indexAllowlist": allowlist},
            }]
        }]

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = "cdc-full-e2e-imported-clusters"

    def prepare_clusters(self):
        pass

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        ns = self.argo_service.namespace

        # --- Pre-snapshot: generate-data via proxy ---
        logger.info("Waiting for capture-proxy to be ready...")
        wait_for_pod_ready(ns, PROXY_LABEL_SELECTOR, timeout_seconds)

        logger.info("Pre-snapshot: generating %d docs into %s via proxy", self.PRE_SNAPSHOT_DOCS, self.idx_pre)
        run_generate_data("proxy", self.idx_pre, self.PRE_SNAPSHOT_DOCS)

        # --- Wait for replayer (signals backfill done) ---
        logger.info("Waiting for replayer to start...")
        wait_for_pod_ready(ns, REPLAYER_LABEL_SELECTOR, timeout_seconds)
        logger.info("Waiting for replayer to join Kafka consumer group...")
        wait_for_replayer_consuming(namespace=ns)

        # --- Post-snapshot: bulk into idx_post through proxy ---
        logger.info("Post-snapshot: generating %d docs into %s via proxy", self.POST_SNAPSHOT_DOCS, self.idx_post)
        run_generate_data("proxy", self.idx_post, self.POST_SNAPSHOT_DOCS)

        # Verify on source
        self.source_operations.check_doc_counts_match(
            cluster=self.source_cluster,
            expected_index_details={
                self.idx_pre: {"count": self.PRE_SNAPSHOT_DOCS},
                self.idx_post: {"count": self.POST_SNAPSHOT_DOCS},
            },
            max_attempts=10, delay=3.0,
        )

    def post_migration_actions(self):
        pass

    def verify_clusters(self):
        # Pre-snapshot docs appear twice: once from RFS backfill, once from replayer.
        # generate-data uses auto-generated IDs, so replayed docs don't overwrite backfilled ones.
        expected_pre = self.PRE_SNAPSHOT_DOCS * 2
        logger.info("Verifying both indices on AOSS target...")
        pre, post = 0, 0
        for attempt in range(30):
            try:
                resp_pre = self.target_cluster.call_api(f"/{self.idx_pre}/_count", raise_error=False)
                resp_post = self.target_cluster.call_api(f"/{self.idx_post}/_count", raise_error=False)
                if resp_pre.status_code == 200:
                    pre = resp_pre.json().get("count", 0)
                if resp_post.status_code == 200:
                    post = resp_post.json().get("count", 0)
                if pre == expected_pre and post == self.POST_SNAPSHOT_DOCS:
                    logger.info("AOSS target: %s=%d, %s=%d ✓", self.idx_pre, pre, self.idx_post, post)
                    return
                logger.info("Attempt %d: %s=%d/%d (status=%d), %s=%d/%d (status=%d)", attempt + 1,
                            self.idx_pre, pre, expected_pre, resp_pre.status_code,
                            self.idx_post, post, self.POST_SNAPSHOT_DOCS, resp_post.status_code)
            except Exception as e:
                logger.info("Attempt %d: %s", attempt + 1, e)
            time.sleep(10)
        raise AssertionError(
            f"Expected {self.idx_pre}={expected_pre}, {self.idx_post}={self.POST_SNAPSHOT_DOCS}, "
            f"got {self.idx_pre}={pre}, {self.idx_post}={post}")
