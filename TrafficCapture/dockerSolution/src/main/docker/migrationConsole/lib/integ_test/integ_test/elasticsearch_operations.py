from .default_operations import DefaultOperationsLibrary

from console_link.models.cluster import HttpMethod, Cluster


class ElasticsearchV5_XOperationsLibrary(DefaultOperationsLibrary):
    def create_document(self, index_name: str, doc_id: str, cluster: Cluster, data: dict = None,
                        doc_type = "test_doc_type", **kwargs):
        super().create_document(index_name=index_name, doc_id=doc_id, cluster=cluster, data=data,
                                doc_type=doc_type, **kwargs)

    def get_document(self, index_name: str, doc_id: str, cluster: Cluster, doc_type = "test_doc_type", **kwargs):
        super().get_document(index_name=index_name, doc_id=doc_id, cluster=cluster, doc_type=doc_type, **kwargs)


class ElasticsearchV6_XOperationsLibrary(DefaultOperationsLibrary):
    pass


class ElasticsearchV7_XOperationsLibrary(DefaultOperationsLibrary):
    pass