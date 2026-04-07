from .default_operations import DefaultOperationsLibrary
from console_link.models.cluster import Cluster

import logging

logger = logging.getLogger(__name__)


class SolrOperationsLibrary(DefaultOperationsLibrary):
    """Operations library for Solr clusters (standalone mode)."""

    def create_document(self, index_name: str, doc_id: str, cluster: Cluster, data: dict = None,
                        doc_type="_doc", **kwargs):
        if data is None:
            data = {'title': 'Test Document', 'content': 'Sample document for testing.'}
        data['id'] = doc_id
        import requests
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
        import requests
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
        """Create a Solr core (standalone mode) via the Core Admin API."""
        import requests
        r = requests.get(
            f"{cluster.endpoint}/solr/admin/cores?action=CREATE"
            f"&name={index_name}&configSet=_default",
            timeout=30
        )
        r.raise_for_status()
        return r

    def get_all_index_details(self, cluster: Cluster, index_prefix_ignore_list=None, **kwargs):
        import requests
        # Use Core Admin API for standalone Solr
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

    def clear_index_templates(self, cluster: Cluster, **kwargs):
        pass
