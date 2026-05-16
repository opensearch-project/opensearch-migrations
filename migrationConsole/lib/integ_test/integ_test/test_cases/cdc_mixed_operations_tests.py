import json
import logging
import time
import uuid

from console_link.models.cluster import HttpMethod

from .cdc_base import (
    MATestBase, MigrationType, MATestUserArguments,
    CDC_SOURCE_TARGET_COMBINATIONS, REPLAYER_LABEL_SELECTOR,
    wait_for_pod_ready, wait_for_replayer_consuming,
    make_proxy_cluster, send_bulk,
)
from ..common_utils import execute_api_call

logger = logging.getLogger(__name__)


class Test0033CdcOnlyMixedOperations(MATestBase):
    """CDC-only test exercising diverse operation types through the capture proxy.

    Covers: bulk inserts, individual creates, partial updates, update_by_query,
    delete_by_query, index lifecycle (create/delete/recreate with mappings),
    aliases, index templates, and settings changes.

    Uses three indices:
      - idx_bulk: bulk-inserted docs, then update_by_query and delete_by_query
      - idx_lifecycle: created with explicit mappings, deleted, recreated
      - idx_template: created from an index template, written via alias
    """
    requires_explicit_selection = True

    BULK_DOC_COUNT = 50
    BULK_BATCH_SIZE = 25

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="CDC mixed operations: bulk, updates, deletes, aliases, templates, settings.",
            migrations_required=[MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=CDC_SOURCE_TARGET_COMBINATIONS,
        )
        uid = f"{self.unique_id}-{uuid.uuid4().hex[:4]}"
        self.idx_bulk = f"cdc0033-bulk-{uid}"
        self.idx_lifecycle = f"cdc0033-lifecycle-{uid}"
        self.idx_template = f"cdc0033-tmpl-{uid}"
        self.alias_name = f"cdc0033-alias-{uid}"
        self.template_name = f"cdc0033-template-{uid}"

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
        logger.info("Replayer is running, ready for CDC traffic")

    def post_migration_actions(self):
        logger.info("Waiting for replayer to join Kafka consumer group...")
        wait_for_replayer_consuming(namespace=self.argo_service.namespace)
        proxy = make_proxy_cluster(self.source_cluster)
        ops = self.source_operations

        # ---- Phase 1: Bulk inserts into idx_bulk ----
        logger.info("Phase 1: Bulk inserting %d docs into %s", self.BULK_DOC_COUNT, self.idx_bulk)
        for batch_start in range(0, self.BULK_DOC_COUNT, self.BULK_BATCH_SIZE):
            send_bulk(proxy, self.idx_bulk, batch_start, self.BULK_BATCH_SIZE)

        # ---- Phase 2: Partial update via _update API ----
        logger.info("Phase 2: Partial update on doc_10")
        ops.update_document(index_name=self.idx_bulk, doc_id="doc_10", cluster=proxy,
                            doc={"title": "Updated bulk doc 10"})

        # ---- Phase 3: Update by query - set category=C for all value >= 40 ----
        # Allow time for the replayer to process bulk inserts on the target.
        # _update_by_query is a query-time operation — the target must have
        # searchable docs before the replayed query can match them.
        logger.info("Phase 3: Waiting 30s for replayer to process bulk inserts on target...")
        time.sleep(30)
        logger.info("Phase 3: update_by_query on %s (value >= 40 -> category=C)", self.idx_bulk)
        ops.refresh_index(index_name=self.idx_bulk, cluster=proxy)
        ops.update_by_query(index_name=self.idx_bulk, cluster=proxy, body={
            "query": {"range": {"value": {"gte": 40}}},
            "script": {"source": "ctx._source.category = 'C'", "lang": "painless"}
        })

        # ---- Phase 4: Delete by query - remove docs with value < 5 ----
        logger.info("Phase 4: delete_by_query on %s (value < 5)", self.idx_bulk)
        ops.refresh_index(index_name=self.idx_bulk, cluster=proxy)
        ops.delete_by_query(index_name=self.idx_bulk, cluster=proxy, body={
            "query": {"range": {"value": {"lt": 5}}}
        })
        # After: 45 docs remain (doc_5 through doc_49)

        # ---- Phase 5: Index lifecycle - create with mappings, write, delete, recreate ----
        logger.info("Phase 5: Index lifecycle on %s", self.idx_lifecycle)
        ops.create_index(index_name=self.idx_lifecycle, cluster=proxy, data=json.dumps({
            "mappings": {
                "properties": {
                    "name": {"type": "keyword"},
                    "score": {"type": "integer"}
                }
            }
        }))
        ops.create_document(index_name=self.idx_lifecycle, doc_id="lc_1", cluster=proxy,
                            data={"name": "first", "score": 10})
        ops.delete_index(index_name=self.idx_lifecycle, cluster=proxy)
        # Recreate with different mappings
        ops.create_index(index_name=self.idx_lifecycle, cluster=proxy, data=json.dumps({
            "mappings": {
                "properties": {
                    "name": {"type": "keyword"},
                    "score": {"type": "integer"},
                    "tag": {"type": "keyword"}
                }
            }
        }))
        ops.create_document(index_name=self.idx_lifecycle, doc_id="lc_2", cluster=proxy,
                            data={"name": "second", "score": 20, "tag": "recreated"})
        ops.create_document(index_name=self.idx_lifecycle, doc_id="lc_3", cluster=proxy,
                            data={"name": "third", "score": 30, "tag": "recreated"})

        # ---- Phase 6: Settings change ----
        logger.info("Phase 6: Change refresh_interval on %s", self.idx_lifecycle)
        ops.put_settings(index_name=self.idx_lifecycle, cluster=proxy,
                         settings={"index": {"refresh_interval": "5s"}})

        # ---- Phase 7: Index template + alias ----
        logger.info("Phase 7: Create index template and alias for %s", self.idx_template)
        ops.create_index_template(template_name=self.template_name, cluster=proxy, body={
            "index_patterns": ["cdc0033-tmpl-*"],
            "template": {
                "settings": {"number_of_replicas": 0},
                "mappings": {
                    "properties": {
                        "msg": {"type": "text"},
                        "priority": {"type": "integer"}
                    }
                }
            }
        })
        # Create the index (template should apply)
        ops.create_index(index_name=self.idx_template, cluster=proxy)
        # Create alias pointing to the index
        ops.create_alias(cluster=proxy, actions=[
            {"add": {"index": self.idx_template, "alias": self.alias_name}}
        ])
        # Write through alias
        ops.create_document(index_name=self.alias_name, doc_id="alias_1", cluster=proxy,
                            data={"msg": "written via alias", "priority": 1})
        ops.create_document(index_name=self.alias_name, doc_id="alias_2", cluster=proxy,
                            data={"msg": "also via alias", "priority": 2})

        # ---- Verify on source ----
        logger.info("Verifying final state on source...")
        ops.check_doc_counts_match(
            cluster=self.source_cluster,
            expected_index_details={
                self.idx_bulk: {"count": 45},
                self.idx_lifecycle: {"count": 2},
                self.idx_template: {"count": 2},
            },
            max_attempts=10, delay=3.0,
        )

    def verify_clusters(self):
        ops = self.target_operations
        target = self.target_cluster

        # 1. idx_bulk: 45 docs (50 - 5 deleted by delete_by_query)
        logger.info("Verifying idx_bulk doc count on target (expect 45)...")
        ops.check_doc_counts_match(
            cluster=target,
            expected_index_details={self.idx_bulk: {"count": 45}},
            max_attempts=120, delay=10.0,
        )

        # 2. idx_bulk: doc_10 was partially updated (survives delete_by_query since value=10 >= 5)
        logger.info("Verifying partial update on doc_10...")
        resp = ops.get_document(index_name=self.idx_bulk, doc_id="doc_10", cluster=target)
        content = resp.json()["_source"]
        assert content["title"] == "Updated bulk doc 10", f"Expected updated title, got {content}"
        assert content["value"] == 10, f"Expected value=10 preserved, got {content}"

        # 3. idx_bulk: update_by_query changed category to C for value >= 40
        logger.info("Verifying update_by_query result (doc_45 should have category=C)...")
        resp = ops.get_document(index_name=self.idx_bulk, doc_id="doc_45", cluster=target)
        content = resp.json()["_source"]
        assert content["category"] == "C", f"Expected category=C for doc_45, got {content}"

        # 4. idx_lifecycle: 2 docs (index was deleted and recreated)
        logger.info("Verifying idx_lifecycle on target (expect 2 docs after recreate)...")
        ops.check_doc_counts_match(
            cluster=target,
            expected_index_details={self.idx_lifecycle: {"count": 2}},
            max_attempts=120, delay=10.0,
        )
        # Verify the recreated doc has the 'tag' field (new mapping)
        resp = ops.get_document(index_name=self.idx_lifecycle, doc_id="lc_2", cluster=target)
        content = resp.json()["_source"]
        assert content["tag"] == "recreated", f"Expected tag=recreated, got {content}"
        # Verify lc_1 is gone (was in the deleted index)
        ops.get_document(index_name=self.idx_lifecycle, doc_id="lc_1", cluster=target,
                         expected_status_code=404, max_attempts=3)

        # 5. idx_lifecycle: settings change replicated
        logger.info("Verifying settings change on target...")
        resp = ops.get_index(index_name=self.idx_lifecycle, cluster=target)
        settings = resp.json()[self.idx_lifecycle]["settings"]["index"]
        assert settings.get("refresh_interval") == "5s", \
            f"Expected refresh_interval=5s, got {settings.get('refresh_interval')}"

        # 6. idx_template: 2 docs written via alias
        logger.info("Verifying idx_template on target (expect 2 docs)...")
        ops.check_doc_counts_match(
            cluster=target,
            expected_index_details={self.idx_template: {"count": 2}},
            max_attempts=120, delay=10.0,
        )
        # Verify template was applied (number_of_replicas=0)
        resp = ops.get_index(index_name=self.idx_template, cluster=target)
        idx_settings = resp.json()[self.idx_template]["settings"]["index"]
        assert idx_settings.get("number_of_replicas") == "0", \
            f"Expected number_of_replicas=0 from template, got {idx_settings.get('number_of_replicas')}"
        # Verify alias exists on target
        resp = execute_api_call(cluster=target, method=HttpMethod.GET,
                                path=f"/_alias/{self.alias_name}")
        alias_data = resp.json()
        assert self.idx_template in alias_data, \
            f"Expected alias {self.alias_name} to point to {self.idx_template}, got {alias_data}"

    def cleanup(self):
        # Delete composable index templates created by this test from both clusters.
        # These survive index deletion and cause 400 errors on reruns due to
        # conflicting index patterns.
        # Note: import_existing_clusters() clears target templates at test start,
        # but source templates need explicit cleanup here.
        for cluster in [self.source_cluster, self.target_cluster]:
            try:
                execute_api_call(cluster=cluster, method=HttpMethod.DELETE,
                                 path=f"/_index_template/{self.template_name}",
                                 max_attempts=1)
            except Exception as e:
                logger.debug("Failed to delete index template %s: %s", self.template_name, e)
