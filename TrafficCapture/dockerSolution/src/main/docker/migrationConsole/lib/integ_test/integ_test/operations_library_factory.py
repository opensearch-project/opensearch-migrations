from .cluster_version import (ClusterVersion, ElasticsearchV5_X, ElasticsearchV6_X, ElasticsearchV7_X, OpensearchV1_X,
                              OpensearchV2_X, is_incoming_version_supported)
from .elasticsearch_operations import (ElasticsearchV5_XOperationsLibrary, ElasticsearchV6_XOperationsLibrary,
                                       ElasticsearchV7_XOperationsLibrary)
from .opensearch_operations import OpensearchV1_XOperationsLibrary, OpensearchV2_XOperationsLibrary


def get_operations_library_by_version(version: ClusterVersion):
    if is_incoming_version_supported(limiting_version=ElasticsearchV5_X, incoming_version=version):
        return ElasticsearchV5_XOperationsLibrary()
    elif is_incoming_version_supported(limiting_version=ElasticsearchV6_X, incoming_version=version):
        return ElasticsearchV6_XOperationsLibrary()
    elif is_incoming_version_supported(limiting_version=ElasticsearchV7_X, incoming_version=version):
        return ElasticsearchV7_XOperationsLibrary()
    elif is_incoming_version_supported(limiting_version=OpensearchV1_X, incoming_version=version):
        return OpensearchV1_XOperationsLibrary()
    elif is_incoming_version_supported(limiting_version=OpensearchV2_X, incoming_version=version):
        return OpensearchV2_XOperationsLibrary()
    else:
        raise Exception(f"Unsupported cluster version: {version}")
