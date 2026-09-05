"""Shared workflow manage tree sections, groups, and stable ids.

Both the status/resource view and the config edit view present the same
workflow shape.  Status nodes are built from CRs and projected config, while
edit nodes are built from the schema-aware TS edit model; this module keeps the
section/group vocabulary and cross-view ids in one place so those paths do not
drift in labels or ordering.
"""

from typing import Dict, List, Optional, Tuple


WORKFLOW_CONFIGURATION_SECTION = "Workflow Configuration"
SNAPSHOT_MIGRATION_SECTION = "Snapshot Migration"
LIVE_TRAFFIC_MIGRATION_SECTION = "Live Traffic Migration"

SOURCES_GROUP = "Sources"
TARGETS_GROUP = "Targets"
SNAPSHOT_GROUP = "Snapshot"
BACKFILL_GROUP = "Backfill"
CAPTURE_GROUP = "Capture"
BUFFER_GROUP = "Buffer"
REPLAY_GROUP = "Replay"
KAFKA_CLUSTERS_GROUP = "Kafka Clusters"
KAFKA_TOPICS_GROUP = "Kafka Topics"


RESOURCE_SECTIONS: List[Tuple[str, List[Tuple[List[str], str]]]] = [
    (SOURCES_GROUP, [(["sourceconfigs"], SOURCES_GROUP)]),
    (TARGETS_GROUP, [(["targetconfigs"], TARGETS_GROUP)]),
    (SNAPSHOT_MIGRATION_SECTION, [
        (["datasnapshots"], SNAPSHOT_GROUP),
        (["snapshotmigrations"], BACKFILL_GROUP),
    ]),
    (LIVE_TRAFFIC_MIGRATION_SECTION, [
        (["kafkaconfigs", "kafkaclusters", "capturedtraffics"], BUFFER_GROUP),
        (["captureproxies"], CAPTURE_GROUP),
        (["trafficreplays"], REPLAY_GROUP),
    ]),
]

PLURAL_DISPLAY_NAMES: Dict[str, str] = {
    plural: display_name
    for _, groups in RESOURCE_SECTIONS
    for plurals, display_name in groups
    for plural in plurals
}

GROUP_PLURALS_BY_PRIMARY: Dict[str, List[str]] = {
    plurals[0]: plurals
    for _, groups in RESOURCE_SECTIONS
    for plurals, _ in groups
}

RESOURCE_TYPE_LABELS: Dict[str, str] = {
    "sourceconfigs": "Source cluster",
    "targetconfigs": "Target cluster",
    "kafkaconfigs": "Kafka connection",
    "kafkaclusters": "Kafka cluster",
    "capturedtraffics": "Captured traffic",
    "captureproxies": "Capture proxy",
    "datasnapshots": "Data snapshot",
    "snapshotmigrations": "Snapshot migration",
    "trafficreplays": "Traffic replay",
}

BUFFER_SUBGROUP_BY_PLURAL: Dict[str, str] = {
    "kafkaconfigs": KAFKA_CLUSTERS_GROUP,
    "kafkaclusters": KAFKA_CLUSTERS_GROUP,
    "capturedtraffics": KAFKA_TOPICS_GROUP,
}

EDIT_ID_BY_TREE_ID: Dict[str, str] = {
    f"section:{WORKFLOW_CONFIGURATION_SECTION}": "edit:workflowConfiguration",
    f"section:{SOURCES_GROUP}": "edit:sourceClusters",
    f"section:{TARGETS_GROUP}": "edit:targetClusters",
    f"section:{SNAPSHOT_MIGRATION_SECTION}": "edit:snapshotMigration",
    f"section:{LIVE_TRAFFIC_MIGRATION_SECTION}": "edit:traffic",
    f"group:{SOURCES_GROUP}:{SOURCES_GROUP}": "edit:sourceClusters",
    f"group:{TARGETS_GROUP}:{TARGETS_GROUP}": "edit:targetClusters",
    (
        f"group:{SNAPSHOT_MIGRATION_SECTION}:{BACKFILL_GROUP}"
    ): "edit:snapshotMigrationConfigs",
    (
        f"group:{LIVE_TRAFFIC_MIGRATION_SECTION}:{CAPTURE_GROUP}"
    ): "edit:traffic.proxies",
    (
        f"group:{LIVE_TRAFFIC_MIGRATION_SECTION}:{BUFFER_GROUP}"
    ): "edit:traffic.buffer",
    (
        f"group:{LIVE_TRAFFIC_MIGRATION_SECTION}:{BUFFER_GROUP}:"
        f"{KAFKA_CLUSTERS_GROUP}"
    ): "edit:traffic.kafkaClusters",
    (
        f"group:{LIVE_TRAFFIC_MIGRATION_SECTION}:{BUFFER_GROUP}:"
        f"{KAFKA_TOPICS_GROUP}"
    ): "edit:traffic.s3Sources",
    (
        f"group:{LIVE_TRAFFIC_MIGRATION_SECTION}:{REPLAY_GROUP}"
    ): "edit:traffic.replayers",
}

EDIT_RESOURCE_COLLECTION_PATHS: Tuple[Tuple[str, ...], ...] = (
    ("sourceClusters",),
    ("targetClusters",),
    ("snapshotMigrationConfigs",),
    ("traffic", "kafkaClusters"),
    ("traffic", "proxies"),
    ("traffic", "s3Sources"),
    ("traffic", "replayers"),
)


def display_name_for_plural(plural: str) -> Optional[str]:
    return PLURAL_DISPLAY_NAMES.get(plural)


def group_plurals_for(primary_plural: str) -> List[str]:
    return GROUP_PLURALS_BY_PRIMARY.get(primary_plural, [primary_plural])


def resource_type_label_for_plural(plural: str) -> Optional[str]:
    return RESOURCE_TYPE_LABELS.get(plural)


def buffer_subgroup_for_plural(plural: str) -> Optional[str]:
    return BUFFER_SUBGROUP_BY_PLURAL.get(plural)
