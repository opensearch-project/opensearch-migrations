import pytest

from console_link.workflow.application.actions import (
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
