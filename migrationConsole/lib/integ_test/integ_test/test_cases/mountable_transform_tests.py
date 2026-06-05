import glob
import gzip
import json
import logging
import re
import subprocess
import time

from .cdc_base import (
    MATestBase, MigrationType, MATestUserArguments,
    CDC_SOURCE_TARGET_COMBINATIONS, wait_for_proxy_ready, wait_for_replayer_consuming,
    make_proxy_cluster, log_kafka_consumer_group_state, assert_replay_drained,
)
from .ma_argo_test_base import ClusterVersionCombinationUnsupported

logger = logging.getLogger(__name__)


class Test0042CdcFullE2eMountableTransforms(MATestBase):
    """Full CDC E2E test for image-mounted transform files across migration types."""

    requires_explicit_selection = True

    DOC_COUNT = 3
    FIELD_NAME = "mountable_transform_marker"
    FIELD_TYPE = "keyword"
    ORDER_FIELD = "mountable_transform_order"
    REQUEST_HEADER = "x-mountable-request-transform"
    TUPLE_HEADER_1 = "x-mountable-tuple-transform-1"
    TUPLE_HEADER_2 = "x-mountable-tuple-transform-2"
    TUPLE_STRING_CONTEXT_HEADER = "x-mountable-string-context-transform"
    TUPLE_GLOB = "/s3/artifacts/tuples/**/tuples-*.log.gz"

    def __init__(self, user_args: MATestUserArguments):
        if user_args.target_type == "AOSS":
            raise ClusterVersionCombinationUnsupported(
                user_args.source_version,
                user_args.target_type,
                "Mountable transform CDC E2E requires a provisioned target cluster",
            )
        super().__init__(
            user_args=user_args,
            description="Full E2E: mountable transform images for metadata, backfill, request, and tuple transforms.",
            migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL,
                                 MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=CDC_SOURCE_TARGET_COMBINATIONS,
        )
        uid = self.unique_id
        self.index_name = f"cdc0042-mountable-transforms-{uid}"
        self.doc_ids = [f"doc_{i}" for i in range(self.DOC_COUNT)]
        self.backfill_field_value = "backfill-from-directory-context"
        self.order_field_value = f"second-transform-after-{self.backfill_field_value}"
        self.request_header_value = "request-from-configmap-directory-context"
        self.tuple_header_1_value = "tuple-one-from-directory-context"
        self.tuple_header_2_prefix = "tuple-two-from-configmap-key-after-"
        self.tuple_header_2_value = f"{self.tuple_header_2_prefix}{self.tuple_header_1_value}"
        self.tuple_string_context_value = f"tuple-string-context-{uid}"
        self.request_context_configmap = self._context_configmap_name("request")
        self.tuple_context_configmap = self._context_configmap_name("tuple")
        self.context_configmap_names = [
            self.request_context_configmap,
            self.tuple_context_configmap,
        ]

    def prepare_workflow_snapshot_and_migration_config(self):
        self.workflow_snapshot_and_migration_config = [{
            "migrations": [{
                "metadataMigrationConfig": {
                    "metadataTransforms": {
                        "entryPoint": {
                            "javascriptFile": {
                                "image": self.transform_image_basic,
                                "path": "metadata.js",
                            },
                        },
                        "context": {
                            "values": {
                                "fieldName": {
                                    "fromFile": {
                                        "image": self.transform_image_basic,
                                        "path": "context/metadata/fieldName",
                                    },
                                },
                                "fieldType": {"value": self.FIELD_TYPE},
                            },
                        },
                    },
                },
                "documentBackfillConfig": {
                    "maxShardSizeBytes": 16000000,
                    "resources": {
                        "requests": {"cpu": "25m", "memory": "1Gi", "ephemeral-storage": "5Gi"},
                        "limits": {"cpu": "1000m", "memory": "2Gi", "ephemeral-storage": "5Gi"}
                    },
                    "documentTransforms": [
                        {
                            "entryPoint": {
                                "javascriptFile": {
                                    "image": self.transform_image_sequence,
                                    "path": "document-1.js",
                                },
                            },
                            "context": {
                                "valueDirectories": [
                                    {
                                        "image": self.transform_image_sequence,
                                        "path": "context/document-1",
                                    },
                                ],
                            },
                        },
                        {
                            "entryPoint": {
                                "javascriptFile": {
                                    "image": self.transform_image_sequence,
                                    "path": "document-2.js",
                                },
                            },
                            "context": {
                                "values": {
                                    "sourceFieldName": {"value": self.FIELD_NAME},
                                    "fieldName": {
                                        "fromFile": {
                                            "image": self.transform_image_context,
                                            "path": "document-2/fieldName",
                                        },
                                    },
                                    "valuePrefix": {"value": "second-transform-after-"},
                                },
                            },
                        },
                    ],
                },
            }]
        }]

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = (
            "cdc-full-e2e-imported-clusters"
            if self.imported_clusters
            else "cdc-e2e-migration-with-clusters"
        )
        self.parameters["capture-proxy-service-type"] = self.capture_proxy_service_type
        if not self.transform_image_basic or not self.transform_image_sequence or not self.transform_image_context:
            raise ValueError(
                "Test0042 requires --transform_image_basic, --transform_image_sequence, "
                "and --transform_image_context"
            )
        self._ensure_context_configmaps()

        self.parameters["replayer-config-overrides"] = {
            "requestTransforms": {
                "entryPoint": {
                    "javascriptFile": {
                        "image": self.transform_image_sequence,
                        "path": "request.js",
                    },
                },
                "context": {
                    "valueDirectories": [
                        {
                            "configMap": self.request_context_configmap,
                        },
                    ],
                    "values": {
                        "headerName": {"value": self.REQUEST_HEADER},
                    },
                },
            },
            "tupleTransforms": [
                {
                    "entryPoint": {
                        "javascriptFile": {
                            "image": self.transform_image_sequence,
                            "path": "tuple-1.js",
                        },
                    },
                    "context": {
                        "valueDirectories": [
                            {
                                "image": self.transform_image_sequence,
                                "path": "context/tuple-1",
                            },
                        ],
                    },
                },
                {
                    "entryPoint": {
                        "javascriptFile": {
                            "image": self.transform_image_sequence,
                            "path": "tuple-2.js",
                        },
                    },
                    "context": {
                        "valueDirectories": [
                            {
                                "configMap": self.tuple_context_configmap,
                            },
                        ],
                        "values": {
                            "previousHeaderName": {"value": self.TUPLE_HEADER_1},
                            "headerName": {
                                "fromFile": {
                                    "image": self.transform_image_context,
                                    "path": "tuple-2/headerName",
                                },
                            },
                            "headerValuePrefix": {
                                "fromFile": {
                                    "configMap": self.tuple_context_configmap,
                                    "path": "headerValuePrefix",
                                },
                            },
                        },
                    },
                },
                {
                    "entryPoint": {
                        "javascriptFile": {
                            "image": self.transform_image_sequence,
                            "path": "tuple-string.js",
                        },
                    },
                    "context": self.tuple_string_context_value,
                },
            ],
            "tupleMaxBufferSeconds": 5,
            "tupleMaxPerFile": 1,
        }

    def _context_configmap_name(self, suffix: str) -> str:
        name = re.sub(r"[^a-z0-9-]+", "-", f"mountable-transform-{suffix}-{self.unique_id}".lower())
        name = re.sub(r"-+", "-", name).strip("-")
        return name[:63].rstrip("-") or f"mountable-transform-{suffix}"

    def _ensure_context_configmaps(self):
        request_config = {
            "headerValue": self.request_header_value,
            "requestGroupMarker": "request-configmap-directory-group-loaded",
        }
        tuple_config = {
            "headerValuePrefix": self.tuple_header_2_prefix,
            "tupleGroupMarker": "tuple-configmap-directory-group-loaded",
        }
        for name, data in (
            (self.request_context_configmap, request_config),
            (self.tuple_context_configmap, tuple_config),
        ):
            configmap = {
                "apiVersion": "v1",
                "kind": "ConfigMap",
                "metadata": {
                    "name": name,
                    "labels": {
                        "app.kubernetes.io/name": "mountable-transform-test-context",
                        "app.kubernetes.io/part-of": "migration-assistant-integ-test",
                    },
                },
                "data": data,
            }
            subprocess.run(
                ["kubectl", "apply", "-n", self.argo_service.namespace, "-f", "-"],
                input=json.dumps(configmap),
                text=True,
                check=True,
            )

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

        if not self.imported_clusters:
            logger.info("Resuming workflow past pause-for-test-data to start CDC migration...")
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)

        logger.info("Waiting for capture-proxy to be ready...")
        wait_for_proxy_ready(ns, timeout_seconds)

        logger.info("Waiting for replayer to join Kafka consumer group...")
        wait_for_replayer_consuming(namespace=ns, timeout_seconds=300)
        log_kafka_consumer_group_state(label="replay-start")

        proxy_cluster = make_proxy_cluster(self.source_cluster)
        logger.info("Sending validation request for tuple capture through proxy...")
        self.source_operations.get_document(
            index_name=self.index_name,
            doc_id=self.doc_ids[0],
            cluster=proxy_cluster,
        )

    def verify_clusters(self):
        logger.info("Verifying transformed backfill documents on target...")
        try:
            self.target_operations.check_doc_counts_match(
                cluster=self.target_cluster,
                expected_index_details={self.index_name: {"count": self.DOC_COUNT}},
                max_attempts=120,
                delay=10.0,
            )
            self._assert_metadata_field_mapping()
            self._wait_for_transformed_backfill_docs()
            self._wait_for_transformed_tuple_file()
            self._assert_file_source_deduplication()
        finally:
            assert_replay_drained(label="replay-end")

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
            self.tuple_string_context_value,
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

    def _assert_file_source_deduplication(self):
        traffic_replay = self._single_custom_resource("trafficreplays.migrations.opensearch.org")
        self._assert_deduped_file_sources(
            traffic_replay["spec"].get("fileSourceVolumes", []),
            traffic_replay["spec"].get("fileSourceVolumeMounts", []),
            expected_images={self.transform_image_sequence, self.transform_image_context},
            expected_configmaps={self.request_context_configmap, self.tuple_context_configmap},
            label="TrafficReplay",
        )

        snapshot_migration = self._single_custom_resource("snapshotmigrations.migrations.opensearch.org")
        spec = snapshot_migration["spec"]
        self._assert_deduped_file_sources(
            spec.get("metadataMigrationFileSourceVolumes", []),
            spec.get("metadataMigrationFileSourceVolumeMounts", []),
            expected_images={self.transform_image_basic},
            expected_configmaps=set(),
            label="SnapshotMigration metadata",
        )
        self._assert_deduped_file_sources(
            spec.get("documentBackfillFileSourceVolumes", []),
            spec.get("documentBackfillFileSourceVolumeMounts", []),
            expected_images={self.transform_image_sequence, self.transform_image_context},
            expected_configmaps=set(),
            label="SnapshotMigration document backfill",
        )

    def _single_custom_resource(self, resource_type: str) -> dict:
        result = subprocess.run(
            ["kubectl", "get", resource_type, "-n", self.argo_service.namespace, "-o", "json"],
            capture_output=True,
            text=True,
            check=True,
        )
        items = json.loads(result.stdout).get("items", [])
        if len(items) != 1:
            names = [item.get("metadata", {}).get("name") for item in items]
            raise AssertionError(f"Expected one {resource_type}, found {len(items)}: {names}")
        return items[0]

    def _assert_deduped_file_sources(
        self,
        volumes: list,
        mounts: list,
        expected_images: set,
        expected_configmaps: set,
        label: str,
    ):
        source_ids = []
        images = set()
        configmaps = set()
        for volume in volumes:
            if "image" in volume:
                image = volume["image"]["reference"]
                pull_policy = volume["image"].get("pullPolicy", "IfNotPresent")
                source_ids.append(("image", image, pull_policy))
                images.add(image)
            elif "configMap" in volume:
                configmap = volume["configMap"]["name"]
                source_ids.append(("configMap", configmap))
                configmaps.add(configmap)
            else:
                raise AssertionError(f"{label} has unsupported file source volume: {volume}")

        if len(source_ids) != len(set(source_ids)):
            raise AssertionError(f"{label} did not dedupe file source volumes: {volumes}")
        if images != expected_images:
            raise AssertionError(f"{label} expected image sources {expected_images}, got {images}: {volumes}")
        if configmaps != expected_configmaps:
            raise AssertionError(
                f"{label} expected ConfigMap sources {expected_configmaps}, got {configmaps}: {volumes}"
            )

        volume_names = {volume["name"] for volume in volumes}
        mount_names = [mount["name"] for mount in mounts]
        mount_paths = [mount["mountPath"] for mount in mounts]
        if len(mounts) != len(volumes) or set(mount_names) != volume_names:
            raise AssertionError(f"{label} volume mounts do not match volumes: volumes={volumes}, mounts={mounts}")
        if len(mount_names) != len(set(mount_names)) or len(mount_paths) != len(set(mount_paths)):
            raise AssertionError(f"{label} did not dedupe file source mounts: {mounts}")

    def test_after(self):
        pass
