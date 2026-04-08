"""Shared constants for migration CRD operations."""

CRD_GROUP = "migrations.opensearch.org"
CRD_VERSION = "v1alpha1"

# Migration CRD types (plural -> friendly name)
MIGRATION_CRD_TYPES = {
    "capturedtraffics": "Capture Proxy",
    "datasnapshots": "Data Snapshot",
    "snapshotmigrations": "Snapshot Migration",
    "trafficreplays": "Traffic Replay",
    "kafkaclusters": "Kafka Cluster",
}
