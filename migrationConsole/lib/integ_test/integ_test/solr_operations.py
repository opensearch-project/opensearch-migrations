import logging
import requests
import time

from .default_operations import DefaultOperationsLibrary
from console_link.models.cluster import Cluster

logger = logging.getLogger(__name__)


class SolrOperationsLibrary(DefaultOperationsLibrary):
    """Operations library for Solr clusters (SolrCloud and standalone)."""

    def _is_solr_cloud(self, cluster: Cluster) -> bool:
        """Detect SolrCloud vs standalone by probing the Collections API."""
        try:
            r = requests.get(
                f"{cluster.endpoint}/solr/admin/collections?action=LIST&wt=json", timeout=5)
            return r.status_code == 200
        except Exception:
            return False

    def _get_solr_major_version(self, cluster: Cluster) -> int:
        """Return the Solr major version integer (e.g. 6, 7, 8, 9). Defaults to 8 on failure."""
        try:
            r = requests.get(
                f"{cluster.endpoint}/solr/admin/info/system?wt=json", timeout=10)
            version_str = r.json()["lucene"]["solr-spec-version"]
            return int(version_str.split(".")[0])
        except Exception:
            return 8

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

    def bulk_create_documents(self, index_name: str, docs: list, cluster: Cluster, **kwargs):
        """Index a list of documents in a single /update call, with commit=true at the end.

        Each doc must have an 'id' field. Returns the last response.
        """
        if not docs:
            return None
        r = requests.post(
            f"{cluster.endpoint}/solr/{index_name}/update?commit=true",
            json=docs,
            headers={'Content-Type': 'application/json'},
            timeout=60
        )
        r.raise_for_status()
        return r

    def get_document(self, index_name: str, doc_id: str, cluster: Cluster, doc_type="_doc",
                     max_attempts=5, delay=2.0, **kwargs):
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

    def get_doc_count(self, index_name: str, cluster: Cluster, **kwargs) -> int:
        """Return total doc count for a Solr collection/core."""
        r = requests.get(
            f"{cluster.endpoint}/solr/{index_name}/select?q=*:*&rows=0&wt=json",
            timeout=10
        )
        r.raise_for_status()
        return r.json().get("response", {}).get("numFound", 0)

    def create_index(self, index_name: str, cluster: Cluster, num_shards: int = 1,
                     replication_factor: int = 1, **kwargs):
        """Create a Solr collection (SolrCloud) or core (standalone).

        In SolrCloud mode, uses the Collections API to create a collection with
        the specified shard/replication topology — this reflects what customers
        actually run in production.

        In standalone mode, creates a core by copying the 'dummy' core config.
        """
        if self._is_solr_cloud(cluster):
            solr_major = self._get_solr_major_version(cluster)

            # Build the collection-creation URL with version-appropriate parameters.
            #
            # maxShardsPerNode: required on single-node clusters in 6.x–8.x to allow
            # num_shards > 1; the parameter was removed entirely in Solr 9.0 and
            # passing it causes a 400 error on 9.x+.
            #
            # collection.configName: the _default configset was introduced in Solr 7.0.
            # In Solr 6.x the equivalent is data_driven_schema_configs, which the
            # Docker image bootstraps into ZooKeeper on first start.
            params = (
                f"action=CREATE"
                f"&name={index_name}"
                f"&numShards={num_shards}"
                f"&replicationFactor={replication_factor}"
                f"&wt=json"
            )
            if solr_major < 9:
                params += f"&maxShardsPerNode={max(num_shards, replication_factor)}"
            if solr_major < 7:
                params += "&collection.configName=data_driven_schema_configs"

            url = f"{cluster.endpoint}/solr/admin/collections?{params}"

            # Retry loop — collection creation is occasionally flaky while ZK settles.
            last_err = None
            for attempt in range(1, 11):
                try:
                    r = requests.get(url, timeout=60)
                    if r.status_code == 200 and r.json().get("responseHeader", {}).get("status") == 0:
                        logger.info(f"Created SolrCloud collection '{index_name}' "
                                    f"({num_shards} shards, rf={replication_factor}, "
                                    f"solr_major={solr_major})")
                        return r
                    last_err = f"status={r.status_code} body={r.text[:300]}"
                except Exception as e:
                    last_err = str(e)
                time.sleep(3)
            raise AssertionError(f"Failed to create SolrCloud collection '{index_name}' "
                                 f"after 10 attempts: {last_err}")

        # Standalone Solr — create a core by copying the 'dummy' core config.
        r = requests.get(
            f"{cluster.endpoint}/solr/admin/cores?action=CREATE"
            f"&name={index_name}"
            f"&instanceDir=/var/solr/data/{index_name}"
            f"&config=/var/solr/data/dummy/conf/solrconfig.xml"
            f"&schema=/var/solr/data/dummy/conf/managed-schema",
            timeout=30
        )
        r.raise_for_status()
        return r

    def get_all_index_details(self, cluster: Cluster, index_prefix_ignore_list=None, **kwargs):
        """Return a dict of {name: {"count": str, "index": name}} for all Solr collections/cores."""
        collections = []
        if self._is_solr_cloud(cluster):
            try:
                r = requests.get(
                    f"{cluster.endpoint}/solr/admin/collections?action=LIST&wt=json", timeout=10)
                r.raise_for_status()
                collections = r.json().get("collections", [])
            except Exception:
                collections = []
        if not collections:
            r = requests.get(
                f"{cluster.endpoint}/solr/admin/cores?action=STATUS&wt=json", timeout=10)
            r.raise_for_status()
            collections = list(r.json().get("status", {}).keys())

        index_dict = {}
        for name in collections:
            if index_prefix_ignore_list and any(name.startswith(p) for p in index_prefix_ignore_list):
                continue
            count = self.get_doc_count(name, cluster)
            index_dict[name] = {"count": str(count), "index": name}
        return index_dict

    def clear_index_templates(self, cluster: Cluster, **kwargs):
        pass
