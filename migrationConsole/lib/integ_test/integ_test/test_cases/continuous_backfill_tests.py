"""Successive snapshot backfills through the production workflow on EKS."""
import copy
import json
import logging
import subprocess
import time
import uuid

from console_link.models.cluster import HttpMethod

from ..cluster_version import CDC_MIGRATION_COMBINATIONS
from ..common_utils import convert_to_b64, execute_api_call
from .ma_argo_test_base import MATestBase, MATestUserArguments

logger = logging.getLogger(__name__)


class Test0090SuccessiveSnapshotBackfills(MATestBase):
    """Baseline, document changes, segment merges, and a no-op delta in one workflow.

    Approval gates make source mutations deterministic: the next snapshot must not
    start until the preceding backfill has finished and its gate is approved.
    Target-only data and document versions detect accidental full reindexing.
    """
    requires_explicit_selection = True
    SNAPSHOTS = ("initial", "updated", "merged", "unchanged")

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="Successive snapshot backfills preserve exact contents through updates, deletes and merges.",
            allow_source_target_combinations=CDC_MIGRATION_COMBINATIONS,
        )
        suffix = f"{self.unique_id}-{uuid.uuid4().hex[:6]}"
        self.index_name = f"test_0090_{suffix}"
        self.removed_index = f"{self.index_name}-removed"
        self.new_index = f"{self.index_name}-new"
        self.repo_name = f"continuous-{suffix}"
        self.expected = {
            self.index_name: {
                "stable": {"value": "unchanged"},
                "updated": {"value": "before"},
                "deleted": {"value": "remove me"},
                "nested": {"children": [{"value": "one"}, {"value": "two"}]},
            },
            self.removed_index: {"gone": {"value": "removed index"}},
        }
        self.argo_service.expected_parked_gate_names = [
            self._gate_name(snapshot) for snapshot in self.SNAPSHOTS
        ]
        self.versions_before_noop = None

    @staticmethod
    def _gate_name(snapshot: str) -> str:
        return f"documentbackfill.source1-target1-{snapshot}-migration-0"

    def import_existing_clusters(self):
        super().import_existing_clusters()
        assert self.imported_clusters, "Test0090 requires --reuse_clusters and provisioned EKS test clusters"

    @staticmethod
    def _cluster_config(config: dict, source: bool) -> dict:
        result = {"endpoint": config["endpoint"]}
        if source:
            result["version"] = config["version"].replace("_", " ")
        if "allow_insecure" in config:
            result["allowInsecure"] = config["allow_insecure"]
        if "sigv4" in config:
            result["authConfig"] = {"sigv4": config["sigv4"]}
        elif "basic_auth" in config:
            result["authConfig"] = {"basic": {"secretName": config["basic_auth"]["k8s_secret_name"]}}
        return result

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        deployment = self.argo_service.get_configmap_data("migrations-default-s3-config")
        repo = {"repoPathUri": f"{deployment['BUCKET_URI']}/{self.repo_name}"}
        for source_key, target_key in (
            ("ENDPOINT", "endpoint"), ("SNAPSHOT_ROLE_ARN", "s3RoleArn"), ("AWS_REGION", "awsRegion")
        ):
            if deployment.get(source_key):
                repo[target_key] = deployment[source_key]
        source = self._cluster_config(self.source_cluster.config, source=True)
        source["snapshotInfo"] = {
            "repos": {self.repo_name: repo},
            "snapshots": {
                name: {
                    "repoName": self.repo_name,
                    "config": {"createSnapshotConfig": {"indexAllowlist": [f"{self.index_name}*"]}},
                }
                for name in self.SNAPSHOTS
            },
        }
        backfill = {
            "skipApproval": False,
            "podReplicas": 2,
            "documentsPerBulkRequest": 1,
            "maxConnections": 4,
            "maxShardSizeBytes": 16000000,
            "resources": {
                "requests": {"cpu": "25m", "memory": "1Gi", "ephemeral-storage": "5Gi"},
                "limits": {"cpu": "1000m", "memory": "2Gi", "ephemeral-storage": "5Gi"},
            },
        }
        config = {
            "skipApprovals": True,
            "sourceClusters": {"source1": source},
            "targetClusters": {"target1": self._cluster_config(self.target_cluster.config, source=False)},
            "snapshotMigrationConfigs": [{
                "fromSource": "source1",
                "toTarget": "target1",
                "snapshotSequence": list(self.SNAPSHOTS),
                "perSnapshotConfig": {
                    name: [{"metadataMigrationConfig": {}, "documentBackfillConfig": copy.deepcopy(backfill)}]
                    for name in self.SNAPSHOTS
                },
            }],
        }
        images = self.argo_service.get_configmap_data("migration-image-config")
        self.workflow_template = "full-migration-with-workflow-cli"
        self.parameters = {
            "migrationConfigBase64": convert_to_b64(config),
            "imageMigrationConsoleLocation": images["migrationConsoleImage"],
            "imageMigrationConsolePullPolicy": images["migrationConsolePullPolicy"],
            "keepMigrationWorkflow": "true",
            "monitor-retry-limit": "90",
        }

    def prepare_clusters(self):
        for index, documents in self.expected.items():
            self.source_operations.create_index(
                cluster=self.source_cluster, index_name=index,
                data=json.dumps({
                    "settings": {
                        "number_of_shards": 2, "number_of_replicas": 0, "refresh_interval": "-1",
                        "merge.policy.floor_segment": "1mb", "merge.policy.max_merged_segment": "1mb",
                    },
                    "mappings": {"properties": {"children": {"type": "nested"}}},
                }),
            )
            for doc_id, body in documents.items():
                self._put(index, doc_id, body)
        self._flush()

    def _put(self, index: str, doc_id: str, body: dict, update: bool = False):
        self.source_operations.create_document(
            cluster=self.source_cluster, index_name=index, doc_id=doc_id, data=body,
            expected_status_code=200 if update else 201,
        )

    def _flush(self):
        execute_api_call(self.source_cluster, f"/{self.index_name}*/_refresh", method=HttpMethod.POST)
        execute_api_call(self.source_cluster, f"/{self.index_name}*/_flush", method=HttpMethod.POST)

    def _target_documents(self, index: str) -> dict:
        execute_api_call(self.target_cluster, f"/{index}/_refresh", method=HttpMethod.POST)
        result = execute_api_call(self.target_cluster, f"/{index}/_search?size=1000&version=true").json()
        assert not result.get("timed_out"), result
        assert result["_shards"]["failed"] == 0, result
        return {hit["_id"]: (hit["_source"], hit["_version"]) for hit in result["hits"]["hits"]}

    def _verify_current_round(self) -> dict:
        versions = {}
        for index, expected in self.expected.items():
            actual = self._target_documents(index)
            assert {doc_id: doc[0] for doc_id, doc in actual.items()} == expected, (index, actual, expected)
            versions[index] = {doc_id: doc[1] for doc_id, doc in actual.items()}
        return versions

    @staticmethod
    def _workflow_cli(*args: str):
        result = subprocess.run(["workflow", *args], capture_output=True, text=True, timeout=60)
        assert result.returncode == 0, (args, result.stdout, result.stderr)
        return result.stdout

    def _wait_for_gate(self, snapshot: str, timeout_seconds: int):
        deadline = time.monotonic() + timeout_seconds
        watcher = self.argo_service.parked_gate_watcher_for(self.workflow_name)
        while time.monotonic() < deadline:
            watcher.check()
            status = self.argo_service.get_workflow_status(self.workflow_name).value
            assert status.get("phase") not in ("Failed", "Error", "Succeeded"), status
            gates = json.loads(self._workflow_cli("approve", "step", "--list", "--output", "json"))
            if any(g["name"] == self._gate_name(snapshot) and g["status"] == "waiting" for g in gates):
                return
            time.sleep(5)
        raise TimeoutError(f"Backfill for {snapshot} did not reach its approval gate")

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        for round_number, snapshot in enumerate(self.SNAPSHOTS):
            self._wait_for_gate(snapshot, timeout_seconds)
            versions = self._verify_current_round()
            snapshots = execute_api_call(self.source_cluster, f"/_snapshot/{self.repo_name}/_all").json()
            assert len(snapshots["snapshots"]) == round_number + 1, snapshots
            if snapshot == "initial":
                # Data outside the snapshot must survive all later backfills.
                sentinel = {"value": "target only"}
                self.target_operations.create_document(
                    cluster=self.target_cluster, index_name=self.index_name, doc_id="sentinel",
                    data=sentinel, expected_status_code=201,
                )
                self.expected[self.index_name]["sentinel"] = sentinel
                self.source_operations.delete_document(
                    cluster=self.source_cluster, index_name=self.index_name, doc_id="deleted")
                del self.expected[self.index_name]["deleted"]
                self._put(self.index_name, "updated", {"value": "after"}, update=True)
                self.expected[self.index_name]["updated"] = {"value": "after"}
                self._put(self.index_name, "added", {"value": "new"})
                self.expected[self.index_name]["added"] = {"value": "new"}
                self.source_operations.delete_index(cluster=self.source_cluster, index_name=self.removed_index)
                self.expected[self.removed_index] = {}
                self._put(self.new_index, "new-index-doc", {"value": "new index"})
                self.expected[self.new_index] = {"new-index-doc": {"value": "new index"}}
                self._flush()
                self.stable_version = versions[self.index_name]["stable"]
            elif snapshot == "updated":
                assert versions[self.index_name]["stable"] == self.stable_version, versions
                self._put(self.index_name, "updated", {"value": "after merge"}, update=True)
                self.expected[self.index_name]["updated"] = {"value": "after merge"}
                self._flush()
                response = execute_api_call(
                    self.source_cluster, f"/{self.index_name}/_forcemerge?max_num_segments=1",
                    method=HttpMethod.POST, timeout=180,
                ).json()
                assert response["_shards"]["failed"] == 0, response
            elif snapshot == "merged":
                self.versions_before_noop = versions
            else:
                assert versions == self.versions_before_noop, "An unchanged snapshot rewrote target documents"
            logger.info("Verified successive snapshot round %s", snapshot)
            self._workflow_cli("approve", "step", self._gate_name(snapshot))
        self.argo_service.wait_for_ending_phase(self.workflow_name, timeout_seconds=900)

    def verify_clusters(self):
        self._verify_current_round()

    def cleanup(self):
        self.argo_service.delete_workflow(workflow_name="migration-workflow")
