import json
import os
from typing import Dict

from .cluster_version import ClusterVersion
from .default_operations import DefaultOperationsLibrary

from console_link.models.cluster import HttpMethod, Cluster


def get_type_mapping_union_transformation(multi_type_index_name: str, doc_type_1: str, doc_type_2: str, cluster_version: ClusterVersion) -> Dict:
    return {
            "TypeMappingSanitizationTransformerProvider": {
                "staticMappings": {
                    multi_type_index_name: {
                        doc_type_1: multi_type_index_name,
                        doc_type_2: multi_type_index_name
                    }
                },
                "sourceProperties": {
                    "version": {
                        "major": cluster_version.major_version,
                        "minor": cluster_version.minor_version
                    }
                }
            }
        }


class ElasticsearchV5_XOperationsLibrary(DefaultOperationsLibrary):
    # def create_document(self, index_name: str, doc_id: str, cluster: Cluster, data: dict = None,
    #                     doc_type = "test_doc_type", **kwargs):
    #     super().create_document(index_name=index_name, doc_id=doc_id, cluster=cluster, data=data,
    #                             doc_type=doc_type, **kwargs)
    #
    # def get_document(self, index_name: str, doc_id: str, cluster: Cluster, doc_type = "test_doc_type", **kwargs):
    #     super().get_document(index_name=index_name, doc_id=doc_id, cluster=cluster, doc_type=doc_type, **kwargs)

    def get_type_mapping_union_transformation(self, multi_type_index_name: str, doc_type_1: str, doc_type_2: str, cluster_version: ClusterVersion):
        return get_type_mapping_union_transformation(multi_type_index_name=multi_type_index_name,
                                                     doc_type_1=doc_type_1,
                                                     doc_type_2=doc_type_2,
                                                     cluster_version=cluster_version)


class ElasticsearchV6_XOperationsLibrary(DefaultOperationsLibrary):
    pass


class ElasticsearchV7_XOperationsLibrary(DefaultOperationsLibrary):
    pass