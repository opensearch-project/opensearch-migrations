from .default_operations import DefaultOperationsLibrary
from console_link.models.cluster import Cluster

import logging
import requests

logger = logging.getLogger(__name__)


def _is_solr_cloud(endpoint: str) -> bool:
    """Detect SolrCloud vs standalone by probing the Collections API."""
    try:
        r = requests.get(f"{endpoint}/solr/admin/collections?action=LIST&wt=json", timeout=10)
        return r.status_code == 200 and "collections" in r.json()
    except Exception:
        return False


class SolrOperationsLibrary(DefaultOperationsLibrary):
    """Operations library for Solr clusters (SolrCloud and standalone)."""

    def __init__(self):
        self._cloud_mode: dict[str, bool] = {}  # cache per endpoint

    def _is_cloud(self, cluster: Cluster) -> bool:
        endpoint = cluster.endpoint
        if endpoint not in self._cloud_mode:
            self._cloud_mode[endpoint] = _is_solr_cloud(endpoint)
            logger.info("Solr at %s detected as %s", endpoint,
                        "SolrCloud" if self._cloud_mode[endpoint] else "standalone")
        return self._cloud_mode[endpoint]

    def create_document(self, index_name: str, doc_id: str, cluster: Cluster, data: dict = None,
                        doc_type="_doc", **kwargs):
        if data is None:
            data = {'title': 'Test Document', 'content': 'Sample document for testing.'}
        data['id'] = doc_id
        r = requests.post(
            f"{cluster.endpoint}/solr/{index_name}/update?commit=true",
            json=[data],
            headers={'Content-Type': 'application/json'},
            timeout=10
        )
        r.raise_for_status()
        return r

    def get_document(self, index_name: str, doc_id: str, cluster: Cluster, doc_type="_doc",
                     max_attempts=5, delay=2.0, **kwargs):
        import time
        for attempt in range(max_attempts):
            r = requests.get(
                f"{cluster.endpoint}/solr/{index_name}/select?q=id:{doc_id}&wt=json",
                timeout=10
            )
            r.raise_for_status()
            docs = r.json().get("response", {}).get("docs", [])
            if docs:
                return r
            if attempt < max_attempts - 1:
                time.sleep(delay)
        raise AssertionError(f"Document {doc_id} not found in {index_name} after {max_attempts} attempts")

    def create_index(self, index_name: str, cluster: Cluster, **kwargs):
        """Create a Solr collection (Cloud) or core (standalone)."""
        if self._is_cloud(cluster):
            r = requests.get(
                f"{cluster.endpoint}/solr/admin/collections?action=CREATE"
                f"&name={index_name}&numShards=1&replicationFactor=1&collection.configName=_default",
                timeout=30
            )
        else:
            # Standalone: reuse the pre-created dummy core's instanceDir with a separate dataDir
            r = requests.get(
                f"{cluster.endpoint}/solr/admin/cores?action=CREATE"
                f"&name={index_name}&instanceDir=dummy&dataDir=data_{index_name}",
                timeout=30
            )
        r.raise_for_status()
        return r

    def get_all_index_details(self, cluster: Cluster, index_prefix_ignore_list=None, **kwargs):
        if self._is_cloud(cluster):
            return self._get_cloud_index_details(cluster, index_prefix_ignore_list)
        return self._get_standalone_index_details(cluster, index_prefix_ignore_list)

    def _get_cloud_index_details(self, cluster: Cluster, index_prefix_ignore_list=None):
        r = requests.get(f"{cluster.endpoint}/solr/admin/collections?action=LIST&wt=json", timeout=10)
        r.raise_for_status()
        collections = r.json().get("collections", [])
        index_dict = {}
        for name in collections:
            if index_prefix_ignore_list and any(name.startswith(p) for p in index_prefix_ignore_list):
                continue
            # Get doc count via core status (works for both modes)
            count = self._get_doc_count(cluster, name)
            index_dict[name] = {"count": str(count), "index": name}
        return index_dict

    def _get_standalone_index_details(self, cluster: Cluster, index_prefix_ignore_list=None):
        r = requests.get(f"{cluster.endpoint}/solr/admin/cores?action=STATUS&wt=json", timeout=10)
        r.raise_for_status()
        cores = r.json().get("status", {})
        index_dict = {}
        for core_name, core_info in cores.items():
            if index_prefix_ignore_list and any(core_name.startswith(p) for p in index_prefix_ignore_list):
                continue
            count = core_info.get("index", {}).get("numDocs", 0)
            index_dict[core_name] = {"count": str(count), "index": core_name}
        return index_dict

    def _get_doc_count(self, cluster: Cluster, collection: str) -> int:
        try:
            r = requests.get(
                f"{cluster.endpoint}/solr/{collection}/select?q=*:*&rows=0&wt=json", timeout=10)
            r.raise_for_status()
            return r.json().get("response", {}).get("numFound", 0)
        except Exception:
            return 0

    def clear_index_templates(self, cluster: Cluster, **kwargs):
        pass
