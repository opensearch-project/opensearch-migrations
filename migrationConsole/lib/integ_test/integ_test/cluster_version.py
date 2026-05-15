import re


class ClusterVersion:
    pattern = re.compile(r"^(ES|OS|SOLR)_([0-9]+)\.([0-9]+|x|X)$")

    def __init__(self, version_str: str):
        match = self.pattern.match(version_str)
        if not match:
            raise ValueError(f"Invalid version format: {version_str}. Cluster versions must be in format ES_x.y, "
                             f"OS_x.y, or SOLR_x.y, where y is a number or 'x' for any minor version.")

        self.cluster_type = match.group(1)
        if self.cluster_type == "ES":
            self.full_cluster_type = "elasticsearch"
        elif self.cluster_type == "OS":
            self.full_cluster_type = "opensearch"
        else:
            self.full_cluster_type = "solr"
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
SolrV8_X = ClusterVersion("SOLR_8.x")


def build_combinations(sources, targets):
    """Return the Cartesian product of the given sources and targets as a list of tuples."""
    return [(s, t) for s in sources for t in targets]


# RFS backfill matrix. OS→OS is out of scope for this matrix today: the
# workflow surface for OS-source imports isn't exercised the same way as
# ES-source and would report all-skipped rows.
RFS_MIGRATION_COMBINATIONS = (
    build_combinations(
        [ElasticsearchV1_X, ElasticsearchV2_X, ElasticsearchV5_X,
         ElasticsearchV6_X, ElasticsearchV7_X],
        [OpensearchV1_X, OpensearchV2_X, OpensearchV3_X]) +
    build_combinations([ElasticsearchV8_X], [OpensearchV2_X, OpensearchV3_X])
)

# CDC migration matrix: Capture and Replay is supported from ES 5.x onwards
# (not ES 1.x/2.x). OS→OS excluded for the same reason as RFS above.
CDC_MIGRATION_COMBINATIONS = (
    build_combinations(
        [ElasticsearchV5_X, ElasticsearchV6_X, ElasticsearchV7_X],
        [OpensearchV1_X, OpensearchV2_X, OpensearchV3_X]) +
    build_combinations([ElasticsearchV8_X], [OpensearchV2_X, OpensearchV3_X])
)


def is_incoming_version_supported(limiting_version: ClusterVersion, incoming_version: ClusterVersion):
    if (limiting_version.cluster_type == incoming_version.cluster_type and
            limiting_version.major_version == incoming_version.major_version):
        if isinstance(limiting_version.minor_version, str):
            return True
        else:
            return limiting_version.minor_version == incoming_version.minor_version
    return False
