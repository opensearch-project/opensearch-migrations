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


class _LegacyTypedOperationsLibrary(DefaultOperationsLibrary):
    """Shared base for pre-ES 6 libraries where a document can be typed.

    Mirrors Java `ClusterOperations.docTypePathOrDefault` + `defaultDocType`:
    caller-supplied type is honored for document paths; falls back to the
    library's default doc type (`doc`). Mappings come back nested under a
    type name in these versions.
    """

    def default_doc_type(self) -> str:
        return "doc"

    def resolve_doc_type(self, type_override):
        return type_override if type_override else self.default_doc_type()

    def uses_typed_mappings(self) -> bool:
        return True


class ElasticsearchV1_XOperationsLibrary(_LegacyTypedOperationsLibrary):
    pass


class ElasticsearchV2_XOperationsLibrary(_LegacyTypedOperationsLibrary):
    pass


class ElasticsearchV5_XOperationsLibrary(_LegacyTypedOperationsLibrary):

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
    # ES 6.2+ uses `_doc`. We don't distinguish 6.0/6.1 here because the test
    # framework only exercises 6.8 (via k8sLocalDeployment's ES_6.8 choice).
    pass


class ElasticsearchV7_XOperationsLibrary(DefaultOperationsLibrary):
    pass


class ElasticsearchV8_XOperationsLibrary(DefaultOperationsLibrary):
    # ES 8 removed per-document types; `default_doc_type()` (`_doc`) is the only
    # valid value. Inherits the correct behavior from DefaultOperationsLibrary.
    pass
