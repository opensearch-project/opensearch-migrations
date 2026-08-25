"""Tests for the presentation-neutral workflow manage state service."""

import copy
import json
from datetime import datetime, timezone

from kubernetes.client.rest import ApiException

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


def test_kubernetes_authentication_problem_does_not_expose_raw_response():
    service = ManageStateService(
        namespace="ma",
        workflow_name="migration",
        resource_loader=lambda _namespace: (_ for _ in ()).throw(
            ApiException(
                status=401,
                reason="Unauthorized",
                http_resp=type("Response", (), {
                    "status": 401,
                    "reason": "Unauthorized",
                    "data": '{"token": "sensitive"}',
                    "getheaders": lambda self: {"Audit-Id": "private"},
                })(),
            )
        ),
    )

    snapshot = service.observe()

    assert len(snapshot.problems) == 1
    assert snapshot.problems[0].message == (
        "Kubernetes authentication failed for the cluster selected when "
        "this server started."
    )
    assert "sensitive" not in snapshot.problems[0].message


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


def test_resource_dependencies_are_typed_bidirectional_and_keep_unresolved_targets():
    snapshot = _service({
        "kafkaclusters": [
            _cr("kafkaclusters", "default", phase="Created"),
        ],
        "capturedtraffics": [
            _cr(
                "capturedtraffics",
                "capture-topic",
                phase="Pending",
                spec={"dependsOn": ["default", "missing-cluster"]},
            ),
        ],
    }).observe()

    topic = _node(snapshot, "capturedtraffics:capture-topic")
    cluster = _node(snapshot, "kafkaclusters:default")

    assert [
        relationship.to_dict()
        for relationship in topic.relationships
    ] == [
        {
            "kind": "runtime-dependency",
            "direction": "requires",
            "targetId": "resource:kafkaclusters:default",
            "targetName": "default",
            "targetPlural": "kafkaclusters",
            "targetPhase": "Created",
            "targetStatus": "pending",
        },
        {
            "kind": "runtime-dependency",
            "direction": "requires",
            "targetName": "missing-cluster",
            "targetStatus": "unknown",
        },
    ]
    assert [
        relationship.to_dict()
        for relationship in cluster.relationships
    ] == [{
        "kind": "runtime-dependency",
        "direction": "required-by",
        "targetId": "resource:capturedtraffics:capture-topic",
        "targetName": "capture-topic",
        "targetPlural": "capturedtraffics",
        "targetPhase": "Pending",
        "targetStatus": "pending",
    }]


def test_pending_overlay_dependencies_link_to_other_pending_resources():
    snapshots = {
        "pending": {
            "resources": [
                {
                    "kind": "CapturedTraffic",
                    "name": "p2-topic",
                    "parameters": {},
                },
                {
                    "kind": "CaptureProxy",
                    "name": "p2",
                    "parameters": {"dependsOn": ["p2-topic"]},
                },
                {
                    "kind": "DataSnapshot",
                    "name": "source-snap1",
                    "parameters": {"dependsOn": ["p2"]},
                },
                {
                    "kind": "SnapshotMigration",
                    "name": "source-target-snap1-migration-0",
                    "parameters": {"dependsOn": ["source-snap1"]},
                },
            ],
        },
        "pending_console": {},
    }

    snapshot = _service({}, snapshots=snapshots).observe()

    proxy = _node(snapshot, "captureproxies:p2")
    data_snapshot = _node(snapshot, "datasnapshots:source-snap1")
    migration = _node(
        snapshot,
        "snapshotmigrations:source-target-snap1-migration-0",
    )
    assert proxy.relationships[0].target_id == (
        "resource:capturedtraffics:p2-topic"
    )
    assert data_snapshot.relationships[0].target_id == (
        "resource:captureproxies:p2"
    )
    assert migration.relationships[0].target_id == (
        "resource:datasnapshots:source-snap1"
    )


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


def test_capture_proxy_nested_provenance_still_targets_the_owning_config():
    snapshots = {
        "pending": {
            "resources": [{
                "kind": "CaptureProxy",
                "name": "p2",
                "parameters": {
                    "listenPort": 9201,
                    "serviceType": "ClusterIP",
                },
                "parameterProvenance": {
                    "listenPort": {
                        "presence": "authored",
                        "sourcePath": [
                            "traffic",
                            "proxies",
                            "p2",
                            "proxyConfig",
                            "listenPort",
                        ],
                    },
                    "serviceType": {
                        "presence": "authored",
                        "sourcePath": [
                            "traffic",
                            "proxies",
                            "p2",
                            "proxyConfig",
                            "serviceType",
                        ],
                    },
                },
            }],
        },
        "pending_console": {},
    }

    snapshot = _service({}, snapshots=snapshots).observe()

    resource = _node(snapshot, "captureproxies:p2")
    assert _capabilities(resource)["edit"].target_id == (
        "edit:traffic.proxies.p2"
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


def test_deployed_resource_removed_from_pending_config_will_be_orphaned():
    source_config = {
        "refName": "source",
        "clientConfig": {"endpoint": "https://source.example.com"},
    }
    snapshots = {
        "submitted_console": {"sources": [source_config]},
        "pending_console": {"sources": []},
    }
    workflow = {
        "metadata": {"name": "migration"},
        "status": {"phase": "Running"},
    }

    snapshot = _service({
        "sourceconfigs": [
            _cr(
                "sourceconfigs",
                "source",
                spec={"endpoint": "https://source.example.com"},
            ),
        ],
    }, workflow=workflow, snapshots=snapshots).observe()

    source = _node(snapshot, "sourceconfigs:source")
    assert source.config_presence == {
        "deployed": True,
        "submitted": True,
        "pending": False,
    }
    assert source.value_summary == "Will be orphaned by new configuration"


def test_deployed_resource_missing_from_submitted_config_requires_cleanup():
    snapshots = {
        "submitted_console": {"sources": []},
        "pending_console": {"sources": []},
    }
    workflow = {
        "metadata": {"name": "migration"},
        "status": {"phase": "Running"},
    }

    snapshot = _service({
        "sourceconfigs": [
            _cr(
                "sourceconfigs",
                "source",
                spec={"endpoint": "https://source.example.com"},
            ),
        ],
    }, workflow=workflow, snapshots=snapshots).observe()

    source = _node(snapshot, "sourceconfigs:source")
    assert source.config_presence == {
        "deployed": True,
        "submitted": False,
        "pending": False,
    }
    assert source.value_summary == "Orphaned; cleanup required"


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


def test_vap_retry_failure_is_lifted_with_reset_before_retry_remedy():
    failure_message = (
        'main: Error (exit code 64): no more retries The capturedtraffics '
        '"p2-topic" is invalid: ValidatingAdmissionPolicy denied request: '
        "Impossible: sourceLabel cannot be changed. Delete and recreate."
    )
    approval = {
        "id": "gate-1",
        "display_name": "Apply",
        "phase": "Running",
        "type": "Retry",
        "is_approval": True,
        "denial_reason": (
            "Impossible: sourceLabel cannot be changed. Delete and recreate"
        ),
        "message": failure_message,
        "inputs": {
            "parameters": [{
                "name": "resourceName",
                "value": "capturedtraffic.p2-topic.vapretry",
            }],
        },
        "children": [],
    }
    resource = ResourceNode(
        name="p2-topic",
        plural="capturedtraffics",
        phase="Ready",
        depends_on=[],
        spec={},
        status={},
        config_presence={
            "deployed": True,
            "submitted": True,
            "pending": True,
        },
        workflow_progress=[approval],
    )
    dependent = ResourceNode(
        name="p2",
        plural="captureproxies",
        phase="Error",
        depends_on=["p2-topic"],
        dependency_states=[{
            "name": "p2-topic",
            "phase": "Ready",
            "plural": "capturedtraffics",
        }],
        spec={},
        status={},
        config_presence={
            "deployed": True,
            "submitted": True,
            "pending": True,
        },
        workflow_progress=[approval],
    )
    sections = [
        ResourceSection(
            name="Live Traffic Migration",
            groups=[
                ResourceGroup(
                    plural="capturedtraffics",
                    display_name="Buffer",
                    resources=[resource],
                ),
                ResourceGroup(
                    plural="captureproxies",
                    display_name="Capture",
                    resources=[dependent],
                ),
            ],
        ),
    ]

    snapshot = _service({}).build_snapshot(sections, {
        "metadata": {"name": "migration"},
        "status": {"phase": "Running"},
    })

    resource_node = _node(snapshot, "capturedtraffics:p2-topic")
    assert resource_node.phase == "Ready"
    assert resource_node.status == "blocked"
    assert [diagnostic.to_dict() for diagnostic in resource_node.diagnostics] == [{
        "severity": "error",
        "message": (
            "Impossible: sourceLabel cannot be changed. Delete and recreate."
        ),
        "path": [],
        "source": "workflow-apply",
        "code": "immutable-resource-update",
        "title": "Apply failed; reset required",
        "remedy": (
            "Reset p2-topic to delete and recreate it, then submit a "
            "replacement workflow."
        ),
        "technicalDetail": failure_message,
    }]
    capabilities = _capabilities(resource_node)
    assert capabilities["approve"].label == "Retry apply"
    assert capabilities["approve"].disabled_reason == (
        "Reset p2-topic and submit a replacement workflow; the current "
        "workflow cannot recreate it."
    )
    apply_step = snapshot.nodes[resource_node.child_ids[0]]
    assert apply_step.label == "Apply failed"
    assert apply_step.phase == "Blocked"
    assert apply_step.status == "blocked"

    dependent_node = _node(snapshot, "captureproxies:p2")
    assert [diagnostic.to_dict() for diagnostic in dependent_node.diagnostics] == [{
        "severity": "error",
        "message": (
            "Impossible: sourceLabel cannot be changed. Delete and recreate."
        ),
        "path": [],
        "source": "workflow-apply",
        "code": "immutable-resource-update",
        "title": "Blocked by p2-topic apply failure",
        "remedy": (
            "Open p2-topic, reset it to delete and recreate it, then submit "
            "a replacement workflow."
        ),
        "technicalDetail": failure_message,
    }]
    assert "approve" not in _capabilities(dependent_node)
    dependent_step = snapshot.nodes[dependent_node.child_ids[0]]
    assert dependent_step.label == "p2-topic apply failed"
    assert "approve" not in _capabilities(dependent_step)
    assert next(
        detail.value
        for detail in dependent_step.details
        if detail.kind == "remedy"
    ) == (
        "Open p2-topic, reset it to delete and recreate it, then submit a "
        "replacement workflow."
    )


def test_vap_retry_for_absent_resource_requires_replacement_workflow():
    approval = {
        "id": "gate-1",
        "display_name": "Apply",
        "phase": "Running",
        "type": "Retry",
        "is_approval": True,
        "denial_reason": (
            "Impossible: sourceVersion cannot be changed. Delete and recreate"
        ),
        "inputs": {
            "parameters": [{
                "name": "resourceName",
                "value": "datasnapshot.source-snap1.vapretry",
            }],
        },
        "children": [],
    }
    resource = ResourceNode(
        name="source-snap1",
        plural="datasnapshots",
        phase="Pending Config",
        depends_on=[],
        spec={},
        status={},
        config_presence={
            "deployed": False,
            "submitted": True,
            "pending": True,
        },
        workflow_progress=[approval],
    )
    sections = [ResourceSection(
        name="Snapshot Migration",
        groups=[ResourceGroup(
            plural="datasnapshots",
            display_name="Snapshots",
            resources=[resource],
        )],
    )]

    snapshot = _service({}).build_snapshot(sections)
    resource_node = _node(snapshot, "datasnapshots:source-snap1")
    diagnostic = resource_node.diagnostics[0]

    assert diagnostic.title == "Replacement workflow required"
    assert diagnostic.remedy == (
        "source-snap1 is already absent. Submit a replacement workflow to "
        "recreate it from the saved configuration."
    )
    assert _capabilities(resource_node)["approve"].disabled_reason == (
        "Submit a replacement workflow to recreate source-snap1; the current "
        "workflow cannot recreate it."
    )


def test_approval_is_attributed_after_pending_resources_are_projected():
    failure_message = (
        'main: Error (exit code 64): The capturedtraffics "p2-topic" is '
        "invalid: denied request: Impossible: sourceLabel cannot be changed. "
        "Delete and recreate."
    )
    workflow = {
        "metadata": {"name": "migration"},
        "status": {
            "phase": "Running",
            "nodes": {
                "proxy": {
                    "id": "proxy",
                    "displayName": "Create Proxy: p2",
                    "type": "Steps",
                    "phase": "Running",
                    "inputs": {
                        "parameters": [
                            {"name": "groupName_view", "value": "p2"},
                            {"name": "resourceName", "value": "p2"},
                        ],
                    },
                },
                "retry-group": {
                    "id": "retry-group",
                    "displayName": "reconcileCapturedTrafficResource",
                    "type": "Steps",
                    "phase": "Running",
                    "boundaryID": "proxy",
                },
                "try": {
                    "id": "try",
                    "displayName": "tryApply",
                    "type": "Pod",
                    "phase": "Failed",
                    "boundaryID": "retry-group",
                    "message": failure_message,
                },
                "wait": {
                    "id": "wait",
                    "displayName": "waitForFix",
                    "templateName": "waitForUserApproval",
                    "type": "Pod",
                    "phase": "Running",
                    "boundaryID": "retry-group",
                    "inputs": {
                        "parameters": [{
                            "name": "resourceName",
                            "value": "capturedtraffic.p2-topic.vapretry",
                        }],
                    },
                },
            },
        },
    }
    snapshots = {
        "submitted": {
            "resources": [{
                "kind": "CaptureProxy",
                "name": "p2",
                "parameters": {"dependsOn": ["p2-topic"]},
            }],
        },
        "pending": {
            "resources": [{
                "kind": "CaptureProxy",
                "name": "p2",
                "parameters": {"dependsOn": ["p2-topic"]},
            }],
        },
        "submitted_console": {},
        "pending_console": {},
    }

    snapshot = _service({
        "capturedtraffics": [
            _cr("capturedtraffics", "p2-topic", phase="Ready"),
        ],
    }, workflow=workflow, snapshots=snapshots).observe()

    topic = _node(snapshot, "capturedtraffics:p2-topic")
    proxy = _node(snapshot, "captureproxies:p2")

    assert topic.status == "blocked"
    assert topic.diagnostics[0].message == (
        "Impossible: sourceLabel cannot be changed. Delete and recreate."
    )
    assert _capabilities(topic)["approve"].target_id == "approval:wait"
    assert proxy.diagnostics[0].title == (
        "Blocked by p2-topic apply failure"
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
