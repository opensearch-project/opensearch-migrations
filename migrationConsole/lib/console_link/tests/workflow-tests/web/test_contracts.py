from datetime import datetime, timezone

from console_link.workflow.application.models import (
    ManageCapability,
    ManageNode,
    ManageRelationship,
    ManageSnapshot,
)
from console_link.workflow.web.contracts import ManageSnapshotV1


def test_snapshot_transport_uses_camel_case_and_exact_capability_target():
    snapshot = ManageSnapshot(
        format_version=1,
        revision="snapshot-1",
        observed_at="2026-08-12T12:00:00Z",
        namespace="ma",
        workflow_name="migration",
        workflow=None,
        root_ids=("resource:captureproxies:capture",),
        nodes={
            "resource:captureproxies:capture": ManageNode(
                id="resource:captureproxies:capture",
                revision="node-1",
                kind="resource",
                label="capture",
                status="ok",
                relationships=(
                    ManageRelationship(
                        kind="runtime-dependency",
                        direction="requires",
                        target_id="resource:kafkaclusters:default",
                        target_name="default",
                        target_plural="kafkaclusters",
                        target_phase="Ready",
                        target_status="ok",
                    ),
                ),
                capabilities=(
                    ManageCapability(
                        kind="edit",
                        target_id="edit:captureproxies:capture",
                        label="Edit capture",
                        disabled_reason="Reset capture before retrying.",
                    ),
                ),
            ),
        },
    )

    payload = ManageSnapshotV1.from_domain(snapshot).model_dump(
        by_alias=True,
        mode="json",
        exclude_none=True,
    )

    assert payload["formatVersion"] == 1
    assert payload["observedAt"] == "2026-08-12T12:00:00Z"
    assert payload["rootIds"] == ["resource:captureproxies:capture"]
    assert payload["nodes"]["resource:captureproxies:capture"]["capabilities"] == [
        {
            "kind": "edit",
            "editTargetId": "edit:captureproxies:capture",
            "label": "Edit capture",
            "disabledReason": "Reset capture before retrying.",
        },
    ]
    assert payload["nodes"]["resource:captureproxies:capture"]["relationships"] == [
        {
            "kind": "runtime-dependency",
            "direction": "requires",
            "targetId": "resource:kafkaclusters:default",
            "targetName": "default",
            "targetPlural": "kafkaclusters",
            "targetPhase": "Ready",
            "targetStatus": "ok",
        },
    ]


def test_transport_rejects_non_utc_observation_time():
    snapshot = ManageSnapshot(
        format_version=1,
        revision="snapshot-1",
        observed_at=datetime(
            2026,
            8,
            12,
            12,
            0,
            tzinfo=timezone.utc,
        ).isoformat(),
        namespace="ma",
        workflow_name="migration",
        workflow=None,
        root_ids=(),
        nodes={},
    )

    transport = ManageSnapshotV1.from_domain(snapshot)

    assert transport.observed_at.tzinfo is not None
