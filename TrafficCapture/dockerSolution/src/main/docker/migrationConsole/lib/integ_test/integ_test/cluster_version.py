import re
from .elasticsearch_operations import (ElasticsearchV5_XOperationsLibrary, ElasticsearchV6_XOperationsLibrary,
                                       ElasticsearchV7_XOperationsLibrary)
from .opensearch_operations import OpensearchV1_XOperationsLibrary, OpensearchV2_XOperationsLibrary


class ClusterVersion:
    pattern = re.compile(r"^(ES|OS)_([0-9]+)\.([0-9]+|x|X)$")

    def __init__(self, version_str: str):
        match = self.pattern.match(version_str)
        if not match:
            raise ValueError(f"Invalid version format: {version_str}. Cluster versions must be in format ES_x.y or "
                             f"OS_x.y, where y is a number or 'x' for any minor version.")

        self.cluster_type = match.group(1)
        self.major_version = int(match.group(2))

        minor_version = match.group(3)
        if minor_version.lower() == 'x':
            self.minor_version = 'x'
        else:
            self.minor_version = int(minor_version)

    def __str__(self):
        return f"{self.cluster_type}_{self.major_version}.{self.minor_version}"


ElasticsearchV5_X = ClusterVersion("ES_5.x")
ElasticsearchV6_X = ClusterVersion("ES_6.x")
ElasticsearchV7_X = ClusterVersion("ES_7.x")
OpensearchV1_X = ClusterVersion("OS_1.x")
OpensearchV2_X = ClusterVersion("OS_2.x")


def is_incoming_version_supported(limiting_version: ClusterVersion, incoming_version: ClusterVersion):
    if (limiting_version.cluster_type == incoming_version.cluster_type and
            limiting_version.major_version == incoming_version.major_version):
        if isinstance(limiting_version.minor_version, str):
            return True
        else:
            return limiting_version.minor_version == incoming_version.minor_version
    return False


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
