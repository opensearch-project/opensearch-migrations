"""End-to-end tests for RFS crash escalation and memory-tier scaling
(docs/rfsMemoryTierScaling.md).

The memory pressure is real and deterministic: a JavaScript document transformer
(jsonJSTransformer) makes exactly ONE typed-array allocation per weighted index per worker
process (held in a module-level map for the life of the process). Typed arrays materialize
real JVM heap immediately — the heap runs at -XX:MaxRAMPercentage=80 of the container limit —
so a worker whose tier cannot hold an index's weight OOMs on that index's first document,
every time (ExitOnOutOfMemoryError, exit 3), and completes once the tier's heap fits the
allocation. Cumulative per-document retention was tried first and rejected: engine string/GC
behavior let shards slip through tiers they were meant to overflow.

Sizing: tiers 1400Mi -> 2800Mi -> 5600Mi  =>  heaps ~1120 / 2240 / 4480 MiB
  tier-1-weight indices allocate 1300MiB (overflow tier 0, fit tier 1)
  tier-2-weight / heavy indices allocate 2600MiB (overflow tiers 0 and 1, fit tier 2)

These tests are long (each climbs the ladder on OOM-restart + 1-minute monitor sweeps), so they
only run when selected explicitly via --test_ids.
"""

import json
import logging
import time
import uuid

from kubernetes import client
from kubernetes.stream import stream as k8s_stream

from console_link.workflow.commands.crd_utils import CRD_GROUP, CRD_VERSION
from ..common_utils import convert_to_b64
from ..cluster_version import ElasticsearchV7_X, OpensearchV2_X
from .basic_tests import SNAPSHOT_MIGRATION_PLURAL
from .cdc_base import load_k8s_config
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)

COMPONENT_SELECTOR = "migrations.opensearch.org/component=rfs-worker"
TIER_LABEL = "migrations.opensearch.org/rfs-memory-tier"
BULK_LOADER_CONTAINER = "bulk-loader"
CRASH_WITNESS_CONTAINER = "crash-witness"

HEAVY_DOCS = 760          # ~380 per shard across 2 shards
HEAVY_SHARDS = 2
ORDINARY_DOCS = 50
HEAVY_RETAINED_MIB = 2600   # one allocation: overflows tier-0 AND tier-1 heaps, fits tier 2

# Worker resources: the smallest limit that still comfortably boots RFS in this suite's
# environment (the suite's other tests boot at 1Gi); requests == limits as memoryScaling requires.
TIER0_MEMORY = "1400Mi"

MEMORY_HOG_TRANSFORM_SCRIPT = """
var allocatedByIndex = {};
function main(context) {
  // {indexName: MiB retained} — memory pressure keyed by SOURCE INDEX NAME. Exactly ONE
  // deterministic allocation per index per worker process: typed arrays materialize real heap
  // immediately (unlike lazy string ropes), so a worker whose tier cannot hold the index's
  // weight OOMs on the first document, every time. Chunked to stay under Java's max array size.
  var weights = JSON.parse(context.get("indexRetainedMiBJson"));
  return (documents) => {
    for (const doc of documents) {
      var operation = doc.get("operation");
      var indexName = operation ? operation.get("_index") : null;
      var mib = indexName ? weights[indexName] : null;
      if (mib && !(indexName in allocatedByIndex)) {
        var chunks = [];
        var remaining = mib;
        while (remaining > 0) {
          var size = Math.min(remaining, 650);
          chunks.push(new Uint8Array(size * 1024 * 1024));
          remaining -= size;
        }
        allocatedByIndex[indexName] = chunks;
      }
    }
    return documents;
  };
}
(() => main)();
"""


def _memory_hog_transform(index_retained_mib: dict) -> dict:
    return {
        "JsonJSTransformerProvider": {
            "initializationScript": MEMORY_HOG_TRANSFORM_SCRIPT,
            "bindingsObject": json.dumps({
                "indexRetainedMiBJson": json.dumps(index_retained_mib),
            }),
        }
    }


class MemoryTierTestBase(MATestBase):
    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments, description: str):
        super().__init__(
            user_args=user_args,
            description=description,
            migrations_required=[MigrationType.BACKFILL],
            allow_source_target_combinations=[(ElasticsearchV7_X, OpensearchV2_X)],
        )
        suffix = f"{self.unique_id}-{uuid.uuid4().hex[:4]}"
        self.heavy_index = f"memtier-heavy-{suffix}"
        self.ordinary_indices = [f"memtier-plain-a-{suffix}", f"memtier-plain-b-{suffix}"]
        self.namespace = self.argo_service.namespace
        self.tier_observations = []
        # k8s clients are built lazily: test cases are constructed during pytest collection,
        # where no cluster may be reachable.
        self._apps = None
        self._core = None
        self._custom = None

    @property
    def apps(self):
        if self._apps is None:
            load_k8s_config()
            self._apps = client.AppsV1Api()
        return self._apps

    @property
    def core(self):
        if self._core is None:
            load_k8s_config()
            self._core = client.CoreV1Api()
        return self._core

    @property
    def custom(self):
        if self._custom is None:
            load_k8s_config()
            self._custom = client.CustomObjectsApi()
        return self._custom

    # ── seeding ────────────────────────────────────────────────────────────────

    def prepare_clusters(self):
        self.source_operations.create_index(
            index_name=self.heavy_index, cluster=self.source_cluster,
            data=json.dumps({"settings": {"number_of_shards": HEAVY_SHARDS, "number_of_replicas": 0}}))
        for index in self.ordinary_indices:
            self.source_operations.create_index(
                index_name=index, cluster=self.source_cluster,
                data=json.dumps({"settings": {"number_of_shards": 1, "number_of_replicas": 0}}))
        self._bulk_seed(self.heavy_index, HEAVY_DOCS)
        for index in self.ordinary_indices:
            self._bulk_seed(index, ORDINARY_DOCS)
        self.source_operations.refresh_index(
            cluster=self.source_cluster,
            index_name=",".join([self.heavy_index] + self.ordinary_indices))

    def _bulk_seed(self, index_name: str, count: int):
        from console_link.models.cluster import HttpMethod
        lines = []
        for i in range(count):
            lines.append(json.dumps({"index": {"_index": index_name, "_id": str(i)}}))
            lines.append(json.dumps({"n": i, "marker": f"{index_name}-{i}"}))
        body = "\n".join(lines) + "\n"
        response = self.source_cluster.call_api(
            "/_bulk", method=HttpMethod.POST, data=body,
            headers={"Content-Type": "application/x-ndjson"})
        assert not response.json().get("errors"), f"bulk seed into {index_name} had errors"

    # ── config ─────────────────────────────────────────────────────────────────

    def backfill_config(self, max_memory: str, memory_scaling: bool = True,
                        with_transform: bool = True, initial_lease: str = "PT10M") -> dict:
        config = {
            "failedDocumentStream": {"type": "disabled"},
            "maxShardSizeBytes": 64000000,
            "initialLeaseDuration": initial_lease,
            "indexAllowlist": [self.heavy_index] + self.ordinary_indices,
            "resources": {
                "requests": {"cpu": "25m", "memory": TIER0_MEMORY, "ephemeral-storage": "5Gi"},
                "limits": {"cpu": "1000m", "memory": TIER0_MEMORY, "ephemeral-storage": "5Gi"},
            },
        }
        if with_transform:
            config["docTransformerConfigBase64"] = convert_to_b64(
                [_memory_hog_transform({self.heavy_index: HEAVY_RETAINED_MIB})])
        if memory_scaling:
            config["memoryScaling"] = {
                "growthFactor": 2,
                "maxMemory": max_memory,
            }
            config["maxTotalPods"] = 4
        else:
            config["podReplicas"] = 2
        return config

    # ── observation helpers ────────────────────────────────────────────────────

    def rfs_deployments(self) -> dict:
        """{deployment name: (tier label value or None, desired replicas)}"""
        items = self.apps.list_namespaced_deployment(
            self.namespace, label_selector=COMPONENT_SELECTOR).items
        return {d.metadata.name: ((d.metadata.labels or {}).get(TIER_LABEL), d.spec.replicas or 0)
                for d in items}

    def observe_tiers(self):
        deployments = self.rfs_deployments()
        active = sorted(int(t) for t, replicas in deployments.values()
                        if t is not None and replicas > 0)
        self.tier_observations.append(active)
        return deployments

    def snapshot_migration_status(self) -> dict:
        """Empty dict until the workflow's initializer has created the CR — pollers treat
        a missing resource as not-yet-started, never as a failure."""
        items = self.custom.list_namespaced_custom_object(
            group=CRD_GROUP, version=CRD_VERSION, namespace=self.namespace,
            plural=SNAPSHOT_MIGRATION_PLURAL).get("items", []) or []
        if not items:
            return {}
        return items[0].get("status", {}) or {}

    def wait_observing_tiers(self, timeout_seconds: int, poll_seconds: int = 15,
                             until_phase=("Completed", "Failed")):
        """Poll the SnapshotMigration while recording which tier fleets are active."""
        deadline = time.time() + timeout_seconds
        while time.time() < deadline:
            self.observe_tiers()
            phase = self.snapshot_migration_status().get("phase", "")
            if phase in until_phase:
                return phase
            time.sleep(poll_seconds)
        raise AssertionError(
            f"SnapshotMigration never reached {until_phase} within {timeout_seconds}s; "
            f"tier observations: {self.tier_observations[-20:]}")

    def verify_ordinary_indices_on_target(self):
        self.target_operations.check_doc_counts_match(
            cluster=self.target_cluster,
            expected_index_details={index: {"count": ORDINARY_DOCS}
                                    for index in self.ordinary_indices},
            max_attempts=20, delay=6.0)


class Test0090MemoryTierEscalationCompletes(MemoryTierTestBase):
    """(a) Heavy shards OOM at tier 0, are escalated, climb through tier 1 to tier 2, and
    complete. Ordinary shards finish at tier 0 without waiting for the heavy ones (greedy, not
    lockstep). The per-tier Deployments appear during the run and are cleaned up afterwards."""

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(user_args, "Memory-tier scaling: heavy shards escalate to tier 2 and complete.")

    def prepare_workflow_snapshot_and_migration_config(self):
        self.workflow_snapshot_and_migration_config = [{
            "migrations": [{"documentBackfillConfig": self.backfill_config(max_memory="5600Mi")}]
        }]

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        if not self.imported_clusters:
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)
        phase = self.wait_observing_tiers(timeout_seconds=timeout_seconds)
        assert phase == "Completed", f"expected Completed, got {phase}"
        if not self.imported_clusters:
            self.argo_service.wait_for_suspend(workflow_name=self.workflow_name,
                                               timeout_seconds=600)

    def verify_clusters(self):
        self.verify_ordinary_indices_on_target()
        self.target_operations.check_doc_counts_match(
            cluster=self.target_cluster,
            expected_index_details={self.heavy_index: {"count": HEAVY_DOCS}},
            max_attempts=20, delay=6.0)

        observed_tiers = {tier for active in self.tier_observations for tier in active}
        assert 1 in observed_tiers or 2 in observed_tiers, \
            f"no escalated tier fleet was ever observed: {self.tier_observations}"
        assert 2 in observed_tiers, \
            f"tier 2 never activated (heavy shards need it to complete): {self.tier_observations}"
        # Greedy, not lockstep: at some instant a higher-tier fleet ran WHILE tier 0 still ran.
        assert any(0 in active and any(t > 0 for t in active)
                   for active in self.tier_observations), \
            f"tiers never ran in parallel (lockstep behavior?): {self.tier_observations}"

        # The monitor's terminal teardown removes every tier deployment and the policy ConfigMap.
        deadline = time.time() + 300
        while time.time() < deadline and self.rfs_deployments():
            time.sleep(10)
        assert self.rfs_deployments() == {}, \
            f"RFS tier deployments were not cleaned up: {self.rfs_deployments()}"

        status = self.snapshot_migration_status()
        backfill = status.get("documentBackfill", {})
        assert backfill.get("phase") == "Completed", f"documentBackfill status: {backfill}"
        summary = backfill.get("summary", {})
        expected_shards = HEAVY_SHARDS + len(self.ordinary_indices)
        assert summary.get("shardsTotal") == expected_shards, f"summary: {summary}"
        assert summary.get("shardsMigrated") == expected_shards, f"summary: {summary}"


class Test0091MemoryTierExhaustionFails(MemoryTierTestBase):
    """(b) A shard that cannot fit even at maxMemory: the migration ends Failed, the parent
    workflow fails (does not hang), and the rollup lists succeeded vs incomplete indices."""

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(user_args, "Memory-tier scaling: shards exceeding maxMemory fail the migration.")

    def prepare_workflow_snapshot_and_migration_config(self):
        # Two tiers only (1400Mi, 2800Mi); the heavy shards overflow both.
        self.workflow_snapshot_and_migration_config = [{
            "migrations": [{"documentBackfillConfig": self.backfill_config(max_memory="2800Mi")}]
        }]

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        if not self.imported_clusters:
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)
        phase = self.wait_observing_tiers(timeout_seconds=timeout_seconds)
        assert phase == "Failed", f"expected the SnapshotMigration to end Failed, got {phase}"

    def verify_clusters(self):
        # Ordinary indices completed before the failure was declared.
        self.verify_ordinary_indices_on_target()
        status = self.snapshot_migration_status()
        backfill = status.get("documentBackfill", {})
        assert backfill.get("phase") == "Failed", f"documentBackfill status: {backfill}"
        message = backfill.get("message", "")
        assert "exceed" in message, f"expected the maxMemory rollup message, got: {message!r}"
        for index in self.ordinary_indices:
            assert index in message, f"succeeded index {index} missing from rollup: {message!r}"
        assert self.heavy_index in message, f"incomplete index missing from rollup: {message!r}"

    def workflow_finish(self):
        # The parent workflow must FAIL (not hang): the waiter recognizes status.phase == Failed.
        self.argo_service.wait_for_ending_phase(workflow_name=self.workflow_name,
                                                timeout_seconds=900)

    def test_after(self):
        status_result = self.argo_service.get_workflow_status(workflow_name=self.workflow_name)
        phase = status_result.value.get("phase", "")
        assert phase in ("Failed", "Error"), \
            f"Expected the parent workflow to fail on the Failed migration, got '{phase}'"


class Test0092NonOomCrashDoesNotEscalate(MemoryTierTestBase):
    """(c) A non-OOM hard crash (kill -9 of the worker JVM): the lease is recovered immediately
    by the same pod's next process (no waiting for natural expiry), and there is NO tier bump —
    a bare 137 without an OOMKilled reason is not OOM evidence."""

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(user_args, "Memory-tier scaling: kill -9 recovers the lease with no tier bump.")
        # No heavy index for this scenario: just enough ordinary work to catch a worker mid-shard.
        self.killed = False

    def prepare_clusters(self):
        for index in self.ordinary_indices:
            self.source_operations.create_index(
                index_name=index, cluster=self.source_cluster,
                data=json.dumps({"settings": {"number_of_shards": 1, "number_of_replicas": 0}}))
            self._bulk_seed(index, ORDINARY_DOCS * 40)  # enough docs to not finish instantly
        self.source_operations.refresh_index(
            cluster=self.source_cluster, index_name=",".join(self.ordinary_indices))

    def prepare_workflow_snapshot_and_migration_config(self):
        config = self.backfill_config(max_memory="5600Mi", with_transform=False,
                                      initial_lease="PT30M")
        config["indexAllowlist"] = self.ordinary_indices
        self.workflow_snapshot_and_migration_config = [{
            "migrations": [{"documentBackfillConfig": config}]
        }]

    def _kill_one_worker(self):
        pods = self.core.list_namespaced_pod(
            self.namespace, label_selector=f"{COMPONENT_SELECTOR},{TIER_LABEL}=0").items
        running = [p for p in pods if p.status.phase == "Running"]
        if not running:
            return False
        pod = running[0].metadata.name
        # SIGKILL the worker JVM in place: the container restarts in the SAME pod, so the next
        # process's dirty-lease sweep (not natural lease expiry) must recover the lease.
        k8s_stream(self.core.connect_get_namespaced_pod_exec, pod, self.namespace,
                   container=BULK_LOADER_CONTAINER,
                   command=["/bin/sh", "-c", "pkill -9 java || kill -9 $(pidof java)"],
                   stderr=True, stdin=False, stdout=True, tty=False)
        logger.info("kill -9'd the worker JVM in pod %s", pod)
        return True

    def workflow_perform_migrations(self, timeout_seconds: int = 2400):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        if not self.imported_clusters:
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)
        # Wait for a running tier-0 worker, kill it, then let the migration finish.
        kill_deadline = time.time() + 900
        while not self.killed and time.time() < kill_deadline:
            self.observe_tiers()
            self.killed = self._kill_one_worker()
            if not self.killed:
                time.sleep(10)
        assert self.killed, "never found a running tier-0 worker to kill"
        phase = self.wait_observing_tiers(timeout_seconds=timeout_seconds)
        # With a 30-minute initial lease, completing inside this window proves the lease was
        # recovered by the startup sweep rather than by natural expiration.
        assert phase == "Completed", f"expected Completed, got {phase}"
        if not self.imported_clusters:
            self.argo_service.wait_for_suspend(workflow_name=self.workflow_name,
                                               timeout_seconds=600)

    def verify_clusters(self):
        self.target_operations.check_doc_counts_match(
            cluster=self.target_cluster,
            expected_index_details={index: {"count": ORDINARY_DOCS * 40}
                                    for index in self.ordinary_indices},
            max_attempts=20, delay=6.0)
        escalated = {tier for active in self.tier_observations for tier in active if tier > 0}
        assert not escalated, \
            f"a non-OOM crash must never climb the memory ladder; saw tiers {escalated}"


class Test0093MemoryScalingOffIsUnchanged(MemoryTierTestBase):
    """(d) Feature off: the same data migrates through the single pre-feature Deployment —
    today's name, no tier label, no crash-witness sidecar, no tier-policy ConfigMap."""

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(user_args, "Memory-tier scaling absent: byte-identical runtime behavior.")

    def prepare_clusters(self):
        for index in self.ordinary_indices:
            self.source_operations.create_index(
                index_name=index, cluster=self.source_cluster,
                data=json.dumps({"settings": {"number_of_shards": 1, "number_of_replicas": 0}}))
            self._bulk_seed(index, ORDINARY_DOCS)
        self.source_operations.refresh_index(
            cluster=self.source_cluster, index_name=",".join(self.ordinary_indices))

    def prepare_workflow_snapshot_and_migration_config(self):
        config = self.backfill_config(max_memory="", memory_scaling=False, with_transform=False)
        config["indexAllowlist"] = self.ordinary_indices
        self.workflow_snapshot_and_migration_config = [{
            "migrations": [{"documentBackfillConfig": config}]
        }]

    def workflow_perform_migrations(self, timeout_seconds: int = 1800):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        if not self.imported_clusters:
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)
        observed = False
        deadline = time.time() + timeout_seconds
        while time.time() < deadline:
            deployments = self.rfs_deployments()
            if deployments and not observed:
                observed = True
                # Exactly one deployment, today's "<session>-rfs" name shape, no tier label.
                assert len(deployments) == 1, f"expected one RFS deployment, saw {deployments}"
                name, (tier, _) = next(iter(deployments.items()))
                assert name.endswith("-rfs"), f"unexpected deployment name {name}"
                assert tier is None, "feature-off deployment must not carry the tier label"
                pods = self.core.list_namespaced_pod(
                    self.namespace, label_selector=COMPONENT_SELECTOR).items
                for pod in pods:
                    containers = [c.name for c in pod.spec.containers]
                    assert containers == [BULK_LOADER_CONTAINER], \
                        f"feature-off pod must run only the bulk loader, saw {containers}"
                    assert pod.spec.termination_grace_period_seconds in (None, 30), \
                        "feature-off pods must keep the default grace period"
                config_maps = [cm.metadata.name for cm in
                               self.core.list_namespaced_config_map(self.namespace).items
                               if cm.metadata.name.endswith("-rfs-tier-policy")]
                assert config_maps == [], f"tier-policy ConfigMap must not exist: {config_maps}"
            phase = self.snapshot_migration_status().get("phase", "")
            if phase in ("Completed", "Failed"):
                assert phase == "Completed", f"expected Completed, got {phase}"
                break
            time.sleep(10)
        assert observed, "never observed the RFS deployment while the migration ran"
        if not self.imported_clusters:
            self.argo_service.wait_for_suspend(workflow_name=self.workflow_name,
                                               timeout_seconds=600)

    def verify_clusters(self):
        self.verify_ordinary_indices_on_target()


LADDER_DOCS = 50          # docs per dummy index; memory comes from the transform, not the data
TIER1_RETAINED_MIB = 1300  # one allocation: overflows tier-0 heap (~1120MiB), fits tier 1 (~2240MiB)
TIER2_RETAINED_MIB = 2600  # one allocation: overflows tier-1 heap, fits tier 2 (~4480MiB)


class Test0094MemoryTierLadder(MemoryTierTestBase):
    """(e) Diversity ladder: ten small dummy indices (50 tiny docs each) created on an in-test
    source cluster, blown up by per-index transform weights so different indices land on
    different memory tiers — six stay at tier 0, two land at tier 1 (1300MiB single allocation),
    two climb to tier 2 (2600MiB). Everything is created inside the test: no external
    snapshot, no bucket access, runs anywhere the full (with-clusters) tests run."""

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(user_args, "Memory-tier scaling: ten dummy indices spread across three tiers.")
        suffix = self.heavy_index.rsplit("memtier-heavy-", 1)[-1]
        self.ladder_indices = [f"memtier-{i:02d}-{suffix}" for i in range(10)]
        self.tier1_indices = self.ladder_indices[6:8]
        self.tier2_indices = self.ladder_indices[8:10]

    def prepare_clusters(self):
        for index in self.ladder_indices:
            self.source_operations.create_index(
                index_name=index, cluster=self.source_cluster,
                data=json.dumps({"settings": {"number_of_shards": 1, "number_of_replicas": 0}}))
            self._bulk_seed(index, LADDER_DOCS)
        self.source_operations.refresh_index(
            cluster=self.source_cluster, index_name=",".join(self.ladder_indices))

    def prepare_workflow_snapshot_and_migration_config(self):
        config = self.backfill_config(max_memory="5600Mi", with_transform=False)
        config["indexAllowlist"] = self.ladder_indices
        config["maxTotalPods"] = 6
        weights = {index: TIER1_RETAINED_MIB for index in self.tier1_indices}
        weights.update({index: TIER2_RETAINED_MIB for index in self.tier2_indices})
        config["docTransformerConfigBase64"] = convert_to_b64([_memory_hog_transform(weights)])
        self.workflow_snapshot_and_migration_config = [{
            "migrations": [{"documentBackfillConfig": config}]
        }]

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        if not self.imported_clusters:
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)
        phase = self.wait_observing_tiers(timeout_seconds=timeout_seconds)
        assert phase == "Completed", f"expected Completed, got {phase}"
        if not self.imported_clusters:
            self.argo_service.wait_for_suspend(workflow_name=self.workflow_name,
                                               timeout_seconds=600)

    def verify_clusters(self):
        self.target_operations.check_doc_counts_match(
            cluster=self.target_cluster,
            expected_index_details={index: {"count": LADDER_DOCS}
                                    for index in self.ladder_indices},
            max_attempts=20, delay=6.0)

        observed_tiers = {tier for active in self.tier_observations for tier in active}
        assert {0, 1, 2} <= observed_tiers, \
            f"expected all of tiers 0, 1, 2 to activate; observed {self.tier_observations}"
        # Greedy, not lockstep: tier 0 kept running while at least one escalated fleet worked.
        assert any(0 in active and any(t > 0 for t in active)
                   for active in self.tier_observations), \
            f"tiers never ran in parallel: {self.tier_observations}"
