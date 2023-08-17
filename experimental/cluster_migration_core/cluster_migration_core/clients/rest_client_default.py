import json

from cluster_migration_core.clients.rest_client_base import RESTClientBase
import cluster_migration_core.clients.rest_ops as ops


class RESTClientDefault(RESTClientBase):
    """
    Default REST client for interfacing w/ Elasticsearch and OpenSearch clusters.

    Given our current limited knowledge of where REST incompatibilities will pop up between versions, we will
    arbitrarily assume that the interface that OpenSearch 1.3.6 presents is our baseline that all other versions will
    conform to/deviate from.  If this later turns out to be a poor assumption, we can iterate on this code.  We'll know
    we need to re-examine this assumption if we end up with a large number of version-specific client implementations.

    You might ask, "why make a rest client at all instead of using one of the client SDKs?"  The answer to that is
    we're trying to avoid introducing another source of bugs/incompatibility (language-specific SDKs) on top of those
    found in the core engine's REST interface.  Additionally, we believe that many customers write their own clients
    against the REST interface, so writing one for the UTF mimics their use-case more closely.  As always, additional
    experience and feedback may dictate we pivot in the future.
    """

    def get_node_info(self, port: int) -> ops.RESTResponse:
        """
        Get basic about the specific node you hit, such as version.
        """
        rest_path = ops.RESTPath(port=port)
        return ops.perform_get(rest_path=rest_path)

    def get_nodes_status(self, port: int) -> ops.RESTResponse:
        """
        Get info on the cluster's nodes.
        """
        rest_path = ops.RESTPath(port=port, suffix="_cat/nodes")
        params = {"v": "true", "pretty": "true"}
        return ops.perform_get(rest_path=rest_path, params=params)

    def get_doc_by_id(self, port: int, index: str, doc_id: str) -> ops.RESTResponse:
        """
        Get a document by its ID
        """
        rest_path = ops.RESTPath(port=port, suffix=f"{index}/_doc/{doc_id}")
        params = {"pretty": "true"}

        return ops.perform_get(rest_path=rest_path, params=params)

    def create_an_index(self, port: int, index: str) -> ops.RESTResponse:
        """
        Creates an index
        """
        rest_path = ops.RESTPath(port=port, suffix=index)
        params = {"pretty": "true"}

        return ops.perform_post(rest_path=rest_path, params=params)

    def delete_an_index(self, port: int, index: str) -> ops.RESTResponse:
        """
        Deletes an index
        """
        rest_path = ops.RESTPath(port=port, suffix=index)
        params = {"pretty": "true"}

        return ops.perform_delete(rest_path=rest_path, params=params)

    def post_doc_to_index(self, port: int, index: str, doc: dict) -> ops.RESTResponse:
        """
        Post a single document to an index
        """
        rest_path = ops.RESTPath(port=port, suffix=f"{index}/_doc")
        params = {"pretty": "true"}
        headers = {"Content-Type": "application/json"}

        return ops.perform_post(rest_path=rest_path, data=json.dumps(doc), headers=headers, params=params)

    def count_docs_in_index(self, port: int, index: str) -> ops.RESTResponse:
        """
        Count documents in an index
        """
        rest_path = ops.RESTPath(port=port, suffix=f"{index}/_count")
        params = {"pretty": "true"}

        return ops.perform_get(rest_path=rest_path, params=params)

    def refresh_index(self, port: int, index: str) -> ops.RESTResponse:
        """
        Refresh an index
        """
        rest_path = ops.RESTPath(port=port, suffix=f"{index}/_refresh")
        params = {"pretty": "true"}

        return ops.perform_post(rest_path=rest_path, params=params)

    def create_snapshot(self, port: int, repo: str, snapshot_id: str) -> ops.RESTResponse:
        """
        Create a snapshot of the cluster into a repo by synchronously blocking on completion
        """
        rest_path = ops.RESTPath(port=port, suffix=f"_snapshot/{repo}/{snapshot_id}")
        params = {"pretty": "true", "wait_for_completion": "true"}

        return ops.perform_post(rest_path=rest_path, params=params)

    def get_snapshot_by_id(self, port: int, repo: str, snapshot_id: str) -> ops.RESTResponse:
        """
        Get a snapshot by its ID
        """
        rest_path = ops.RESTPath(port=port, suffix=f"_snapshot/{repo}/{snapshot_id}")
        params = {"pretty": "true"}
        return ops.perform_get(rest_path=rest_path, params=params)

    def get_snapshots_all(self, port: int, repo: str) -> ops.RESTResponse:
        """
        Get all snapshots from a repo
        """
        rest_path = ops.RESTPath(port=port, suffix=f"_snapshot/{repo}/_all")
        params = {"pretty": "true"}
        return ops.perform_get(rest_path=rest_path, params=params)

    def register_snapshot_dir(self, port: int, repo: str, dir_path: str) -> ops.RESTResponse:
        """
        Register a directory on the node as the place to store snapshots for an snapshot repo
        """
        rest_path = ops.RESTPath(port=port, suffix=f"_snapshot/{repo}")
        headers = {"Content-Type": "application/json"}

        register_args = {
            "type": "fs",
            "settings": {
                "location": dir_path
            }
        }

        return ops.perform_post(rest_path=rest_path, data=json.dumps(register_args), headers=headers)

    def restore_snapshot(self, port: int, repo: str, snapshot_id: int) -> ops.RESTResponse:
        """
        Restore a snapshot by synchronously blocking on completion
        """
        rest_path = ops.RESTPath(port=port, suffix=f"_snapshot/{repo}/{snapshot_id}/_restore")
        params = {"pretty": "true", "wait_for_completion": "true"}
        return ops.perform_post(rest_path=rest_path, params=params)
