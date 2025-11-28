import re


class ClusterVersion:
    pattern = re.compile(r"^(ES|OS)_([0-9]+)\.([0-9]+|x|X)$")

    def __init__(self, version_str: str):
        match = self.pattern.match(version_str)
        if not match:
            raise ValueError(f"Invalid version format: {version_str}. Cluster versions must be in format ES_x.y or "
                             f"OS_x.y, where y is a number or 'x' for any minor version.")

        self.cluster_type = match.group(1)
        self.full_cluster_type = "elasticsearch" if self.cluster_type == "ES" else "opensearch"
        self.major_version = int(match.group(2))

        minor_version = match.group(3)
        if minor_version.lower() == 'x':
            self.minor_version = 'x'
        else:
            self.minor_version = int(minor_version)

    def __str__(self):
        return f"{self.cluster_type}_{self.major_version}.{self.minor_version}"


ElasticsearchV1_X = ClusterVersion("ES_1.x")
ElasticsearchV2_X = ClusterVersion("ES_2.x")
ElasticsearchV5_X = ClusterVersion("ES_5.x")
ElasticsearchV6_X = ClusterVersion("ES_6.x")
ElasticsearchV7_X = ClusterVersion("ES_7.x")
ElasticsearchV8_X = ClusterVersion("ES_8.x")
OpensearchV1_X = ClusterVersion("OS_1.x")
OpensearchV2_X = ClusterVersion("OS_2.x")
OpensearchV3_X = ClusterVersion("OS_3.x")


def is_incoming_version_supported(limiting_version: ClusterVersion, incoming_version: ClusterVersion):
    if (limiting_version.cluster_type == incoming_version.cluster_type and
            limiting_version.major_version == incoming_version.major_version):
        if isinstance(limiting_version.minor_version, str):
            return True
        else:
            return limiting_version.minor_version == incoming_version.minor_version
    return False
