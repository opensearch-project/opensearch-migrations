from typing import Dict

from .cluster_version import ClusterVersion
from .default_operations import DefaultOperationsLibrary


def get_type_mapping_split_transformation(multi_type_index_name: str, doc_type_1: str, doc_type_2: str,
                                          split_index_name_1: str, split_index_name_2: str,
                                          cluster_version: ClusterVersion) -> Dict:
    return {
        "TypeMappingSanitizationTransformerProvider": {
            "staticMappings": {
                multi_type_index_name: {
                    doc_type_1: split_index_name_1,
                    doc_type_2: split_index_name_2
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


def get_type_mapping_union_transformation(multi_type_index_name: str, doc_type_1: str, doc_type_2: str,
                                          cluster_version: ClusterVersion) -> Dict:
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


class ElasticsearchV1_XOperationsLibrary(DefaultOperationsLibrary):
    pass


class ElasticsearchV2_XOperationsLibrary(DefaultOperationsLibrary):
    pass


class ElasticsearchV5_XOperationsLibrary(DefaultOperationsLibrary):

    def get_type_mapping_union_transformation(self, multi_type_index_name: str, doc_type_1: str, doc_type_2: str,
                                              cluster_version: ClusterVersion):
        return get_type_mapping_union_transformation(multi_type_index_name=multi_type_index_name,
                                                     doc_type_1=doc_type_1,
                                                     doc_type_2=doc_type_2,
                                                     cluster_version=cluster_version)

    def get_type_mapping_split_transformation(self, multi_type_index_name: str, doc_type_1: str, doc_type_2: str,
                                              split_index_name_1: str, split_index_name_2: str,
                                              cluster_version: ClusterVersion):
        return get_type_mapping_split_transformation(multi_type_index_name=multi_type_index_name,
                                                     doc_type_1=doc_type_1,
                                                     doc_type_2=doc_type_2,
                                                     split_index_name_1=split_index_name_1,
                                                     split_index_name_2=split_index_name_2,
                                                     cluster_version=cluster_version)

    def get_type_mapping_only_union_transformation(self, cluster_version: ClusterVersion):
        return {
            "TypeMappingSanitizationTransformerProvider": {
                "sourceProperties": {
                    "version": {
                        "major": cluster_version.major_version,
                        "minor": cluster_version.minor_version
                    }
                },
                "regexMappings": [{
                    "sourceIndexPattern": "(.*)",
                    "sourceTypePattern": ".*",
                    "targetIndexPattern": "$1"
                }]
            }
        }


class ElasticsearchV6_XOperationsLibrary(DefaultOperationsLibrary):
    pass


class ElasticsearchV7_XOperationsLibrary(DefaultOperationsLibrary):
    pass


class ElasticsearchV8_XOperationsLibrary(DefaultOperationsLibrary):
    pass
