import pytest

from console_link.workflow.application.actions import (
    ApprovalGateInventory,
    ApprovalService,
    ApprovalStale,
    ApprovalUnavailable,
)
from console_link.workflow.application.resets import (
    ResetPlanStale,
    ResetService,
)


def _approval_workflow():
    return {
        "metadata": {"name": "migration"},
        "status": {
            "nodes": {
                "approval-node": {
                    "id": "approval-node",
                    "displayName": "waitForUserApproval",
                    "templateName": "waitForUserApproval",
                    "phase": "Running",
                    "inputs": {
                        "parameters": [{
                            "name": "resourceName",
                            "value": (
                                "evaluatemetadata.source-target-snapshot-main"
                            ),
                        }],
                    },
                },
            },
        },
    }


def _gate(version="11", phase="Pending"):
    return {
        "metadata": {
            "name": "evaluatemetadata.source-target-snapshot-main",
            "resourceVersion": version,
            "labels": {
                "migrations.opensearch.org/resource-kind": (
                    "SnapshotMigration"
                ),
                "migrations.opensearch.org/resource-name": "migration-0",
            },
        },
        "status": {"phase": phase},
    }


def test_approval_review_names_exact_gate_resource_stage_and_effect():
    approved = []
    service = ApprovalService(
        namespace="ma",
        workflow_name="migration",
        workflow_loader=_approval_workflow,
        gate_loader=lambda _name: _gate(),
        approver=lambda name: approved.append(name) or True,
    )

    review = service.review(
        "approval:approval-node",
        snapshot_revision="snapshot-before",
    )

    assert review.gate_name == (
        "evaluatemetadata.source-target-snapshot-main"
    )
    assert review.resource_id == (
        "resource:snapshotmigrations:migration-0"
    )
    assert review.resource_name == "migration-0"
    assert review.stage == "Metadata evaluation"
    assert "metadata migration" in review.effect.lower()

    service.approve(
        review.target_id,
        review.gate_revision,
    )
    assert approved == [review.gate_name]


def test_approval_review_extracts_the_vap_denial_reason():
    workflow = _approval_workflow()
    approval = workflow["status"]["nodes"]["approval-node"]
    approval["boundaryID"] = "retry-group"
    workflow["status"]["nodes"]["failed-apply"] = {
        "id": "failed-apply",
        "displayName": "tryApply",
        "phase": "Failed",
        "boundaryID": "retry-group",
        "message": (
            'main: Error (exit code 64): The datasnapshots "source-snap1" '
            "is invalid: ValidatingAdmissionPolicy denied request: Impossible: "
            "sourceVersion cannot be changed. Delete and recreate."
        ),
    }
    service = ApprovalService(
        namespace="ma",
        workflow_name="migration",
        workflow_loader=lambda: workflow,
        gate_loader=lambda _name: _gate(),
    )

    review = service.review("approval:approval-node")

    assert review.reason == (
        "Impossible: sourceVersion cannot be changed. Delete and recreate"
    )


def test_approval_rejects_changed_or_completed_gate():
    gate = _gate()
    service = ApprovalService(
        namespace="ma",
        workflow_name="migration",
        workflow_loader=_approval_workflow,
        gate_loader=lambda _name: gate,
        approver=lambda _name: True,
    )
    review = service.review("approval:approval-node")
    gate["metadata"]["resourceVersion"] = "12"

    with pytest.raises(ApprovalStale):
        service.approve(review.target_id, review.gate_revision)


def test_approval_reports_a_gate_removed_after_observation_as_unavailable():
    service = ApprovalService(
        namespace="ma",
        workflow_name="migration",
        workflow_loader=_approval_workflow,
        gate_loader=lambda _name: (_ for _ in ()).throw(
            RuntimeError("ApprovalGate was deleted")
        ),
        approver=lambda _name: True,
    )

    with pytest.raises(ApprovalUnavailable, match="no longer available"):
        service.review("approval:approval-node")


def test_approval_inventory_classifies_upcoming_preapproved_blocking_and_passed():
    workflow = _approval_workflow()
    workflow["status"]["nodes"]["approval-node"]["inputs"]["parameters"][0][
        "value"
    ] = "captureproxy.p2.vapretry"
    workflow["status"]["nodes"]["completed-approval"] = {
        "id": "completed-approval",
        "displayName": "waitForUserApproval",
        "templateName": "waitForUserApproval",
        "phase": "Succeeded",
        "inputs": {
            "parameters": [{
                "name": "resourceName",
                "value": "evaluatemetadata.migration-0",
            }],
        },
    }
    workflow["status"]["nodes"]["failed-apply"] = {
        "id": "failed-apply",
        "displayName": "tryApply",
        "phase": "Failed",
        "boundaryID": "retry-group",
        "message": "denied request: Impossible: serviceType cannot change",
    }
    workflow["status"]["nodes"]["approval-node"]["boundaryID"] = "retry-group"
    labels = {
        "migrations.opensearch.org/workflow": "migration",
        "migrations.opensearch.org/resource-kind": "SnapshotMigration",
        "migrations.opensearch.org/resource-name": "migration-0",
    }
    gates = [
        {
            "metadata": {
                "name": "evaluatemetadata.migration-0",
                "resourceVersion": "1",
                "labels": labels,
            },
            "status": {"phase": "Approved"},
        },
        {
            "metadata": {
                "name": "migratemetadata.migration-0",
                "resourceVersion": "2",
                "labels": labels,
            },
            "status": {"phase": "Approved"},
        },
        {
            "metadata": {
                "name": "documentbackfill.migration-0",
                "resourceVersion": "3",
                "labels": labels,
            },
            "status": {"phase": "Created"},
        },
        {
            "metadata": {
                "name": "captureproxysetup.p2",
                "resourceVersion": "4",
                "labels": {
                    **labels,
                    "migrations.opensearch.org/resource-kind": "CaptureProxy",
                    "migrations.opensearch.org/resource-name": "p2",
                },
            },
            "status": {"phase": "Created"},
        },
        {
            "metadata": {
                "name": "captureproxy.p2.vapretry",
                "resourceVersion": "5",
                "labels": {
                    **labels,
                    "migrations.opensearch.org/resource-kind": "CaptureProxy",
                    "migrations.opensearch.org/resource-name": "p2",
                },
            },
            "status": {"phase": "Created"},
        },
    ]
    service = ApprovalService(
        namespace="ma",
        workflow_name="migration",
        workflow_loader=lambda: workflow,
        gate_inventory_loader=lambda: gates,
        resolved_config_loader=lambda: {
            "requireBeginApproval": False,
            "proxies": [{"name": "p2", "skipApproval": False}],
            "snapshotMigrations": [{
                "resourceName": "migration-0",
                "metadataMigrationConfig": {
                    "skipEvaluateApproval": False,
                    "skipMigrateApproval": False,
                },
                "documentBackfillConfig": {
                    "skipApproval": True,
                },
            }],
        },
    )

    inventory = service.inventory()
    assert isinstance(inventory, ApprovalGateInventory)
    by_name = {gate.name: gate for gate in inventory.gates}
    assert by_name["evaluatemetadata.migration-0"].state == "passed"
    assert by_name["migratemetadata.migration-0"].state == "preapproved"
    assert by_name["migratemetadata.migration-0"].toggleable is True
    assert by_name["documentbackfill.migration-0"].state == "not-required"
    assert by_name["documentbackfill.migration-0"].toggleable is False
    assert by_name["captureproxysetup.p2"].state == "upcoming"
    assert by_name["captureproxysetup.p2"].toggleable is True
    recovery = by_name["captureproxy.p2.vapretry"]
    assert recovery.category == "recovery"
    assert recovery.state == "blocking"
    assert recovery.approval_target_id == "approval:approval-node"
    assert recovery.reason == "Impossible: serviceType cannot change"


def test_approval_inventory_lists_gates_before_workflow_exists():
    service = ApprovalService(
        namespace="ma",
        workflow_name="migration",
        workflow_loader=lambda: {},
        gate_inventory_loader=lambda: [_gate(phase="Approved")],
        resolved_config_loader=lambda: {
            "snapshotMigrations": [{
                "resourceName": "migration-0",
                "metadataMigrationConfig": {
                    "skipEvaluateApproval": False,
                },
            }],
        },
    )

    inventory = service.inventory()

    assert inventory.workflow_name == "migration"
    assert len(inventory.gates) == 1
    assert inventory.gates[0].state == "preapproved"


def test_preapproval_can_only_toggle_an_unreached_enabled_checkpoint():
    phases = []
    gate = {
        "metadata": {
            "name": "captureproxysetup.p2",
            "resourceVersion": "7",
            "labels": {
                "migrations.opensearch.org/workflow": "migration",
                "migrations.opensearch.org/resource-kind": "CaptureProxy",
                "migrations.opensearch.org/resource-name": "p2",
            },
        },
        "status": {"phase": "Created"},
    }
    service = ApprovalService(
        namespace="ma",
        workflow_name="migration",
        workflow_loader=lambda: {
            "metadata": {"name": "migration"},
            "status": {"phase": "Running", "nodes": {}},
        },
        gate_inventory_loader=lambda: [gate],
        resolved_config_loader=lambda: {
            "proxies": [{"name": "p2", "skipApproval": False}],
        },
        phase_setter=lambda name, phase: phases.append((name, phase)) or True,
    )

    updated = service.set_preapproval(
        "captureproxysetup.p2",
        expected_gate_revision="7",
        preapproved=True,
    )

    assert updated.name == "captureproxysetup.p2"
    assert phases == [("captureproxysetup.p2", "Approved")]

    gate["metadata"]["resourceVersion"] = "8"
    gate["status"]["phase"] = "Approved"
    service.set_preapproval(
        "captureproxysetup.p2",
        expected_gate_revision="8",
        preapproved=False,
    )
    assert phases[-1] == ("captureproxysetup.p2", "Created")


def test_preapproval_rejects_stale_disabled_and_passed_gates():
    gate = {
        "metadata": {
            "name": "captureproxysetup.p2",
            "resourceVersion": "7",
            "labels": {
                "migrations.opensearch.org/workflow": "migration",
                "migrations.opensearch.org/resource-kind": "CaptureProxy",
                "migrations.opensearch.org/resource-name": "p2",
            },
        },
        "status": {"phase": "Created"},
    }
    config = {"proxies": [{"name": "p2", "skipApproval": True}]}
    service = ApprovalService(
        namespace="ma",
        workflow_name="migration",
        workflow_loader=lambda: {
            "metadata": {"name": "migration"},
            "status": {"phase": "Running", "nodes": {}},
        },
        gate_inventory_loader=lambda: [gate],
        resolved_config_loader=lambda: config,
        phase_setter=lambda *_args: True,
    )

    with pytest.raises(ApprovalStale):
        service.set_preapproval(
            gate["metadata"]["name"],
            expected_gate_revision="6",
            preapproved=True,
        )
    with pytest.raises(ApprovalUnavailable, match="does not use"):
        service.set_preapproval(
            gate["metadata"]["name"],
            expected_gate_revision="7",
            preapproved=True,
        )

    config["proxies"][0]["skipApproval"] = False
    gate["status"]["phase"] = "Approved"
    service._workflow_loader = lambda: {
        "metadata": {"name": "migration"},
        "status": {
            "phase": "Running",
            "nodes": {
                "done": {
                    "id": "done",
                    "displayName": "waitForUserApproval",
                    "templateName": "waitForUserApproval",
                    "phase": "Succeeded",
                    "inputs": {
                        "parameters": [{
                            "name": "resourceName",
                            "value": "captureproxysetup.p2",
                        }],
                    },
                },
            },
        },
    }
    with pytest.raises(ApprovalUnavailable, match="already passed"):
        service.set_preapproval(
            gate["metadata"]["name"],
            expected_gate_revision="7",
            preapproved=False,
        )


def test_reset_executes_only_the_versioned_dependency_safe_plan():
    versions = {
        ("snapshotmigrations", "migration-0"): {
            "uid": "migration-uid",
            "resourceVersion": "10",
        },
        ("trafficreplays", "replay"): {
            "uid": "replay-uid",
            "resourceVersion": "7",
        },
    }
    targets = [
        ("trafficreplays", "replay", "Ready", ["migration-0"]),
        ("snapshotmigrations", "migration-0", "Ready", []),
    ]
    deleted = []
    service = ResetService(
        namespace="ma",
        target_resolver=lambda *_args, **_kwargs: targets,
        exact_resolver=lambda *_args, **_kwargs: targets,
        plan_builder=lambda resolved, _namespace, messages, _delete: {
            "targets": [{
                "plural": plural,
                "type": plural,
                "name": name,
                "path": f"{plural}.{name}",
                "phase": phase,
                "dependsOn": dependencies,
            } for plural, name, phase, dependencies in resolved],
            "messages": messages,
            "warnings": ["Target indexes are retained"],
        },
        version_loader=lambda plural, name: versions[(plural, name)],
        deleter=lambda resolved, namespace, delete_artifacts: (
            deleted.append((resolved, namespace, delete_artifacts)) or True
        ),
    )

    plan = service.plan("reset:snapshotmigrations:migration-0")

    assert [target.name for target in plan.targets] == [
        "replay",
        "migration-0",
    ]
    assert plan.warnings == ("Target indexes are retained",)
    service.execute(plan.token)
    assert deleted == [(targets, "ma", True)]

    with pytest.raises(ResetPlanStale, match="unknown or expired"):
        service.execute(plan.token)
    assert deleted == [(targets, "ma", True)]

    changed_plan = service.plan("reset:snapshotmigrations:migration-0")
    versions[("snapshotmigrations", "migration-0")] = {
        "uid": "migration-uid",
        "resourceVersion": "11",
    }
    with pytest.raises(ResetPlanStale):
        service.execute(changed_plan.token)


def test_reset_plans_multiple_requested_resources_as_one_dependency_set():
    versions = {
        ("capturedtraffics", "p2-topic"): {
            "uid": "topic-uid",
            "resourceVersion": "10",
        },
        ("datasnapshots", "source-snap1"): {
            "uid": "snapshot-uid",
            "resourceVersion": "11",
        },
    }
    targets = [
        ("capturedtraffics", "p2-topic", "Ready", []),
        ("datasnapshots", "source-snap1", "Ready", []),
    ]
    requested_paths = []

    def resolve(paths, *_args, **_kwargs):
        requested_paths.append(paths)
        return targets

    service = ResetService(
        namespace="ma",
        target_resolver=resolve,
        exact_resolver=lambda *_args, **_kwargs: targets,
        plan_builder=lambda resolved, _namespace, messages, _delete: {
            "targets": [{
                "plural": plural,
                "type": plural,
                "name": name,
                "path": f"{plural}.{name}",
                "phase": phase,
                "dependsOn": dependencies,
            } for plural, name, phase, dependencies in resolved],
            "messages": messages,
            "warnings": [],
        },
        version_loader=lambda plural, name: versions[(plural, name)],
        deleter=lambda *_args: True,
    )

    plan = service.plan_many([
        "reset:capturedtraffics:p2-topic",
        "reset:datasnapshots:source-snap1",
    ])

    assert requested_paths == [[
        "capturedtraffic.p2-topic",
        "datasnapshot.source-snap1",
    ]]
    assert [target.name for target in plan.targets] == [
        "p2-topic",
        "source-snap1",
    ]


def test_reset_plan_is_stale_when_a_reviewed_target_was_removed():
    present = True

    def version_loader(_plural, _name):
        if not present:
            raise RuntimeError("resource not found")
        return {"uid": "migration-uid", "resourceVersion": "10"}

    targets = [("snapshotmigrations", "migration-0", "Ready", [])]
    service = ResetService(
        namespace="ma",
        target_resolver=lambda *_args, **_kwargs: targets,
        exact_resolver=lambda *_args, **_kwargs: targets,
        plan_builder=lambda resolved, _namespace, messages, _delete: {
            "targets": [{
                "plural": plural,
                "type": plural,
                "name": name,
                "path": f"{plural}.{name}",
                "phase": phase,
                "dependsOn": dependencies,
            } for plural, name, phase, dependencies in resolved],
            "messages": messages,
            "warnings": [],
        },
        version_loader=version_loader,
        deleter=lambda *_args: True,
    )
    plan = service.plan("reset:snapshotmigrations:migration-0")
    present = False

    with pytest.raises(ResetPlanStale, match="no longer available"):
        service.validate(plan.token)
