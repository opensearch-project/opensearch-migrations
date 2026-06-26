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

KAFKA_CLIENTS_GROUP = "Kafka Clients"
SOURCES_GROUP = "Sources"
TARGETS_GROUP = "Targets"
SNAPSHOT_GROUP = "Snapshot"
BACKFILL_GROUP = "Backfill"
CAPTURE_GROUP = "Capture"
BUFFER_GROUP = "Buffer"
REPLAY_GROUP = "Replay"


RESOURCE_SECTIONS: List[Tuple[str, List[Tuple[List[str], str]]]] = [
    (WORKFLOW_CONFIGURATION_SECTION, [
        (["kafkaconfigs"], KAFKA_CLIENTS_GROUP),
        (["sourceconfigs"], SOURCES_GROUP),
        (["targetconfigs"], TARGETS_GROUP),
    ]),
    (SNAPSHOT_MIGRATION_SECTION, [
        (["datasnapshots"], SNAPSHOT_GROUP),
        (["snapshotmigrations"], BACKFILL_GROUP),
    ]),
    (LIVE_TRAFFIC_MIGRATION_SECTION, [
        (["captureproxies"], CAPTURE_GROUP),
        (["kafkaclusters", "capturedtraffics"], BUFFER_GROUP),
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

EDIT_ID_BY_TREE_ID: Dict[str, str] = {
    f"section:{WORKFLOW_CONFIGURATION_SECTION}": "edit:workflowConfiguration",
    f"section:{SNAPSHOT_MIGRATION_SECTION}": "edit:snapshotMigration",
    f"section:{LIVE_TRAFFIC_MIGRATION_SECTION}": "edit:traffic",
    f"group:{KAFKA_CLIENTS_GROUP}": "edit:kafkaClusterConfiguration",
    f"group:{SOURCES_GROUP}": "edit:sourceClusters",
    f"group:{TARGETS_GROUP}": "edit:targetClusters",
    f"group:{BACKFILL_GROUP}": "edit:snapshotMigrationConfigs",
    f"group:{CAPTURE_GROUP}": "edit:traffic.proxies",
    f"group:{BUFFER_GROUP}": "edit:traffic.s3Sources",
    f"group:{REPLAY_GROUP}": "edit:traffic.replayers",
}


def display_name_for_plural(plural: str) -> Optional[str]:
    return PLURAL_DISPLAY_NAMES.get(plural)


def group_plurals_for(primary_plural: str) -> List[str]:
    return GROUP_PLURALS_BY_PRIMARY.get(primary_plural, [primary_plural])
