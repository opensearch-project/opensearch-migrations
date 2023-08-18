from abc import ABC, abstractmethod
import logging

import cluster_migration_core.clients.rest_ops as ops


class RESTClientBase(ABC):
    """
    Abstract base class for the REST clients that inferface w/ the cluster.
    """

    def __init__(self):
        self.logger = logging.getLogger(__name__)

    # Node operations
    @abstractmethod
    def get_node_info(self, port: int) -> ops.RESTResponse:
        pass

    @abstractmethod
    def get_nodes_status(self, port: int) -> ops.RESTResponse:
        pass

    # Doc operations
    @abstractmethod
    def get_doc_by_id(self, port: int, index: str, doc_id: int) -> ops.RESTResponse:
        pass

    @abstractmethod
    def create_an_index(self, port: int, index: str) -> ops.RESTResponse:
        pass

    @abstractmethod
    def delete_an_index(self, port: int, index: str) -> ops.RESTResponse:
        pass

    @abstractmethod
    def post_doc_to_index(self, port: int, index: str, doc: dict) -> ops.RESTResponse:
        pass

    @abstractmethod
    def count_docs_in_index(self, port: int, index: str) -> ops.RESTResponse:
        pass

    @abstractmethod
    def refresh_index(self, port: int, index: str) -> ops.RESTResponse:
        pass

    # Snapshot operations
    @abstractmethod
    def create_snapshot(self, port: int, repo: str) -> ops.RESTResponse:
        pass

    @abstractmethod
    def get_snapshot_by_id(self, port: int, repo: str, snapshot_id: int) -> ops.RESTResponse:
        pass

    @abstractmethod
    def get_snapshots_all(self, port: int, repo: str) -> ops.RESTResponse:
        pass

    @abstractmethod
    def register_snapshot_dir(self, port: int, repo: str, dir_path: str) -> ops.RESTResponse:
        pass

    @abstractmethod
    def restore_snapshot(self, port: int, repo: str, snapshot_id: int) -> ops.RESTResponse:
        pass
