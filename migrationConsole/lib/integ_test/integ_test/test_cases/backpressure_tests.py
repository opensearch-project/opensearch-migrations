import importlib.util
import json
import logging
import os
import re
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

import requests
from kubernetes import client

from console_link.workflow.commands.crd_utils import CRD_GROUP, CRD_VERSION
from ..cluster_version import ElasticsearchV7_X, OpensearchV2_X
from .basic_tests import DEFAULT_S3_CONFIG_MAP, SNAPSHOT_MIGRATION_PLURAL, run_console_json
from .cdc_base import load_k8s_config
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)

STARVED_TARGET_TEMPLATE = "opensearch-2-19-starved-single-node"
RFS_PODS = 3
BULK_BYTES = 1024 * 1024
DOC_SIZE_BYTES = 1000
# One shard per index, so one RFS work item each. The two small shards keep three workers contending for the
# target; the big one outlasts them by more than the in-flight limit's 5-minute quiet period, so the last worker
# runs alone long enough to reach --max-connections and reset its threshold.
INDEX_DOCS = {"big": 1_700_000, "small-a": 175_000, "small-b": 175_000}
PROMETHEUS_URL = "http://kube-prometheus-stack-prometheus.{namespace}.svc:9090"
SHARED_LOGS = "/shared-logs-output"
GENERATOR_PATH = "/root/testDocumentGenerator.py"


def _bulk_insert_data():
    spec = importlib.util.spec_from_file_location("testDocumentGenerator", GENERATOR_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.bulk_insert_data


class Test0011RfsBackpressure(MATestBase):
    """Backfills into a target that can't keep up, and checks that RFS backs off instead of failing.

    The target is one OpenSearch node with one CPU, one write thread and a write queue of 10
    (opensearch-2-19-starved-single-node), so it rejects bulks beyond about 11 in flight. Three RFS workers with the
    default --max-connections (10) can send 30. They must be throttled back to what the target accepts and migrate
    every document, with no batch giving up and nothing in the failed document stream. Then, from Prometheus and the
    workers' logs: the target pushed back and the workers cut their limits, the last worker grew to the maximum and
    reset its threshold after 5 quiet minutes, the retry budget never ran out, and the bulk size metrics and the
    per-minute flow heartbeat were emitted. About 10 minutes of data movement after ~10 minutes of setup.
    """
    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="Backfill into a starved target: workers throttle back and lose nothing.",
            migrations_required=[MigrationType.BACKFILL],
            allow_source_target_combinations=[(ElasticsearchV7_X, OpensearchV2_X)],
        )
        suffix = f"{self.unique_id}-{uuid.uuid4().hex[:4]}"
        self.indices = {key: f"test-0011-{key}-{suffix}" for key in INDEX_DOCS}
        self.target_argo_cluster_template = STARVED_TARGET_TEMPLATE
        self.migration_start = None
        self.migration_end = None

    def prepare_workflow_snapshot_and_migration_config(self):
        allowlist = list(self.indices.values())
        self.workflow_snapshot_and_migration_config = [{
            "migrations": [{
                "metadataMigrationConfig": {"indexAllowlist": allowlist},
                "documentBackfillConfig": {
                    "indexAllowlist": allowlist,
                    "podReplicas": RFS_PODS,
                    # Smaller than the 10 MiB default so a contended round trip takes seconds, not tens of seconds,
                    # and the in-flight limit gets many rounds in a few minutes.
                    "documentsSizePerBulkRequest": BULK_BYTES,
                    "failedDocumentStreamS3Bucket": self._deployment_default_s3_bucket(),
                    "resources": {
                        "requests": {"cpu": "500m", "memory": "1Gi", "ephemeral-storage": "5Gi"},
                        "limits": {"cpu": "1000m", "memory": "2Gi", "ephemeral-storage": "5Gi"},
                    },
                },
            }]
        }]

    def _deployment_default_s3_bucket(self) -> str:
        load_k8s_config()
        config_map = client.CoreV1Api().read_namespaced_config_map(
            name=DEFAULT_S3_CONFIG_MAP, namespace=self.argo_service.namespace)
        bucket = ((config_map.data or {}).get("BUCKET_NAME") or "").strip()
        if not bucket:
            raise AssertionError(f"ConfigMap {DEFAULT_S3_CONFIG_MAP} has no BUCKET_NAME: {config_map.data}")
        return bucket

    def prepare_clusters(self):
        for index in self.indices.values():
            self.source_operations.create_index(
                index_name=index, cluster=self.source_cluster,
                data=json.dumps({"settings": {"number_of_shards": 1, "number_of_replicas": 0}}))
        bulk_insert_data = _bulk_insert_data()

        def generate(key):
            result = bulk_insert_data(self.source_cluster, self.indices[key], INDEX_DOCS[key], DOC_SIZE_BYTES,
                                      1000, None)
            logger.info("Generated %s: %s", self.indices[key], result)
            return result

        with ThreadPoolExecutor(max_workers=len(INDEX_DOCS)) as pool:
            results = list(pool.map(generate, INDEX_DOCS))
        for key, result in zip(INDEX_DOCS, results):
            assert result["total_inserted"] == INDEX_DOCS[key], f"{self.indices[key]}: {result}"
        self.source_operations.refresh_index(cluster=self.source_cluster, index_name=",".join(self.indices.values()))

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        self.migration_start = time.time()
        super().workflow_perform_migrations(timeout_seconds=timeout_seconds)
        self.migration_end = time.time()

    def verify_clusters(self):
        self.target_operations.check_doc_counts_match(
            cluster=self.target_cluster,
            expected_index_details={self.indices[key]: {"count": count} for key, count in INDEX_DOCS.items()},
            max_attempts=30, delay=10.0)
        records = run_console_json(["failed-document-stream", "list", "--limit", "10"])
        assert records == [], f"documents reached the failed document stream: {records}"
        self._assert_backfill_completed()
        self._assert_metrics()
        self._assert_logs()

    def _snapshot_migration(self) -> dict:
        load_k8s_config()
        items = client.CustomObjectsApi().list_namespaced_custom_object(
            CRD_GROUP, CRD_VERSION, self.argo_service.namespace, SNAPSHOT_MIGRATION_PLURAL).get("items", [])
        assert len(items) == 1, f"expected one SnapshotMigration, found {len(items)}"
        return items[0]

    def _assert_backfill_completed(self):
        phase = None
        for _ in range(20):
            backfill = (self._snapshot_migration().get("status") or {}).get("documentBackfill") or {}
            phase = backfill.get("phase")
            if phase in ("Completed", "CompletedWithErrors"):
                break
            time.sleep(6)
        assert phase == "Completed", f"backfill phase {phase!r}, expected Completed"

    # --- Prometheus --------------------------------------------------------------------------------------------

    def _query(self, expr: str) -> float:
        """An instant query at the end of the migration over its whole window, as one number (0 if empty)."""
        window = f"{int(self.migration_end - self.migration_start) + 120}s"
        url = PROMETHEUS_URL.format(namespace=self.argo_service.namespace) + "/api/v1/query"
        response = requests.get(url, params={"query": expr.replace("$window", window),
                                             "time": self.migration_end + 60}, timeout=30)
        response.raise_for_status()
        result = response.json()["data"]["result"]
        value = float(result[0]["value"][1]) if result else 0.0
        logger.info("Prometheus %s = %s", expr, value)
        return value

    def _assert_metrics(self):
        scope = 'otel_scope_name="documentMigration"'

        def increase(metric, where=""):
            return self._query(f"sum(increase({metric}{{{scope}{where}}}[$window]))")

        def over_window(agg, metric):
            return self._query(f"{agg}_over_time({agg}({metric}{{{scope}}})[$window:15s])")

        total_docs = sum(INDEX_DOCS.values())
        throttled = increase("bulkAttempts_count_total", ',outcome=~"throttled_429|admission_rejected|timeout"')
        checks = {
            "the target pushed back (throttled attempts)": throttled > 0,
            "workers cut their in-flight limits": increase("bulkLimitCuts_count_total") > 0,
            "no document was given up": increase("bulkDocsGaveUp_count_total") == 0,
            "a worker's limit reached --max-connections (10)": over_window("max", "bulkLimit_count") >= 10,
            "a worker reset its threshold after a quiet period": increase("bulkLimitQuietResets_count_total") >= 1,
            "the retry budget never ran out": increase("retryBudgetEmpty_count_total") == 0,
            "bulk size histograms were recorded": increase("bulkRequestDocuments_count_count") > 0,
            "every document was counted as migrated": increase("pipelineDocsMigrated_count_total") >= total_docs * 0.99,
        }
        failed = [name for name, ok in checks.items() if not ok]
        assert not failed, f"flow-control expectations not met: {failed}"

    # --- logs ----------------------------------------------------------------------------------------------------

    def _rfs_log_lines(self):
        session = (self._snapshot_migration().get("status") or {}).get("rfsSessionName")
        assert session, "SnapshotMigration has no status.rfsSessionName"
        pod_prefix = re.compile(rf"^fluentbit-{re.escape(session)}-rfs-(?!coordinator|done)")
        for node in os.listdir(SHARED_LOGS):
            node_dir = os.path.join(SHARED_LOGS, node)
            for name in os.listdir(node_dir):
                if pod_prefix.match(name):
                    with open(os.path.join(node_dir, name), errors="replace") as f:
                        yield from f

    def _assert_logs(self):
        heartbeats, cuts, quiet_resets = 0, 0, 0
        cuts_in_heartbeats = 0
        for line in self._rfs_log_lines():
            if "Bulk flow in the last" in line:
                heartbeats += 1
                match = re.search(r"; (\d+) limit cuts", line)
                cuts_in_heartbeats += int(match.group(1)) if match else 0
            elif "cutting the in-flight limit" in line:
                cuts += 1
            elif "growing the in-flight limit" in line and "quickly again" in line:
                quiet_resets += 1
        logger.info("RFS logs: %d heartbeats (%d cuts reported), %d cut lines, %d quiet resets",
                    heartbeats, cuts_in_heartbeats, cuts, quiet_resets)
        assert heartbeats >= RFS_PODS, f"expected a flow heartbeat from each worker, saw {heartbeats}"
        assert cuts > 0 and cuts_in_heartbeats > 0, \
            f"expected limit cuts in the logs: {cuts} cut lines, {cuts_in_heartbeats} in heartbeats"
        assert quiet_resets >= 1, "expected a quiet-period reset in the logs"
