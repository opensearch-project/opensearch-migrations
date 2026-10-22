"""Tests for runtime SnapshotMigration navigation grouping."""

from dataclasses import replace

from console_link.workflow.application.models import ManageNode, ManageSnapshot
from console_link.workflow.application.snapshot_navigation import (
    group_snapshot_migration_navigation,
)


def test_snapshot_migration_navigation_groups_only_useful_prefixes():
    group_id = "group:Snapshot Migration:Backfill"
    resources = (
        ("a", ("source", "target-a", "snap-a", "slice-10"), "warning"),
        ("b", ("source", "target-a", "snap-a", "slice-2"), "ok"),
        ("c", ("source", "target-a", "snap-b", "slice-3"), "error"),
        ("d", ("source", "target-b", "snap-c", "slice-4"), "ok"),
    )
    nodes = {
        group_id: ManageNode(
            id=group_id,
            revision="r",
            parent_id="section:Snapshot Migration",
            kind="group",
            label="Backfill",
            status="ok",
        ),
    }
    for suffix, navigation_key, status in resources:
        node_id = f"resource:snapshotmigrations:{suffix}"
        nodes[node_id] = ManageNode(
            id=node_id,
            revision="r",
            parent_id=group_id,
            kind="resource",
            label=suffix,
            status=status,
            resource_plural="snapshotmigrations",
            resource_name=suffix,
            resource_type="Snapshot migration",
            navigation_key=navigation_key,
        )
    nodes[group_id] = replace(
        nodes[group_id],
        child_ids=tuple(
            f"resource:snapshotmigrations:{suffix}"
            for suffix, _, _ in resources
        ),
    )
    snapshot = ManageSnapshot(
        format_version=1,
        revision="runtime",
        observed_at="2026-09-09T12:00:00+00:00",
        namespace="ma",
        workflow_name="migration-workflow",
        workflow=None,
        root_ids=(group_id,),
        nodes=nodes,
    )

    grouped = group_snapshot_migration_navigation(snapshot)

    source_group = grouped.nodes["snapshot-navigation:1:source"]
    target_group = grouped.nodes[
        "snapshot-navigation:2:source:target-a"
    ]
    assert source_group.label == "source"
    assert source_group.status == "error"
    assert target_group.label == "target-a"
    assert target_group.status == "error"
    assert target_group.child_ids == (
        "resource:snapshotmigrations:b",
        "resource:snapshotmigrations:a",
        "resource:snapshotmigrations:c",
    )
    assert "snapshot-navigation:3:source:target-a:snap-a" not in grouped.nodes
    assert grouped.nodes[
        "resource:snapshotmigrations:d"
    ].parent_id == source_group.id
    assert grouped.nodes["resource:snapshotmigrations:d"].label == (
        "target-b-snap-c-slice-4"
    )


def test_snapshot_navigation_deduplicates_flattened_resources():
    section_id = "section:Snapshot Migration"
    group_id = "group:Snapshot Migration:Backfill"
    resource_id = "resource:snapshotmigrations:source-target-snap-slice-0"
    snapshot = ManageSnapshot(
        format_version=1,
        revision="runtime",
        observed_at="2026-09-10T12:00:00+00:00",
        namespace="ma",
        workflow_name="migration-workflow",
        workflow=None,
        root_ids=(section_id,),
        nodes={
            section_id: ManageNode(
                id=section_id,
                revision="r",
                parent_id=None,
                kind="section",
                label="Snapshot Migration",
                status="ok",
                child_ids=(resource_id, group_id),
            ),
            group_id: ManageNode(
                id=group_id,
                revision="r",
                parent_id=section_id,
                kind="group",
                label="Backfill",
                status="ok",
                child_ids=(resource_id,),
            ),
            resource_id: ManageNode(
                id=resource_id,
                revision="r",
                parent_id=group_id,
                kind="resource",
                label="slice-0",
                status="ok",
                resource_plural="snapshotmigrations",
                resource_name="source-target-snap-slice-0",
                resource_type="Snapshot migration",
                navigation_key=("source", "target", "snap", "slice-0"),
            ),
        },
    )

    grouped = group_snapshot_migration_navigation(snapshot)

    assert grouped.nodes[section_id].child_ids == (resource_id,)
