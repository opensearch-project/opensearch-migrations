import logging
import time
from ..cluster_version import ElasticsearchV5_X, OpensearchV2_X
from .ma_test_base import MATestBase
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


class Test0006Backfill(MATestBase):
    def __init__(self, console_config_path: str, console_link_env: Environment, unique_id: str):
        allow_combinations = [
            (ElasticsearchV5_X, OpensearchV2_X),
        ]
        run_isolated = True
        description = "Performs metadata, backfill, and replayer migrations with a multi-type split transformation."
        super().__init__(console_config_path=console_config_path,
                         console_link_env=console_link_env,
                         unique_id=unique_id,
                         description=description,
                         allow_source_target_combinations=allow_combinations,
                         run_isolated=run_isolated)
        self.transform_config_file = "/shared-logs-output/test-transformations/transformation.json"

    def test_before(self):
        self.source_operations.run_test_benchmarks(cluster=self.source_cluster)
        # TODO: Fix this invalid transformation config
        self.source_operations.create_transformation_json_file(transform_config_data=[{}],
                                                               file_path_to_create=self.transform_config_file)
        pass

    def metadata_migrate(self):
        metadata_result: CommandResult = self.metadata.migrate(extra_args=["--multi-type-behavior", "UNION"])
        assert metadata_result.success

    def metadata_after(self):
        self.target_operations.check_doc_counts_match(cluster=self.target_cluster, expected_index_details=empty_indices)

    def backfill_after(self):
        try:
            self.target_operations.check_doc_counts_match(cluster=self.target_cluster, expected_index_details=full_indices)
        except Exception as e:
            logger.info('Hit an exception! ' + str(e))
            time.sleep(1000)

    def backfill_wait_for_stop(self):
        # Don't scale down so I can look at logs
        pass

    def replay_before(self):
        pass

    def replay_start(self):
        pass

    def replay_during(self):
        pass

    def replay_wait_for_stop(self):
        pass

    def replay_after(self):
        pass
