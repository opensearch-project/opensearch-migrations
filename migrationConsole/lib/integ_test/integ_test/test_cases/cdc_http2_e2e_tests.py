"""End-to-end HTTP/2 capture-and-replay test for OS 3.x → OS 3.x.

The test exercises the full H2 path on Kubernetes:

  source (OS 3.x, h2-capable) ─h2─► capture-proxy (--enableHttp2)
                                        │
                                        ├─► Kafka (frame observations)
                                        │
                                        └─► upstream (h2 forward)

  Kafka ─► replayer (--targetEnableHttp2) ─h2─► target (OS 3.x, h2-capable)

Acceptance: documents written via H2 to the proxy land on the target via
the H2 dispatch + multiplexed replay path. We verify document counts on
both source and target match what was sent — proving the H2 capture
serialized to v2 records, was decoded by H2Accumulation, converted to H1
objects via H2ToH1ObjectAdapter, transformed by the existing pipeline,
and replayed over a multiplexed H2 connection to the target.
"""
import logging
import uuid

from ..cluster_version import H2_CDC_MIGRATION_COMBINATIONS

from .cdc_base import (
    MATestBase, MigrationType, MATestUserArguments,
    REPLAYER_LABEL_SELECTOR, PROXY_LABEL_SELECTOR,
    wait_for_pod_ready, wait_for_replayer_consuming,
    run_generate_data,
)

logger = logging.getLogger(__name__)


class Test0050CdcHttp2E2eOsToOs(MATestBase):
    """Full H2 capture+replay for OS 3.x → OS 3.x.

    Configures both the capture proxy and the traffic replayer to negotiate
    HTTP/2 via ALPN. Generates documents on the source via the proxy, waits
    for replayer to drain Kafka, asserts documents land on the target.

    Pre-snapshot: generate-data --cluster proxy into idx_pre.
    Post-snapshot: generate-data --cluster proxy into idx_post.

    Pre-snapshot docs are duplicated on target (snapshot backfill + replay
    of the captured PUTs); post-snapshot docs land via replay only.
    """
    requires_explicit_selection = True

    PRE_SNAPSHOT_DOCS = 50
    POST_SNAPSHOT_DOCS = 25

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description=(
                "OS 3.x → OS 3.x CDC over HTTP/2: capture proxy negotiates ALPN h2 "
                "with the source and the replayer multiplexes streams over h2 to the target."
            ),
            migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL,
                                 MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=H2_CDC_MIGRATION_COMBINATIONS,
        )
        uid = f"{self.unique_id}-{uuid.uuid4().hex[:4]}"
        self.idx_pre = f"cdc0050-h2-pre-{uid}"
        self.idx_post = f"cdc0050-h2-post-{uid}"

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = (
            "cdc-full-e2e-imported-clusters" if self.imported_clusters
            else "cdc-e2e-migration-with-clusters"
        )
        # Tell the replayer to negotiate H2 with the target. The H2 dispatch path
        # is gated on -Dreplayer.h2.enabled=true at the JVM level, so the
        # replayer also needs that system property — passed via additionalJavaOpts
        # so the v2 capture format is accepted and the per-session multiplex
        # factory picks h2 when the target ALPN probe returns "h2".
        self.parameters["replayer-config-overrides"] = {
            "targetEnableHttp2": True,
            "additionalJavaOpts": "-Dreplayer.h2.enabled=true",
        }
        # Tell the capture proxy to advertise + capture H2.
        self.parameters["proxy-config-overrides"] = {
            "enableHttp2": True,
        }

    def prepare_clusters(self):
        # Source/target provisioning is handled by the workflow template; data is
        # written via the proxy in workflow_perform_migrations below.
        pass

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        ns = self.argo_service.namespace

        if not self.imported_clusters:
            logger.info("Resuming workflow past pause-for-test-data...")
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)

        logger.info("Waiting for capture-proxy (h2 mode) to be ready...")
        wait_for_pod_ready(ns, PROXY_LABEL_SELECTOR, timeout_seconds)

        logger.info("Pre-snapshot: generating %d docs into %s via H2 proxy",
                    self.PRE_SNAPSHOT_DOCS, self.idx_pre)
        run_generate_data("proxy", self.idx_pre, self.PRE_SNAPSHOT_DOCS)

        logger.info("Waiting for replayer (--targetEnableHttp2) to start...")
        wait_for_pod_ready(ns, REPLAYER_LABEL_SELECTOR, timeout_seconds)
        logger.info("Waiting for replayer to join Kafka consumer group...")
        wait_for_replayer_consuming(namespace=ns)

        logger.info("Post-snapshot: generating %d docs into %s via H2 proxy",
                    self.POST_SNAPSHOT_DOCS, self.idx_post)
        run_generate_data("proxy", self.idx_post, self.POST_SNAPSHOT_DOCS)

        # Confirm documents arrived on source via the proxy.
        self.source_operations.check_doc_counts_match(
            cluster=self.source_cluster,
            expected_index_details={
                self.idx_pre: {"count": self.PRE_SNAPSHOT_DOCS},
                self.idx_post: {"count": self.POST_SNAPSHOT_DOCS},
            },
            max_attempts=10, delay=3.0,
        )

        if not self.imported_clusters:
            logger.info("Waiting for pause-for-migration-verification suspend...")
            self.argo_service.wait_for_suspend(
                workflow_name=self.workflow_name, timeout_seconds=600)

    def post_migration_actions(self):
        pass

    def verify_clusters(self):
        # Pre-snapshot docs land twice on target: once via snapshot/backfill, once
        # via replay (generate-data uses _bulk with auto-IDs so replay creates new
        # docs rather than overwriting). Post-snapshot docs land once (replay only).
        expected_pre = self.PRE_SNAPSHOT_DOCS * 2
        logger.info(
            "Verifying H2 round-trip on target (pre=%d due to snapshot+replay duplication)...",
            expected_pre)
        self.target_operations.check_doc_counts_match(
            cluster=self.target_cluster,
            expected_index_details={
                self.idx_pre: {"count": expected_pre},
                self.idx_post: {"count": self.POST_SNAPSHOT_DOCS},
            },
            max_attempts=120, delay=10.0,
        )
