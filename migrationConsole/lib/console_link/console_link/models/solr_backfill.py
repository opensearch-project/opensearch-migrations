"""Solr-specific backfill: reads docs from Solr via query API, writes to OpenSearch."""
import json
import logging
import urllib.parse
from typing import Dict, List, Optional

from console_link.models.backfill_base import Backfill, BackfillStatus, BackfillOverallStatus
from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.command_result import CommandResult
from console_link.models.step_state import StepStateWithPause

logger = logging.getLogger(__name__)

BATCH_SIZE = 500


def _solr_cursor_query(source: Cluster, collection: str, cursor_mark: str, rows: int) -> Dict:
    """Query Solr with cursor-based pagination."""
    encoded_cursor = urllib.parse.quote(cursor_mark, safe='')
    r = source.call_api(
        f"/solr/{collection}/select?q=*:*&wt=json&sort=id+asc"
        f"&rows={rows}&cursorMark={encoded_cursor}"
    )
    return r.json()


def _bulk_index_to_opensearch(target: Cluster, index: str, docs: List[Dict]):
    """Bulk index documents to OpenSearch."""
    if not docs:
        return
    lines = []
    for doc in docs:
        doc_id = doc.pop("id", doc.pop("_id", None))
        action = {"index": {"_index": index}}
        if doc_id:
            action["index"]["_id"] = str(doc_id)
        lines.append(json.dumps(action))
        lines.append(json.dumps(doc))
    body = "\n".join(lines) + "\n"
    target.call_api(
        "/_bulk",
        method=HttpMethod.POST,
        data=body,
        headers={"Content-Type": "application/x-ndjson"},
    )


class SolrBackfill(Backfill):
    """Backfills data from Solr collections to OpenSearch indices via cursor queries."""

    def __init__(self, source_cluster: Cluster, target_cluster: Cluster,
                 index_allowlist: Optional[List[str]] = None):
        # Skip Backfill.__init__ validation — it expects ES-specific config keys
        self.config = {"reindex_from_snapshot": {"solr": True}}
        self.source_cluster = source_cluster
        self.target_cluster = target_cluster
        self.index_allowlist = index_allowlist
        self._running = False
        self._docs_migrated = 0
        self._docs_total = 0

    def create(self, *args, **kwargs) -> CommandResult:
        return CommandResult(True, "no-op")

    def start(self, *args, **kwargs) -> CommandResult:
        """Run the backfill synchronously."""
        self._running = True
        try:
            return self._run_backfill()
        finally:
            self._running = False

    def stop(self, *args, **kwargs) -> CommandResult:
        self._running = False
        return CommandResult(True, "Stopped")

    def pause(self, *args, **kwargs) -> CommandResult:
        self._running = False
        return CommandResult(True, "Paused")

    def scale(self, units: int, *args, **kwargs) -> CommandResult:
        return CommandResult(True, "Solr backfill runs as a single process")

    def get_status(self, *args, **kwargs) -> CommandResult:
        if self._running:
            return CommandResult(True, (BackfillStatus.RUNNING,
                                        f"Migrated {self._docs_migrated}/{self._docs_total} docs"))
        return CommandResult(True, (BackfillStatus.STOPPED,
                                    f"Migrated {self._docs_migrated}/{self._docs_total} docs"))

    def archive(self, *args, **kwargs) -> CommandResult:
        return CommandResult(True, "no-op for Solr backfill")

    def build_backfill_status(self) -> BackfillOverallStatus:
        pct = (self._docs_migrated / self._docs_total * 100) if self._docs_total else 0.0
        status = StepStateWithPause.RUNNING if self._running else StepStateWithPause.COMPLETED
        return BackfillOverallStatus(
            status=status,
            percentage_completed=pct,
        )

    def _run_backfill(self) -> CommandResult:
        """Migrate all collections from Solr to OpenSearch."""
        from console_link.models.solr_metadata import get_solr_collections
        collections = get_solr_collections(self.source_cluster)
        if self.index_allowlist:
            collections = [c for c in collections if c in self.index_allowlist]

        # Get total doc counts
        self._docs_total = 0
        for coll in collections:
            r = self.source_cluster.call_api(f"/solr/{coll}/select?q=*:*&rows=0&wt=json")
            self._docs_total += r.json().get("response", {}).get("numFound", 0)

        self._docs_migrated = 0
        results = []
        for coll in collections:
            if not self._running:
                results.append(f"Stopped before completing '{coll}'")
                break
            try:
                count = self._migrate_collection(coll)
                results.append(f"Migrated {count} docs from '{coll}'")
            except Exception as e:
                results.append(f"Failed on '{coll}': {e}")

        return CommandResult(success=True, value="\n".join(results))

    def _migrate_collection(self, collection: str) -> int:
        """Migrate all docs from a single Solr collection using cursor pagination."""
        cursor_mark = "*"
        count = 0
        while self._running:
            resp = _solr_cursor_query(self.source_cluster, collection, cursor_mark, BATCH_SIZE)
            docs = resp.get("response", {}).get("docs", [])
            next_cursor = resp.get("nextCursorMark", cursor_mark)

            if docs:
                # Remove Solr internal fields
                for doc in docs:
                    doc.pop("_version_", None)
                _bulk_index_to_opensearch(self.target_cluster, collection, docs)
                count += len(docs)
                self._docs_migrated += len(docs)

            if next_cursor == cursor_mark:
                break
            cursor_mark = next_cursor

        return count
