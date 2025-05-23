import logging
from ..cluster_version import ElasticsearchV5_X, ElasticsearchV8_X, OpensearchV2_X
from .ma_test_base import MATestBase, MigrationType
from console_link.environment import Environment
from console_link.models.command_result import CommandResult

logger = logging.getLogger(__name__)
empty_indices = {
    "geonames": {"count": "0"},
    "logs-221998": {"count": "0"},
    "logs-211998": {"count": "0"},
    "logs-231998": {"count": "0"},
    "logs-241998": {"count": "0"},
    "logs-181998": {"count": "0"},
    "logs-201998": {"count": "0"},
    "logs-191998": {"count": "0"},
    "sonested": {"count": "0"},
    "nyc_taxis": {"count": "0"}
}
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
empty_indices_no_taxi = {
    "geonames": {"count": "0"},
    "logs-221998": {"count": "0"},
    "logs-211998": {"count": "0"},
    "logs-231998": {"count": "0"},
    "logs-241998": {"count": "0"},
    "logs-181998": {"count": "0"},
    "logs-201998": {"count": "0"},
    "logs-191998": {"count": "0"},
    "sonested": {"count": "0"}
}
full_indices_no_taxi = {
    "geonames": {"count": "1000"},
    "logs-221998": {"count": "1000"},
    "logs-211998": {"count": "1000"},
    "logs-231998": {"count": "1000"},
    "logs-241998": {"count": "1000"},
    "logs-181998": {"count": "1000"},
    "logs-201998": {"count": "1000"},
    "logs-191998": {"count": "1000"},
    "sonested": {"count": "2977"}
}


class Test0006OpenSearchBenchmarkBackfill(MATestBase):
    def __init__(self, console_config_path: str, console_link_env: Environment, unique_id: str):
        allow_combinations = [
            (ElasticsearchV5_X, OpensearchV2_X),
            (ElasticsearchV8_X, OpensearchV2_X),
        ]
        run_isolated = True
        description = "Run OpenSearch Benchmark tests and then runs metadata and backfill."
        super().__init__(console_config_path=console_config_path,
                         console_link_env=console_link_env,
                         unique_id=unique_id,
                         description=description,
                         allow_source_target_combinations=allow_combinations,
                         migrations_required=[MigrationType.BACKFILL, MigrationType.METADATA],
                         run_isolated=run_isolated)
        self.transform_config_file = "/shared-logs-output/test-transformations/transformation.json"

    def test_before(self):
        self.source_operations.run_test_benchmarks(cluster=self.source_cluster)
        # Current test structure requires a transformation config
        union_transform = self.source_operations.get_type_mapping_only_union_transformation(
            cluster_version=self.source_version
        )
        self.source_operations.create_transformation_json_file(transform_config_data=[union_transform],
                                                               file_path_to_create=self.transform_config_file)

    def metadata_migrate(self):
        metadata_result: CommandResult = self.metadata.migrate(extra_args=["--multi-type-behavior", "UNION"])
        assert metadata_result.success

    def metadata_after(self):
        self.target_operations.check_doc_counts_match(cluster=self.target_cluster,
                                                      expected_index_details=empty_indices,
                                                      delay=3,
                                                      max_attempts=20)

    def backfill_wait_for_stop(self):
        self.target_operations.check_doc_counts_match(cluster=self.target_cluster,
                                                      expected_index_details=full_indices,
                                                      delay=5,
                                                      max_attempts=30)
        return super().backfill_wait_for_stop()


class Test0007EndToEndTestForES8WithOSBenchmarks(MATestBase):
    def __init__(self, console_config_path: str, console_link_env: Environment, unique_id: str):
        allow_combinations = [
            (ElasticsearchV8_X, OpensearchV2_X)
        ]
        run_isolated = True
        description = "For ES 8x, run OpenSearch Benchmark tests and then runs metadata and backfill."
        super().__init__(console_config_path=console_config_path,
                         console_link_env=console_link_env,
                         unique_id=unique_id,
                         description=description,
                         allow_source_target_combinations=allow_combinations,
                         migrations_required=[MigrationType.BACKFILL, MigrationType.METADATA],
                         run_isolated=run_isolated)
        self.transform_config_file = "/shared-logs-output/test-transformations/transformation.json"
    
    def test_before(self):
        # Ingest OSBenchmarks data into source cluster
        self.source_operations.run_test_benchmarks(cluster=self.source_cluster)

        # Disable bloom filter for each index in benchmarks
        # and refresh indexes
        for index_name in empty_indices:
            self.source_operations.disable_bloom(cluster=self.source_cluster, index_name=index_name)
            self.source_operations.refresh(cluster=self.source_cluster, index_name=index_name)

        # Use no-op transformer
        noop_transform = self.source_operations.get_noop_transformation()
        self.source_operations.create_transformation_json_file(
            transform_config_data=[noop_transform],
            file_path_to_create=self.transform_config_file
        )

    def metadata_migrate(self):
        comma_separated_indices = ",".join(empty_indices.keys())
        metadata_result: CommandResult = self.metadata.migrate(
            extra_args=[
                "--transformer-config-file", self.transform_config_file,
                "--index-allowlist", comma_separated_indices,
                "--component-template-allowlist", "none",
                "--index-template-allowlist", "none"
            ]
        )
        assert metadata_result.success

    def metadata_after(self):
        self.target_operations.check_doc_counts_match(
            cluster=self.target_cluster,
            expected_index_details=empty_indices,
            delay=3,
            max_attempts=20
        )

    def backfill_wait_for_stop(self):
        self.target_operations.check_doc_counts_match(
            cluster=self.target_cluster,
            expected_index_details=full_indices,
            delay=5,
            max_attempts=30
        )
        return super().backfill_wait_for_stop()


class Test0008EndToEndTestForES8WithSimpleDocs(MATestBase):
    def __init__(self, console_config_path: str, console_link_env: Environment, unique_id: str):
        allow_combinations = [
            (ElasticsearchV8_X, OpensearchV2_X)
        ]
        run_isolated = True
        description = "For ES 8x test using 2 simple docs in 1 index to perform metadata and backfill."
        super().__init__(console_config_path=console_config_path,
                         console_link_env=console_link_env,
                         unique_id=unique_id,
                         description=description,
                         allow_source_target_combinations=allow_combinations,
                         migrations_required=[MigrationType.BACKFILL, MigrationType.METADATA],
                         run_isolated=run_isolated)
        self.transform_config_file = "/shared-logs-output/test-transformations/transformation.json"
        self.index_name = f"test_0010_{self.unique_id}"
        self.doc_id1 = "test_1000_doc"
        self.doc_id2 = "test_2000_doc"

    def test_before(self):
        # Create a single index
        self.source_operations.create_index(cluster=self.source_cluster, index_name=self.index_name)
        self.source_operations.disable_bloom(cluster=self.source_cluster, index_name=self.index_name)
        self.source_operations.refresh(cluster=self.source_cluster, index_name=self.index_name)
        self.source_operations.get_index(cluster=self.source_cluster, index_name=self.index_name)

        # Create a single document
        self.source_operations.create_document(
            cluster=self.source_cluster,
            index_name=self.index_name,
            doc_id=self.doc_id1
        )
        self.source_operations.get_document(
            cluster=self.source_cluster,
            index_name=self.index_name,
            doc_id=self.doc_id1
        )

        # Create a single document
        self.source_operations.create_document(
            cluster=self.source_cluster,
            index_name=self.index_name,
            doc_id=self.doc_id2
        )
        self.source_operations.get_document(
            cluster=self.source_cluster,
            index_name=self.index_name,
            doc_id=self.doc_id2
        )

        # Use no-op transformer
        noop_transform = self.source_operations.get_noop_transformation()
        self.source_operations.create_transformation_json_file(
            transform_config_data=[noop_transform],
            file_path_to_create=self.transform_config_file
        )

    def metadata_migrate(self):
        metadata_result: CommandResult = self.metadata.migrate(
            extra_args=[
                "--transformer-config-file", self.transform_config_file,
                "--index-allowlist", self.index_name,
                "--component-template-allowlist", "none",
                "--index-template-allowlist", "none"
            ]
        )
        assert metadata_result.success

    def metadata_after(self):
        self.target_operations.get_index(
            cluster=self.target_cluster,
            index_name=self.index_name,
            max_attempts=5,
            delay=2.0
        )

    def backfill_wait_for_stop(self):
        return super().backfill_wait_for_stop()
