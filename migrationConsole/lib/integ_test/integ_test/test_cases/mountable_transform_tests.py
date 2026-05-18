import glob
import gzip
import json
import logging
import time

from .cdc_base import (
    MATestBase, MigrationType, MATestUserArguments,
    CDC_SOURCE_TARGET_COMBINATIONS, REPLAYER_LABEL_SELECTOR, PROXY_LABEL_SELECTOR,
    wait_for_pod_ready, wait_for_replayer_consuming,
    make_proxy_cluster,
)

logger = logging.getLogger(__name__)


class Test0041CdcFullE2eMountableTransforms(MATestBase):
    """Full CDC E2E test for image-mounted transform files across migration types."""

    requires_explicit_selection = True

    DOC_COUNT = 3
    FIELD_NAME = "mountable_transform_marker"
    FIELD_TYPE = "keyword"
    ORDER_FIELD = "mountable_transform_order"
    REQUEST_HEADER = "x-mountable-request-transform"
    TUPLE_HEADER_1 = "x-mountable-tuple-transform-1"
    TUPLE_HEADER_2 = "x-mountable-tuple-transform-2"
    TUPLE_GLOB = "/s3/artifacts/tuples/**/tuples-*.log.gz"

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="Full E2E: mountable transform images for metadata, backfill, request, and tuple transforms.",
            migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL,
                                 MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=CDC_SOURCE_TARGET_COMBINATIONS,
        )
        uid = self.unique_id
        self.index_name = f"cdc0041-mountable-transforms-{uid}"
        self.doc_ids = [f"doc_{i}" for i in range(self.DOC_COUNT)]
        self.backfill_field_value = f"backfill-{uid}"
        self.order_field_value = f"second-transform-after-{self.backfill_field_value}"
        self.request_header_value = f"request-{uid}"
        self.tuple_header_1_value = f"tuple-one-{uid}"
        self.tuple_header_2_value = f"tuple-two-after-{self.tuple_header_1_value}"

    def prepare_workflow_snapshot_and_migration_config(self):
        self.workflow_snapshot_and_migration_config = [{
            "migrations": [{
                "metadataMigrationConfig": {
                    "transformsSource": "transform-basic",
                    "metadataTransforms": {
                        "language": "javascript",
                        "file": "metadata.js",
                        "bindingsObject": {
                            "fieldName": self.FIELD_NAME,
                            "fieldType": self.FIELD_TYPE,
                        },
                    },
                },
                "documentBackfillConfig": {
                    "maxShardSizeBytes": 16000000,
                    "resources": {
                        "requests": {"cpu": "25m", "memory": "1Gi", "ephemeral-storage": "5Gi"},
                        "limits": {"cpu": "1000m", "memory": "2Gi", "ephemeral-storage": "5Gi"}
                    },
                    "transformsSource": "transform-sequence",
                    "documentTransforms": [
                        {
                            "language": "javascript",
                            "file": "document-1.js",
                            "bindingsObject": {
                                "fieldName": self.FIELD_NAME,
                                "fieldValue": self.backfill_field_value,
                            },
                        },
                        {
                            "language": "javascript",
                            "file": "document-2.js",
                            "bindingsObject": {
                                "sourceFieldName": self.FIELD_NAME,
                                "fieldName": self.ORDER_FIELD,
                                "valuePrefix": "second-transform-after-",
                            },
                        },
                    ],
                },
            }]
        }]

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = "cdc-full-e2e-imported-clusters"
        if not self.transform_image_basic or not self.transform_image_sequence:
            raise ValueError("Test0041 requires --transform_image_basic and --transform_image_sequence")

        self.parameters["transforms-sources"] = {
            "transform-basic": {"image": self.transform_image_basic},
            "transform-sequence": {"image": self.transform_image_sequence},
        }
        self.parameters["replayer-config-overrides"] = {
            "transformsSource": "transform-sequence",
            "requestTransforms": {
                "language": "javascript",
                "file": "request.js",
                "bindingsObject": {
                    "headerName": self.REQUEST_HEADER,
                    "headerValue": self.request_header_value,
                },
            },
            "tupleTransforms": [
                {
                    "language": "javascript",
                    "file": "tuple-1.js",
                    "bindingsObject": {
                        "headerName": self.TUPLE_HEADER_1,
                        "headerValue": self.tuple_header_1_value,
                    },
                },
                {
                    "language": "javascript",
                    "file": "tuple-2.js",
                    "bindingsObject": {
                        "previousHeaderName": self.TUPLE_HEADER_1,
                        "headerName": self.TUPLE_HEADER_2,
                        "headerValuePrefix": "tuple-two-after-",
                    },
                },
            ],
            "tupleMaxBufferSeconds": 5,
            "tupleMaxPerFile": 1,
        }

    def prepare_clusters(self):
        index_body = {
            "settings": {
                "index": {
                    "number_of_shards": 1,
                    "number_of_replicas": 0,
                }
            },
            "mappings": {
                "properties": {
                    "title": {"type": "text"},
                    "category": {"type": "keyword"},
                }
            }
        }
        self.source_operations.create_index(
            index_name=self.index_name,
            cluster=self.source_cluster,
            data=json.dumps(index_body),
        )
        for doc_id in self.doc_ids:
            self.source_operations.create_document(
                index_name=self.index_name,
                doc_id=doc_id,
                cluster=self.source_cluster,
                data={
                    "title": f"Mountable transform source document {doc_id}",
                    "category": "seed",
                },
            )
        self.source_operations.refresh_index(index_name=self.index_name, cluster=self.source_cluster)
        self.source_operations.check_doc_counts_match(
            cluster=self.source_cluster,
            expected_index_details={self.index_name: {"count": self.DOC_COUNT}},
            max_attempts=10,
            delay=3.0,
        )

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        ns = self.argo_service.namespace

        logger.info("Waiting for capture-proxy to be ready...")
        wait_for_pod_ready(ns, PROXY_LABEL_SELECTOR, timeout_seconds)

        logger.info("Waiting for replayer to start...")
        wait_for_pod_ready(ns, REPLAYER_LABEL_SELECTOR, timeout_seconds)
        logger.info("Waiting for replayer to join Kafka consumer group...")
        wait_for_replayer_consuming(namespace=ns, timeout_seconds=300)

        proxy_cluster = make_proxy_cluster(self.source_cluster)
        logger.info("Sending validation request for tuple capture through proxy...")
        self.source_operations.get_document(
            index_name=self.index_name,
            doc_id=self.doc_ids[0],
            cluster=proxy_cluster,
        )

    def verify_clusters(self):
        logger.info("Verifying transformed backfill documents on target...")
        self.target_operations.check_doc_counts_match(
            cluster=self.target_cluster,
            expected_index_details={self.index_name: {"count": self.DOC_COUNT}},
            max_attempts=120,
            delay=10.0,
        )
        self._assert_metadata_field_mapping()
        self._wait_for_transformed_backfill_docs()
        self._wait_for_transformed_tuple_file()

    def _assert_metadata_field_mapping(self):
        response = self.target_operations.get_index(index_name=self.index_name, cluster=self.target_cluster)
        properties = response.json()[self.index_name]["mappings"]["properties"]
        actual_field = properties.get(self.FIELD_NAME)
        if actual_field is None:
            raise AssertionError(f"Expected metadata transform to add mapping for {self.FIELD_NAME}")
        actual_type = actual_field.get("type")
        if actual_type != self.FIELD_TYPE:
            raise AssertionError(
                f"Expected mapping {self.FIELD_NAME}.type={self.FIELD_TYPE}, got {actual_type}: {actual_field}"
            )

    def _wait_for_transformed_backfill_docs(self, max_attempts: int = 60, delay: float = 5.0):
        last_error = ""
        for attempt in range(1, max_attempts + 1):
            try:
                self.target_operations.refresh_index(index_name=self.index_name, cluster=self.target_cluster)
                for doc_id in self.doc_ids:
                    response = self.target_operations.get_document(
                        index_name=self.index_name,
                        doc_id=doc_id,
                        cluster=self.target_cluster,
                    )
                    source = response.json()["_source"]
                    if source.get(self.FIELD_NAME) != self.backfill_field_value:
                        raise AssertionError(f"{doc_id} missing {self.FIELD_NAME}: {source}")
                    if source.get(self.ORDER_FIELD) != self.order_field_value:
                        raise AssertionError(f"{doc_id} missing {self.ORDER_FIELD}: {source}")
                return
            except Exception as e:
                last_error = str(e)
                logger.info("Transformed backfill docs not ready on attempt %d/%d: %s",
                            attempt, max_attempts, last_error)
                time.sleep(delay)
        raise AssertionError(f"Backfill transform assertions did not pass: {last_error}")

    def _wait_for_transformed_tuple_file(self, max_attempts: int = 60, delay: float = 5.0):
        required_values = {
            self.request_header_value,
            self.tuple_header_1_value,
            self.tuple_header_2_value,
        }
        last_seen_files = []
        for attempt in range(1, max_attempts + 1):
            found_values = set()
            last_seen_files = sorted(glob.glob(self.TUPLE_GLOB, recursive=True))
            for tuple_file in last_seen_files:
                found_values.update(self._read_required_values_from_tuple_file(tuple_file, required_values))
                if required_values <= found_values:
                    return
            logger.info("Tuple transform values not ready on attempt %d/%d. Found %s in %d files.",
                        attempt, max_attempts, sorted(found_values), len(last_seen_files))
            time.sleep(delay)
        raise AssertionError(
            f"Expected tuple files under {self.TUPLE_GLOB} to contain {sorted(required_values)}. "
            f"Last saw files: {last_seen_files[-10:]}"
        )

    def _read_required_values_from_tuple_file(self, tuple_file: str, required_values: set) -> set:
        found_values = set()
        try:
            with gzip.open(tuple_file, "rt", encoding="utf-8") as f:
                for line in f:
                    tuple_record = json.loads(line)
                    for value in required_values - found_values:
                        if self._contains_value(tuple_record, value):
                            found_values.add(value)
        except (OSError, EOFError, json.JSONDecodeError) as e:
            logger.info("Skipping tuple file %s while it is not readable: %s", tuple_file, e)
        return found_values

    def _contains_value(self, value, expected: str) -> bool:
        if isinstance(value, str):
            return expected in value
        if isinstance(value, dict):
            return any(self._contains_value(k, expected) or self._contains_value(v, expected)
                       for k, v in value.items())
        if isinstance(value, list):
            return any(self._contains_value(item, expected) for item in value)
        return False

    def test_after(self):
        pass
