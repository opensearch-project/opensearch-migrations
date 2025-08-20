import logging
from ..cluster_version import ElasticsearchV5_X, ElasticsearchV8_X, OpensearchV2_X
from .ma_argo_test_base import MATestBase, MigrationType

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
    def __init__(self, source_version: str, target_version: str, unique_id: str):
        allow_combinations = [
            (ElasticsearchV5_X, OpensearchV2_X),
            (ElasticsearchV8_X, OpensearchV2_X),
        ]
        description = "Run OpenSearch Benchmark tests and then runs metadata and backfill."
        super().__init__(source_version=source_version,
                         target_version=target_version,
                         unique_id=unique_id,
                         description=description,
                         allow_source_target_combinations=allow_combinations,
                         migrations_required=[MigrationType.BACKFILL, MigrationType.METADATA])

    def prepare_clusters(self):
        # Run OSB workloads against source cluster
        self.source_operations.run_test_benchmarks(cluster=self.source_cluster)

    def workflow_perform_migrations(self, timeout_seconds: int = 300):
        super().workflow_perform_migrations(timeout_seconds=timeout_seconds)

    def verify_clusters(self):
        self.target_operations.check_doc_counts_match(cluster=self.target_cluster,
                                                      expected_index_details=full_indices,
                                                      delay=5,
                                                      max_attempts=30)
