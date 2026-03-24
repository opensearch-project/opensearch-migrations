"""Solr-specific metadata migration: converts Solr schemas to OpenSearch mappings."""
import logging
from typing import Dict, List, Optional

from console_link.models.cluster import Cluster, HttpMethod, NoTargetClusterDefinedError
from console_link.models.command_result import CommandResult

logger = logging.getLogger(__name__)

SOLR_TO_OS_TYPE = {
    "string": "keyword", "strings": "keyword",
    "text_general": "text", "text_en": "text", "text_ws": "text", "text": "text",
    "pint": "integer", "pints": "integer", "int": "integer",
    "plong": "long", "plongs": "long", "long": "long",
    "pfloat": "float", "pfloats": "float", "float": "float",
    "pdouble": "double", "pdoubles": "double", "double": "double",
    "pdate": "date", "pdates": "date", "date": "date",
    "boolean": "boolean", "booleans": "boolean",
    "binary": "binary",
}


def convert_solr_schema_to_os_mappings(solr_fields: List[Dict]) -> Dict:
    """Convert Solr schema fields to OpenSearch mappings."""
    properties = {}
    for field in solr_fields:
        name = field.get("name", "")
        field_type = field.get("type", "")
        if name.startswith("_") and name != "id":
            continue
        os_type = SOLR_TO_OS_TYPE.get(field_type, "text")
        properties[name] = {"type": os_type}
    return {"properties": properties}


def get_solr_collections(source: Cluster) -> List[str]:
    """List Solr collections (SolrCloud) or cores (standalone)."""
    # Try SolrCloud Collections API first
    try:
        r = source.call_api("/solr/admin/collections?action=LIST&wt=json")
        if r.status_code == 200:
            collections = r.json().get("collections", [])
            if collections:
                return collections
    except Exception:
        pass
    # Fall back to Core Admin API (standalone)
    r = source.call_api("/solr/admin/cores?action=STATUS&wt=json")
    return list(r.json().get("status", {}).keys())


def get_solr_schema_fields(source: Cluster, collection: str) -> List[Dict]:
    """Get schema fields for a Solr collection."""
    r = source.call_api(f"/solr/{collection}/schema?wt=json")
    return r.json().get("schema", {}).get("fields", [])


class SolrMetadata:
    """Migrates Solr collection schemas to OpenSearch index mappings."""

    def __init__(self, source_cluster: Cluster, target_cluster: Cluster,
                 index_allowlist: Optional[List[str]] = None):
        self.source_cluster = source_cluster
        self.target_cluster = target_cluster
        self.index_allowlist = index_allowlist

    def evaluate(self, **kwargs) -> CommandResult:
        """Dry-run: show what would be created."""
        return self._migrate_schemas(dry_run=True)

    def migrate(self, **kwargs) -> CommandResult:
        """Create OpenSearch indices from Solr schemas."""
        return self._migrate_schemas(dry_run=False)

    def _migrate_schemas(self, dry_run: bool) -> CommandResult:
        if not self.target_cluster:
            raise NoTargetClusterDefinedError()

        collections = get_solr_collections(self.source_cluster)
        if self.index_allowlist:
            collections = [c for c in collections if c in self.index_allowlist]

        results = []
        for coll in collections:
            try:
                fields = get_solr_schema_fields(self.source_cluster, coll)
                mappings = convert_solr_schema_to_os_mappings(fields)

                if dry_run:
                    results.append(f"[DRY RUN] Would create index '{coll}' with "
                                   f"{len(mappings.get('properties', {}))} fields")
                else:
                    self._create_index(coll, mappings)
                    results.append(f"Created index '{coll}' with "
                                   f"{len(mappings.get('properties', {}))} fields")
            except Exception as e:
                results.append(f"Failed on '{coll}': {e}")

        return CommandResult(success=True, value="\n".join(results))

    def _create_index(self, index_name: str, mappings: Dict):
        """Create an OpenSearch index with the given mappings."""
        import json
        body = json.dumps({"mappings": mappings})
        r = self.target_cluster.call_api(
            f"/{index_name}",
            method=HttpMethod.PUT,
            data=body,
            headers={"Content-Type": "application/json"},
            raise_error=False
        )
        if r.status_code == 400:
            error = r.json().get("error", {})
            if "resource_already_exists_exception" in str(error.get("type", "")):
                logger.info(f"Index '{index_name}' already exists, skipping")
                return
        r.raise_for_status()
