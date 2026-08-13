"""Tests for the presentation-neutral workflow manage state service."""

import copy
import json
from datetime import datetime, timezone

from console_link.workflow.application.manage_state import ManageStateService
from console_link.workflow.application.models import ManageSnapshot
from console_link.workflow.resource_tree import (
    ResourceGroup,
    ResourceNode,
    ResourceSection,
    _build_tree_from_raw,
)


def _cr(plural, name, phase="Ready", spec=None, status=None):
    return {
        "metadata": {
            "name": name,
            "creationTimestamp": "2026-08-12T12:00:00Z",
        },
        "spec": spec or {},
        "status": {
            "phase": phase,
            **(status or {}),
        },
    }


class _Argo:
    def __init__(self, workflow=None, error="workflow not found"):
        self.workflow = workflow
        self.error = error

    def get_workflow(self, name, namespace):
        if self.workflow is None:
            return {"success": False, "error": self.error}, {}
        return {"success": True}, copy.deepcopy(self.workflow)


class _Config:
    def __init__(self, snapshots):
        self.snapshots = snapshots

    def load_resource_config_snapshots(self, workflow_name):
        return copy.deepcopy(self.snapshots)


def _service(raw, workflow=None, snapshots=None, clock=None):
    return ManageStateService(
        namespace="ma",
        workflow_name="migration",
        argo_service=_Argo(workflow),
        resource_loader=lambda namespace: _build_tree_from_raw(copy.deepcopy(raw)),
        config_service_provider=(lambda: _Config(snapshots)) if snapshots is not None else None,
        clock=clock,
    )


def _node(snapshot, suffix):
    return next(node for node in snapshot.nodes.values() if node.id.endswith(suffix))


def _capabilities(node):
    return {capability.kind: capability for capability in node.capabilities}


def test_no_workflow_still_returns_cluster_resources_without_presentation_markup():
    snapshot = _service({
        "captureproxies": [
            _cr("captureproxies", "capture", spec={"listenPort": 9201}),
        ],
    }).observe()

    assert isinstance(snapshot, ManageSnapshot)
    assert snapshot.workflow is None
    assert snapshot.problems[0].source == "argo"
    resource = _node(snapshot, "captureproxies:capture")
    assert resource.label == "capture"
    assert resource.phase == "Ready"
    assert {"edit", "logs", "reset"} <= set(_capabilities(resource))

    serialized = json.dumps(snapshot.to_dict(), sort_keys=True)
    assert "[bold]" not in serialized
    assert "[green]" not in serialized
    assert "✓" not in serialized
    assert "▶" not in serialized


def test_active_workflow_adds_secondary_step_detail_to_owning_resource():
    workflow = {
        "metadata": {"name": "migration", "resourceVersion": "9"},
        "status": {
            "phase": "Running",
            "startedAt": "2026-08-12T12:00:00Z",
            "nodes": {
                "resource-group": {
                    "id": "resource-group",
                    "displayName": "capture",
                    "type": "Steps",
                    "phase": "Running",
                    "inputs": {
                        "parameters": [
                            {"name": "groupName_view", "value": "Capture"},
                            {"name": "resourceName", "value": "capture"},
                        ],
                    },
                },
                "pod": {
                    "id": "pod",
                    "displayName": "deployCapture",
                    "type": "Pod",
                    "phase": "Running",
                    "boundaryID": "resource-group",
                },
            },
        },
    }

    snapshot = _service({
        "captureproxies": [_cr("captureproxies", "capture", phase="Pending")],
    }, workflow=workflow).observe()

    assert snapshot.workflow is not None
    assert snapshot.workflow.phase == "Running"
    resource = _node(snapshot, "captureproxies:capture")
    steps = [snapshot.nodes[node_id] for node_id in resource.child_ids]
    assert [(step.kind, step.label, step.phase) for step in steps] == [
        ("workflow-step", "deployCapture", "Running"),
    ]
    assert "logs" in _capabilities(steps[0])


def test_failed_resource_has_semantic_error_status():
    snapshot = _service({
        "datasnapshots": [
            _cr(
                "datasnapshots",
                "catalog",
                phase="Failed",
                status={"message": "Snapshot job failed"},
            ),
        ],
    }).observe()

    resource = _node(snapshot, "datasnapshots:catalog")
    assert resource.status == "error"
    assert resource.phase == "Failed"
    assert snapshot.nodes[resource.parent_id].status == "error"
    assert snapshot.nodes[snapshot.nodes[resource.parent_id].parent_id].status == "error"


def test_pending_only_resource_has_edit_but_no_cluster_actions():
    snapshots = {
        "pending": {
            "resources": [{
                "kind": "TrafficReplay",
                "name": "replay",
                "parameters": {"podReplicas": 2},
            }],
        },
        "pending_console": {},
    }

    snapshot = _service({}, snapshots=snapshots).observe()

    resource = _node(snapshot, "trafficreplays:replay")
    assert resource.phase == "Pending Config"
    assert set(_capabilities(resource)) == {"edit"}


def test_edit_capability_targets_the_config_processor_branch_from_provenance():
    snapshots = {
        "pending": {
            "resources": [{
                "kind": "CaptureProxy",
                "name": "capture",
                "parameters": {
                    "source": "legacy",
                    "listenPort": 9201,
                },
                "parameterProvenance": {
                    "source": {
                        "presence": "authored",
                        "sourcePath": ["traffic", "proxies", "capture", "source"],
                    },
                    "listenPort": {
                        "presence": "authored",
                        "sourcePath": [
                            "traffic",
                            "proxies",
                            "capture",
                            "proxyConfig",
                            "listenPort",
                        ],
                    },
                },
            }],
        },
        "pending_console": {},
    }

    snapshot = _service({}, snapshots=snapshots).observe()

    resource = _node(snapshot, "captureproxies:capture")
    assert _capabilities(resource)["edit"].target_id == (
        "edit:traffic.proxies.capture"
    )


def test_incomplete_capture_proxy_uses_its_config_collection_as_edit_fallback():
    snapshots = {
        "pending": {
            "resources": [{
                "kind": "CaptureProxy",
                "name": "p2",
                "parameters": {
                    "source": "legacy",
                    "proxyConfig": {},
                },
                "diagnostics": [{
                    "severity": "required",
                    "path": [
                        "traffic",
                        "proxies",
                        "p2",
                        "proxyConfig",
                        "listenPort",
                    ],
                    "message": "Listen port is required.",
                }],
            }],
        },
        "pending_console": {},
    }

    snapshot = _service({}, snapshots=snapshots).observe()

    resource = _node(snapshot, "captureproxies:p2")
    assert _capabilities(resource)["edit"].target_id == (
        "edit:traffic.proxies.p2"
    )


def test_edit_capability_ignores_inherited_provenance_outside_the_owner_branch():
    snapshots = {
        "pending": {
            "resources": [{
                "kind": "SnapshotMigration",
                "name": "source-target-nightly-migrate",
                "parameters": {
                    "sourceLabel": "source",
                    "targetLabel": "target",
                    "snapshotLabel": "nightly",
                    "sourceVersion": "2.17",
                    "metadataMigrationEnabled": True,
                },
                "parameterProvenance": {
                    "sourceLabel": {
                        "presence": "inherited",
                        "sourcePath": ["snapshotMigrationConfigs", "0", "fromSource"],
                    },
                    "targetLabel": {
                        "presence": "inherited",
                        "sourcePath": ["snapshotMigrationConfigs", "0", "toTarget"],
                    },
                    "snapshotLabel": {
                        "presence": "inherited",
                        "sourcePath": [
                            "snapshotMigrationConfigs",
                            "0",
                            "perSnapshotConfig",
                            "nightly",
                        ],
                    },
                    "metadataMigrationEnabled": {
                        "presence": "authored",
                        "sourcePath": [
                            "snapshotMigrationConfigs",
                            "0",
                            "perSnapshotConfig",
                            "nightly",
                            "0",
                            "metadataMigrationConfig",
                            "enabled",
                        ],
                    },
                    "sourceVersion": {
                        "presence": "inherited",
                        "sourcePath": ["sourceClusters", "source", "version"],
                    },
                },
            }],
        },
        "pending_console": {},
    }

    snapshot = _service({}, snapshots=snapshots).observe()

    resource = _node(
        snapshot,
        "snapshotmigrations:source-target-nightly-migrate",
    )
    assert _capabilities(resource)["edit"].target_id == (
        "edit:snapshotMigrationConfigs.0"
    )


def test_config_comparison_preserves_deployed_submitted_and_pending_values():
    snapshots = {
        "submitted": {
            "resources": [{
                "kind": "CaptureProxy",
                "name": "capture",
                "parameters": {"listenPort": 9202},
            }],
        },
        "pending": {
            "resources": [{
                "kind": "CaptureProxy",
                "name": "capture",
                "parameters": {"listenPort": 9203},
            }],
        },
    }

    snapshot = _service({
        "captureproxies": [
            _cr("captureproxies", "capture", spec={"listenPort": 9201}),
        ],
    }, workflow={"metadata": {"name": "migration"}, "status": {"phase": "Running"}}, snapshots=snapshots).observe()

    resource = _node(snapshot, "captureproxies:capture")
    comparison = next(item for item in resource.comparisons if item.path == "listenPort")
    assert comparison.deployed.value == 9201
    assert comparison.submitted.value == 9202
    assert comparison.pending.value == 9203
    assert comparison.submitted_changed is True
    assert comparison.pending_changed is True
    assert resource.config_presence == {
        "deployed": True,
        "submitted": True,
        "pending": True,
    }


def test_saved_resource_removal_has_an_explicit_navigation_summary():
    snapshots = {
        "submitted_console": {
            "sources": [{
                "refName": "source",
                "clientConfig": {"endpoint": "https://source.example.com"},
            }],
        },
        "pending_console": {"sources": []},
    }
    workflow = {
        "metadata": {"name": "migration"},
        "status": {"phase": "Running"},
    }

    snapshot = _service({}, workflow=workflow, snapshots=snapshots).observe()

    source = _node(snapshot, "sourceconfigs:source")
    assert source.config_presence == {
        "deployed": False,
        "submitted": True,
        "pending": False,
    }
    assert source.value_summary == "Removal pending submission"


def test_approval_and_output_capabilities_name_exact_targets():
    approval = {
        "id": "approval-node",
        "display_name": "Approve metadata",
        "phase": "Running",
        "type": "Pod",
        "is_approval": True,
        "inputs": {
            "parameters": [{
                "name": "resourceName",
                "value": "snapshotmigration.migration-0.vapretry",
            }],
        },
        "children": [],
    }
    resource = ResourceNode(
        name="migration-0",
        plural="snapshotmigrations",
        phase="Pending",
        depends_on=[],
        spec={},
        status={},
        workflow_progress=[approval],
    )
    sections = [
        ResourceSection(
            name="Snapshot Migration",
            groups=[
                ResourceGroup(
                    plural="snapshotmigrations",
                    display_name="Backfill",
                    resources=[resource],
                ),
            ],
        ),
    ]
    workflow = {
        "metadata": {"name": "migration"},
        "status": {
            "phase": "Running",
            "nodes": {
                "patch-output": {
                    "id": "patch-output",
                    "displayName": "patchMetadataEvaluateOutput",
                    "type": "Pod",
                    "phase": "Succeeded",
                    "inputs": {
                        "parameters": [{
                            "name": "resourceName",
                            "value": "migration-0",
                        }],
                    },
                },
            },
        },
    }
    service = _service({})

    snapshot = service.build_snapshot(sections, workflow)

    resource_node = _node(snapshot, "snapshotmigrations:migration-0")
    capabilities = _capabilities(resource_node)
    assert capabilities["approve"].target_id == "approval:approval-node"
    assert capabilities["approve"].label == "Approve metadata"
    assert capabilities["output"].target_id == (
        "output:snapshotmigrations:migration-0:metadataEvaluate"
    )


def test_revisions_are_deterministic_and_change_only_for_node_and_ancestors():
    raw = {
        "captureproxies": [_cr("captureproxies", "capture", phase="Ready")],
        "trafficreplays": [_cr("trafficreplays", "replay", phase="Ready")],
    }
    times = iter([
        datetime(2026, 8, 12, 12, 0, tzinfo=timezone.utc),
        datetime(2026, 8, 12, 12, 1, tzinfo=timezone.utc),
        datetime(2026, 8, 12, 12, 2, tzinfo=timezone.utc),
    ])
    service = _service(raw, clock=lambda: next(times))

    first = service.observe()
    second = service.observe()

    assert first.observed_at != second.observed_at
    assert first.revision == second.revision
    assert first.nodes == second.nodes

    raw["captureproxies"][0]["status"]["phase"] = "Failed"
    third = service.observe()
    changed_ids = {
        node_id
        for node_id, node in first.nodes.items()
        if third.nodes[node_id].revision != node.revision
    }

    assert changed_ids == {
        "section:Live Traffic Migration",
        "group:Live Traffic Migration:Capture",
        "resource:captureproxies:capture",
    }
    assert (
        first.nodes["resource:trafficreplays:replay"].revision
        == third.nodes["resource:trafficreplays:replay"].revision
    )
