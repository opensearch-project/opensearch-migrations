"""Service for resetting migration resources via direct K8s API calls.

Deletes migration CRDs in reverse dependency order. Optionally tears down
the capture proxy and Kafka infrastructure (with VAP-gated annotation stamping).

Dependency order (reverse for deletion):
  Kafka -> CapturedTraffic (proxy) -> TrafficReplay
                                   -> SnapshotMigration -> DataSnapshot
"""

import logging
from dataclasses import dataclass, field
from typing import List, Optional

from kubernetes import client
from kubernetes.client.rest import ApiException

logger = logging.getLogger(__name__)

# CRD coordinates for migrations.opensearch.org/v1alpha1
MIGRATION_API_GROUP = "migrations.opensearch.org"
MIGRATION_API_VERSION = "v1alpha1"

# Kafka (Strimzi) coordinates
KAFKA_API_GROUP = "kafka.strimzi.io"

# Teardown approval annotation key
TEARDOWN_ANNOTATION = "migrations.opensearch.org/approved-for-teardown"


@dataclass
class DeleteResult:
    """Result of a single resource deletion."""
    kind: str
    name: str
    success: bool
    message: str
    not_found: bool = False


@dataclass
class ResetResult:
    """Aggregate result of a reset operation."""
    success: bool
    results: List[DeleteResult] = field(default_factory=list)
    error: Optional[str] = None

    @property
    def summary(self) -> str:
        deleted = [r for r in self.results if r.success and not r.not_found]
        skipped = [r for r in self.results if r.not_found]
        failed = [r for r in self.results if not r.success]
        parts = []
        if deleted:
            parts.append(f"{len(deleted)} deleted")
        if skipped:
            parts.append(f"{len(skipped)} not found (skipped)")
        if failed:
            parts.append(f"{len(failed)} failed")
        return ", ".join(parts) if parts else "nothing to do"


class K8sResetService:
    """Service for resetting migration resources via direct K8s API.

    Uses the Kubernetes Python client to delete CRDs and Kafka resources
    in the correct dependency order, respecting the VAP-gated teardown
    approval pattern for Kafka resources.
    """

    def __init__(self, namespace: str):
        self.namespace = namespace
        self.custom_api = client.CustomObjectsApi()

    def reset(
        self,
        include_proxy: bool = False,
        resource_name: Optional[str] = None,
    ) -> ResetResult:
        """Reset migration resources in dependency order.

        Deletion order:
          1. TrafficReplay
          2. SnapshotMigration
          3. DataSnapshot
          4. CapturedTraffic (only if include_proxy=True)
          5. Kafka resources (only if include_proxy=True)

        Args:
            include_proxy: If True, also delete capture proxy and Kafka resources.
            resource_name: If set, only delete resources with this name.
                         Otherwise deletes ALL instances of each CRD type.

        Returns:
            ResetResult with per-resource details.
        """
        results: List[DeleteResult] = []
        all_ok = True

        # Phase 1: Delete TrafficReplay
        phase_results = self._delete_migration_crds(
            "TrafficReplay", "trafficreplays", resource_name
        )
        results.extend(phase_results)

        # Phase 2: Delete SnapshotMigration
        phase_results = self._delete_migration_crds(
            "SnapshotMigration", "snapshotmigrations", resource_name
        )
        results.extend(phase_results)

        # Phase 3: Delete DataSnapshot
        phase_results = self._delete_migration_crds(
            "DataSnapshot", "datasnapshots", resource_name
        )
        results.extend(phase_results)

        # Phase 4: Proxy + Kafka (only if include_proxy)
        if include_proxy:
            # Delete CapturedTraffic
            phase_results = self._delete_migration_crds(
                "CapturedTraffic", "capturedtraffics", resource_name
            )
            results.extend(phase_results)

            # Stamp teardown approval on Kafka resources, then delete them
            kafka_results = self._teardown_kafka(resource_name)
            results.extend(kafka_results)

        for r in results:
            if not r.success:
                all_ok = False

        return ResetResult(success=all_ok, results=results)

    def _delete_migration_crds(
        self,
        kind: str,
        plural: str,
        resource_name: Optional[str],
    ) -> List[DeleteResult]:
        """Delete migration CRD instances."""
        if resource_name:
            return [self._delete_custom_resource(
                MIGRATION_API_GROUP, MIGRATION_API_VERSION, plural, kind, resource_name
            )]
        else:
            return self._delete_all_custom_resources(
                MIGRATION_API_GROUP, MIGRATION_API_VERSION, plural, kind
            )

    def _teardown_kafka(self, resource_name: Optional[str]) -> List[DeleteResult]:
        """Stamp teardown annotation on Kafka resources and delete them.

        The VAP requires the teardown-approval annotation to be present
        before deletion is allowed. We stamp it first, then delete.

        Order: KafkaTopic -> KafkaNodePool -> Kafka (cluster last)
        """
        results: List[DeleteResult] = []

        # Collect all Kafka resources that need annotation + deletion
        kafka_resources = [
            ("v1beta2", "kafkatopics", "KafkaTopic"),
            ("v1", "kafkanodepools", "KafkaNodePool"),
            ("v1", "kafkas", "Kafka"),
        ]

        for api_version, plural, kind in kafka_resources:
            if resource_name:
                names = [resource_name]
            else:
                names = self._list_resource_names(
                    KAFKA_API_GROUP, api_version, plural
                )

            for name in names:
                # Stamp the teardown approval annotation
                stamp_ok = self._stamp_teardown_annotation(
                    KAFKA_API_GROUP, api_version, plural, kind, name
                )
                if not stamp_ok:
                    results.append(DeleteResult(
                        kind=kind, name=name, success=False,
                        message=f"Failed to stamp teardown annotation on {kind}/{name}"
                    ))
                    continue

                # Now delete
                result = self._delete_custom_resource(
                    KAFKA_API_GROUP, api_version, plural, kind, name
                )
                results.append(result)

        return results

    def _list_resource_names(
        self,
        group: str,
        version: str,
        plural: str,
    ) -> List[str]:
        """List all resource names of a given type in the namespace."""
        try:
            response = self.custom_api.list_namespaced_custom_object(
                group=group,
                version=version,
                namespace=self.namespace,
                plural=plural,
            )
            items = response.get("items", [])
            return [
                item["metadata"]["name"]
                for item in items
                if "metadata" in item and "name" in item["metadata"]
            ]
        except ApiException as e:
            if e.status == 404:
                # CRD not installed — nothing to list
                logger.debug(f"CRD {group}/{version}/{plural} not found (CRD not installed)")
                return []
            logger.warning(f"Failed to list {plural}: {e}")
            return []

    def _stamp_teardown_annotation(
        self,
        group: str,
        version: str,
        plural: str,
        kind: str,
        name: str,
    ) -> bool:
        """Patch a resource to add the teardown approval annotation.

        Returns True on success, False on failure.
        """
        body = {
            "metadata": {
                "annotations": {
                    TEARDOWN_ANNOTATION: "cli-reset"
                }
            }
        }
        try:
            self.custom_api.patch_namespaced_custom_object(
                group=group,
                version=version,
                namespace=self.namespace,
                plural=plural,
                name=name,
                body=body,
            )
            logger.info(f"Stamped teardown annotation on {kind}/{name}")
            return True
        except ApiException as e:
            if e.status == 404:
                logger.debug(f"{kind}/{name} not found — nothing to annotate")
                return True  # Not an error — resource already gone
            logger.error(f"Failed to annotate {kind}/{name}: {e}")
            return False

    def _delete_custom_resource(
        self,
        group: str,
        version: str,
        plural: str,
        kind: str,
        name: str,
    ) -> DeleteResult:
        """Delete a single custom resource by name."""
        try:
            self.custom_api.delete_namespaced_custom_object(
                group=group,
                version=version,
                namespace=self.namespace,
                plural=plural,
                name=name,
            )
            logger.info(f"Deleted {kind}/{name}")
            return DeleteResult(
                kind=kind, name=name, success=True,
                message=f"Deleted {kind}/{name}"
            )
        except ApiException as e:
            if e.status == 404:
                logger.debug(f"{kind}/{name} not found — already deleted")
                return DeleteResult(
                    kind=kind, name=name, success=True,
                    message=f"{kind}/{name} not found (already deleted)",
                    not_found=True,
                )
            logger.error(f"Failed to delete {kind}/{name}: {e}")
            return DeleteResult(
                kind=kind, name=name, success=False,
                message=f"Failed to delete {kind}/{name}: {e.reason} ({e.status})"
            )

    def _delete_all_custom_resources(
        self,
        group: str,
        version: str,
        plural: str,
        kind: str,
    ) -> List[DeleteResult]:
        """Delete all instances of a custom resource type in the namespace."""
        names = self._list_resource_names(group, version, plural)
        if not names:
            logger.info(f"No {kind} resources found in namespace {self.namespace}")
            return []
        return [
            self._delete_custom_resource(group, version, plural, kind, name)
            for name in names
        ]
