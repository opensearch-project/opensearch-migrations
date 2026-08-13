from concurrent.futures import Future
from datetime import datetime, timezone

from console_link.workflow.application.operations import (
    OperationManager,
    OperationWorkResult,
)


class _InlineExecutor:
    def submit(self, function):
        future = Future()
        try:
            future.set_result(function())
        except Exception as error:
            future.set_exception(error)
        return future

    def shutdown(self, wait=False, cancel_futures=False):
        return None


def _clock():
    return datetime(2026, 8, 13, 13, 0, tzinfo=timezone.utc)


def test_operation_manager_tracks_waiting_completion_and_failure():
    manager = OperationManager(
        executor=_InlineExecutor(),
        clock=_clock,
        history_limit=5,
    )

    waiting = manager.start(
        kind="submit",
        label="Submit workflow configuration",
        target_ids=("resource:snapshotmigrations:migration-0",),
        worker=lambda: OperationWorkResult(
            waiting=True,
            message="Waiting for the submitted workflow to be observed",
            result={
                "workflowName": "migration",
                "baselineRevision": "before-submit",
            },
        ),
    )
    failed = manager.start(
        kind="reset",
        label="Reset migration-0",
        target_ids=("resource:snapshotmigrations:migration-0",),
        worker=lambda: (_ for _ in ()).throw(
            RuntimeError("foreground deletion timed out")
        ),
    )

    assert manager.get(waiting.id).status == "waiting"
    assert manager.get(failed.id).status == "failed"
    assert manager.get(failed.id).detail == "foreground deletion timed out"

    completed = manager.reconcile_submit(
        workflow_name="migration",
        snapshot_revision="after-submit",
        workflow_phase="Running",
    )

    assert completed == (waiting.id,)
    assert manager.get(waiting.id).status == "succeeded"
    assert "observed" in manager.get(waiting.id).message.lower()
    assert [event.operation_id for event in manager.events_after(0)][-1] == (
        waiting.id
    )


def test_operation_history_is_bounded_without_dropping_active_work():
    manager = OperationManager(
        executor=_InlineExecutor(),
        clock=_clock,
        history_limit=2,
    )
    for index in range(4):
        manager.start(
            kind="test",
            label=f"Operation {index}",
            target_ids=(),
            worker=lambda: OperationWorkResult(
                waiting=False,
                message="Complete",
            ),
        )

    operations = manager.list()

    assert len(operations) == 2
    assert [item.label for item in operations] == [
        "Operation 3",
        "Operation 2",
    ]


def test_approval_stays_waiting_until_capability_disappears():
    manager = OperationManager(
        executor=_InlineExecutor(),
        clock=_clock,
    )
    approval = manager.start(
        kind="approve",
        label="Approve metadata evaluation",
        target_ids=("resource:snapshotmigrations:migration-0",),
        worker=lambda: OperationWorkResult(
            waiting=True,
            message="Approval accepted; waiting for workflow reconciliation",
            result={
                "approvalTargetId": "approval:node-1",
                "baselineRevision": "before",
            },
        ),
    )

    assert manager.reconcile_approvals(
        active_target_ids=("approval:node-1",),
        snapshot_revision="during",
    ) == ()
    assert manager.get(approval.id).status == "waiting"

    assert manager.reconcile_approvals(
        active_target_ids=(),
        snapshot_revision="after",
    ) == (approval.id,)
    assert manager.get(approval.id).status == "succeeded"
