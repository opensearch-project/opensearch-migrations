import logging
from ..common_utils import convert_to_b64
from ..cluster_version import ElasticsearchV5_X, OpensearchV1_X, OpensearchV2_X
from .ma_argo_test_base import MATestBase, MATestUserArguments, OTEL_COLLECTOR_ENDPOINT

logger = logging.getLogger(__name__)


class Test0004MultiTypeUnionMigration(MATestBase):
    def __init__(self, user_args: MATestUserArguments):
        allow_combinations = [
            (ElasticsearchV5_X, OpensearchV1_X),
            (ElasticsearchV5_X, OpensearchV2_X),
        ]
        description = "Performs metadata and backfill migrations with a multi-type union transformation."
        super().__init__(user_args=user_args,
                         description=description,
                         allow_source_target_combinations=allow_combinations)
        self.index_name = f"test_0004_{self.unique_id}"
        self.doc_id1 = "test_0004_1"
        self.doc_id2 = "test_0004_2"
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

    def prepare_workflow_snapshot_and_migration_config(self):
        union_transform = self.source_operations.get_type_mapping_union_transformation(
            multi_type_index_name=self.index_name,
            doc_type_1=self.doc_type1,
            doc_type_2=self.doc_type2,
            cluster_version=self.source_version
        )
        transformation_b64 = convert_to_b64([union_transform])
        snapshot_and_migration_configs = [{
            "migrations": [{
                "metadata": {
                    "from_snapshot": None,
                    "otel_endpoint": OTEL_COLLECTOR_ENDPOINT,
                    "transformer_config_base64": transformation_b64
                },
                "documentBackfillConfigs": [{
                    "transformer_config_base64": transformation_b64
                }]
            }]
        }]
        self.workflow_snapshot_and_migration_config = snapshot_and_migration_configs

    def prepare_clusters(self):
        # Create two documents each with a different type mapping for the same index
        self.source_operations.create_and_retrieve_document(cluster=self.source_cluster, index_name=self.index_name,
                                                            doc_id=self.doc_id1, doc_type=self.doc_type1,
                                                            data=self.sample_data1)
        self.source_operations.create_and_retrieve_document(cluster=self.source_cluster, index_name=self.index_name,
                                                            doc_id=self.doc_id2, doc_type=self.doc_type2,
                                                            data=self.sample_data2)

    def verify_clusters(self):
        self.target_operations.get_index(cluster=self.target_cluster, index_name=self.index_name, max_attempts=3,
                                         delay=2.0)
        # Get all keys from sample data
        expected_keys = set(self.sample_data1.keys()).union(set(self.sample_data2.keys()))
        self.target_operations.verify_index_mapping_properties(cluster=self.target_cluster, index_name=self.index_name,
                                                               expected_props=expected_keys)
        # Verify documents exist on target
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.index_name,
                                            doc_id=self.doc_id1, max_attempts=10, delay=3.0)
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.index_name,
                                            doc_id=self.doc_id2, max_attempts=10, delay=3.0)


class Test0005MultiTypeSplitMigration(MATestBase):
    def __init__(self, user_args: MATestUserArguments):
        allow_combinations = [
            (ElasticsearchV5_X, OpensearchV1_X),
            (ElasticsearchV5_X, OpensearchV2_X),
        ]
        description = "Performs metadata and backfill migrations with a multi-type split transformation."
        super().__init__(user_args=user_args,
                         description=description,
                         allow_source_target_combinations=allow_combinations)
        self.index_name = f"test_0005_{self.unique_id}"
        self.split_index_name1 = f"test_0005_split_1_{self.unique_id}"
        self.split_index_name2 = f"test_0005_split_2_{self.unique_id}"
        self.doc_id1 = "test_0005_1"
        self.doc_id2 = "test_0005_2"
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

    def prepare_workflow_snapshot_and_migration_config(self):
        split_transform = self.source_operations.get_type_mapping_split_transformation(
            multi_type_index_name=self.index_name,
            doc_type_1=self.doc_type1,
            doc_type_2=self.doc_type2,
            split_index_name_1=self.split_index_name1,
            split_index_name_2=self.split_index_name2,
            cluster_version=self.source_version
        )
        transformation_b64 = convert_to_b64([split_transform])
        snapshot_and_migration_configs = [{
            "migrations": [{
                "metadata": {
                    "from_snapshot": None,
                    "otel_endpoint": OTEL_COLLECTOR_ENDPOINT,
                    "transformer_config_base64": transformation_b64
                },
                "documentBackfillConfigs": [{
                    "transformer_config_base64": transformation_b64
                }]
            }]
        }]
        self.workflow_snapshot_and_migration_config = snapshot_and_migration_configs

    def prepare_clusters(self):
        # Create two documents each with a different type mapping for the same index
        self.source_operations.create_and_retrieve_document(cluster=self.source_cluster, index_name=self.index_name,
                                                            doc_id=self.doc_id1, doc_type=self.doc_type1,
                                                            data=self.sample_data1)
        self.source_operations.create_and_retrieve_document(cluster=self.source_cluster, index_name=self.index_name,
                                                            doc_id=self.doc_id2, doc_type=self.doc_type2,
                                                            data=self.sample_data2)

    def verify_clusters(self):
        self.target_operations.verify_index_mapping_properties(cluster=self.target_cluster,
                                                               index_name=self.split_index_name1,
                                                               expected_props=set(self.sample_data1.keys()))
        self.target_operations.verify_index_mapping_properties(cluster=self.target_cluster,
                                                               index_name=self.split_index_name2,
                                                               expected_props=set(self.sample_data2.keys()))
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.split_index_name1,
                                            doc_id=self.doc_id1, max_attempts=10, delay=3.0)
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.split_index_name2,
                                            doc_id=self.doc_id2, max_attempts=10, delay=3.0)
