import logging
from ..common_utils import wait_for_service_status
from ..cluster_version import ElasticsearchV5_X, OpensearchV1_X, OpensearchV2_X
from .ma_test_base import MATestBase
from console_link.environment import Environment
from console_link.models.command_result import CommandResult
from console_link.models.replayer_base import ReplayStatus
from console_link.models.backfill_base import BackfillStatus

logger = logging.getLogger(__name__)


class Test0004MultiTypeUnionMigration(MATestBase):
    def __init__(self, console_config_path: str, console_link_env: Environment, unique_id: str):
        allow_combinations = [
            (ElasticsearchV5_X, OpensearchV1_X),
            (ElasticsearchV5_X, OpensearchV2_X),
        ]
        run_isolated = True
        super().__init__(console_config_path=console_config_path,
                         console_link_env=console_link_env,
                         unique_id=unique_id,
                         allow_source_target_combinations=allow_combinations,
                         run_isolated=run_isolated)
        self.index_name = f"test_0004_{self.unique_id}"
        self.doc_id1 = "test_0004_1"
        self.doc_id2 = "test_0004_2"
        self.doc_id3 = "test_0004_3"
        self.doc_id4 = "test_0004_4"
        self.doc_type1 = "sample_type1"
        self.doc_type2 = "sample_type2"
        self.sample_data1 = {
            'author': 'Alice Quantum',
            'published_date': '2025-03-11T12:00:00Z',
            'tags': ['quantum computing', 'technology', 'innovation', 'research'],
        }
        self.sample_data2 = {
            'title': 'Exploring Quantum Computing',
            'content': 'Quantum computing is an emerging field that leverages quantum phenomena to perform '
                       'computations at unprecedented speeds. This document explores the basic principles, '
                       'potential applications, and future challenges of this revolutionary technology.',
            'published_date': '2025-03-11T14:00:00Z'
        }
        self.transform_config_file = "/shared-logs-output/test-transformations/transformation.json"

    def test_before(self):
        union_transform = self.source_operations.get_type_mapping_union_transformation(
            multi_type_index_name=self.index_name,
            doc_type_1=self.doc_type1,
            doc_type_2=self.doc_type2,
            cluster_version=self.source_version
        )
        self.source_operations.create_transformation_json_file(transform_config_data=[union_transform],
                                                               file_path_to_create=self.transform_config_file)
        self.source_operations.create_and_retrieve_document(cluster=self.source_cluster, index_name=self.index_name,
                                                            doc_id=self.doc_id1, doc_type=self.doc_type1,
                                                            data=self.sample_data1)
        self.source_operations.create_and_retrieve_document(cluster=self.source_cluster, index_name=self.index_name,
                                                            doc_id=self.doc_id2, doc_type=self.doc_type2,
                                                            data=self.sample_data2)

    def metadata_migrate(self):
        metadata_result: CommandResult = self.metadata.migrate(extra_args=["--transformer-config-file",
                                                                           self.transform_config_file])
        assert metadata_result.success

    def metadata_after(self):
        self.target_operations.get_index(cluster=self.target_cluster, index_name=self.index_name, max_attempts=3,
                                         delay=2.0)
        # Get all keys from sample data
        expected_keys = set(self.sample_data1.keys()).union(set(self.sample_data2.keys()))
        self.target_operations.verify_index_mapping_properties(cluster=self.target_cluster, index_name=self.index_name,
                                                               expected_props=expected_keys)

    def backfill_wait_for_stop(self):
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.index_name,
                                            doc_id=self.doc_id1, max_attempts=10, delay=3.0)
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.index_name,
                                            doc_id=self.doc_id2, max_attempts=10, delay=3.0)
        backfill_stop_result: CommandResult = self.backfill.stop()
        assert backfill_stop_result.success
        wait_for_service_status(status_func=lambda: self.backfill.get_status(), desired_status=BackfillStatus.STOPPED)

    def replay_before(self):
        self.source_operations.create_and_retrieve_document(cluster=self.source_cluster, index_name=self.index_name,
                                                            doc_id=self.doc_id3, doc_type=self.doc_type1,
                                                            data=self.sample_data1)
        self.source_operations.create_and_retrieve_document(cluster=self.source_cluster, index_name=self.index_name,
                                                            doc_id=self.doc_id4, doc_type=self.doc_type2,
                                                            data=self.sample_data2)

    def replay_wait_for_stop(self):
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.index_name,
                                            doc_id=self.doc_id3, max_attempts=20, delay=3.0)
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.index_name,
                                            doc_id=self.doc_id4, max_attempts=20, delay=3.0)
        replayer_stop_result = self.replayer.stop()
        assert replayer_stop_result.success
        wait_for_service_status(status_func=lambda: self.replayer.get_status(), desired_status=ReplayStatus.STOPPED)


class Test0005MultiTypeSplitMigration(MATestBase):
    def __init__(self, console_config_path: str, console_link_env: Environment, unique_id: str):
        allow_combinations = [
            (ElasticsearchV5_X, OpensearchV1_X),
            (ElasticsearchV5_X, OpensearchV2_X),
        ]
        run_isolated = True
        super().__init__(console_config_path=console_config_path,
                         console_link_env=console_link_env,
                         unique_id=unique_id,
                         allow_source_target_combinations=allow_combinations,
                         run_isolated=run_isolated)
        self.index_name = f"test_0005_{self.unique_id}"
        self.split_index_name1 = f"test_0005_split_1_{self.unique_id}"
        self.split_index_name2 = f"test_0005_split_2_{self.unique_id}"
        self.doc_id1 = "test_0005_1"
        self.doc_id2 = "test_0005_2"
        self.doc_id3 = "test_0005_3"
        self.doc_id4 = "test_0005_4"
        self.doc_type1 = "sample_type1"
        self.doc_type2 = "sample_type2"
        self.sample_data1 = {
            'author': 'Alice Quantum',
            'published_date': '2025-03-11T12:00:00Z',
            'tags': ['quantum computing', 'technology', 'innovation', 'research'],
        }
        self.sample_data2 = {
            'title': 'Exploring Quantum Computing',
            'content': 'Quantum computing is an emerging field that leverages quantum phenomena to perform '
                       'computations at unprecedented speeds. This document explores the basic principles, '
                       'potential applications, and future challenges of this revolutionary technology.',
            'published_date': '2025-03-11T14:00:00Z'
        }
        self.transform_config_file = "/shared-logs-output/test-transformations/transformation.json"

    def test_before(self):
        split_transform = self.source_operations.get_type_mapping_split_transformation(
            multi_type_index_name=self.index_name,
            doc_type_1=self.doc_type1,
            doc_type_2=self.doc_type2,
            split_index_name_1=self.split_index_name1,
            split_index_name_2=self.split_index_name2,
            cluster_version=self.source_version
        )
        self.source_operations.create_transformation_json_file(transform_config_data=[split_transform],
                                                               file_path_to_create=self.transform_config_file)
        self.source_operations.create_and_retrieve_document(cluster=self.source_cluster, index_name=self.index_name,
                                                            doc_id=self.doc_id1, doc_type=self.doc_type1,
                                                            data=self.sample_data1)
        self.source_operations.create_and_retrieve_document(cluster=self.source_cluster, index_name=self.index_name,
                                                            doc_id=self.doc_id2, doc_type=self.doc_type2,
                                                            data=self.sample_data2)

    def metadata_migrate(self):
        metadata_result: CommandResult = self.metadata.migrate(extra_args=["--transformer-config-file",
                                                                           self.transform_config_file])
        assert metadata_result.success

    def metadata_after(self):
        self.target_operations.verify_index_mapping_properties(cluster=self.target_cluster,
                                                               index_name=self.split_index_name1,
                                                               expected_props=set(self.sample_data1.keys()))
        self.target_operations.verify_index_mapping_properties(cluster=self.target_cluster,
                                                               index_name=self.split_index_name2,
                                                               expected_props=set(self.sample_data2.keys()))

    def backfill_wait_for_stop(self):
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.split_index_name1,
                                            doc_id=self.doc_id1, max_attempts=10, delay=3.0)
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.split_index_name2,
                                            doc_id=self.doc_id2, max_attempts=10, delay=3.0)
        backfill_stop_result: CommandResult = self.backfill.stop()
        assert backfill_stop_result.success
        wait_for_service_status(status_func=lambda: self.backfill.get_status(), desired_status=BackfillStatus.STOPPED)

    def replay_before(self):
        self.source_operations.create_and_retrieve_document(cluster=self.source_cluster, index_name=self.index_name,
                                                            doc_id=self.doc_id3, doc_type=self.doc_type1,
                                                            data=self.sample_data1)
        self.source_operations.create_and_retrieve_document(cluster=self.source_cluster, index_name=self.index_name,
                                                            doc_id=self.doc_id4, doc_type=self.doc_type2,
                                                            data=self.sample_data2)

    def replay_wait_for_stop(self):
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.split_index_name1,
                                            doc_id=self.doc_id3, max_attempts=20, delay=3.0)
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.split_index_name2,
                                            doc_id=self.doc_id4, max_attempts=20, delay=3.0)
        replayer_stop_result = self.replayer.stop()
        assert replayer_stop_result.success
        wait_for_service_status(status_func=lambda: self.replayer.get_status(), desired_status=ReplayStatus.STOPPED)
