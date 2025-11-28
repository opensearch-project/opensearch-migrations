import logging
from ..cluster_version import (
    ElasticsearchV5_X, ElasticsearchV7_X, ElasticsearchV6_X, ElasticsearchV8_X,
    OpensearchV1_X, OpensearchV2_X, OpensearchV3_X
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
        allow_combinations = [
            (ElasticsearchV5_X, OpensearchV1_X),
            (ElasticsearchV5_X, OpensearchV2_X),
            (ElasticsearchV5_X, OpensearchV3_X),
            (ElasticsearchV6_X, OpensearchV1_X),
            (ElasticsearchV6_X, OpensearchV2_X),
            (ElasticsearchV6_X, OpensearchV3_X),
            (ElasticsearchV7_X, OpensearchV1_X),
            (ElasticsearchV7_X, OpensearchV2_X),
            (ElasticsearchV7_X, OpensearchV3_X),
            (ElasticsearchV8_X, OpensearchV1_X),
            (ElasticsearchV8_X, OpensearchV2_X),
            (ElasticsearchV8_X, OpensearchV3_X),
        ]
        description = "Run OpenSearch Benchmark tests and then runs metadata and backfill."
        super().__init__(user_args=user_args,
                         description=description,
                         allow_source_target_combinations=allow_combinations,
                         migrations_required=[MigrationType.BACKFILL, MigrationType.METADATA])

    def prepare_clusters(self):
        # Run OSB workloads against source cluster
        self.source_operations.run_test_benchmarks(cluster=self.source_cluster)

    def workflow_perform_migrations(self, timeout_seconds: int = 600):
        super().workflow_perform_migrations(timeout_seconds=timeout_seconds)

    def verify_clusters(self):
        self.target_operations.check_doc_counts_match(cluster=self.target_cluster,
                                                      expected_index_details=full_indices,
                                                      delay=5,
                                                      max_attempts=30)
