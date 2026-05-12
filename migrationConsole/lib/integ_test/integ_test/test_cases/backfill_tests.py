import logging
from ..cluster_version import (
    ElasticsearchV1_X, ElasticsearchV2_X,
    RFS_MIGRATION_COMBINATIONS,
)
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)
full_indices = {
    "geonames": {"count": "1000"},
    "logs-221998": {"count": "1000"},
    "logs-211998": {"count": "1000"},
    "logs-231998": {"count": "1000"},
    "logs-241998": {"count": "1000"},
    "logs-181998": {"count": "1000"},
    "logs-201998": {"count": "1000"},
    "logs-191998": {"count": "1000"},
    "sonested": {"count": "1000"},
    "nyc_taxis": {"count": "1000"}
}


class Test0006OpenSearchBenchmarkBackfill(MATestBase):
    def __init__(self, user_args: MATestUserArguments):
        # OSB doesn't support ES 1.x/2.x or OS→OS paths, so exclude those from the full RFS matrix.
        allow_combinations = [
            combo for combo in RFS_MIGRATION_COMBINATIONS
            if combo[0] not in (ElasticsearchV1_X, ElasticsearchV2_X) and
            combo[0].cluster_type != "OS"
        ]
        description = "Run OpenSearch Benchmark tests and then runs metadata and backfill."
        super().__init__(user_args=user_args,
                         description=description,
                         allow_source_target_combinations=allow_combinations,
                         migrations_required=[MigrationType.BACKFILL, MigrationType.METADATA])

    def prepare_clusters(self):
        # Run OSB workloads against source cluster
        self.source_operations.run_test_benchmarks(cluster=self.source_cluster)

    def verify_clusters(self):
        self.target_operations.check_doc_counts_match(cluster=self.target_cluster,
                                                      expected_index_details=full_indices,
                                                      delay=5,
                                                      max_attempts=30)
