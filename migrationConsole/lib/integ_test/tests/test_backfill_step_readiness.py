from unittest.mock import Mock

import pytest

from integ_test.test_cases import basic_tests


def test_step_view_can_appear_after_monitor_reports_running(monkeypatch):
    case = basic_tests.Test0002SingleDocumentBackfillWithRfsCoordinatorCluster(
        basic_tests.MATestUserArguments(source_version="ES_6.8", target_version="OS_3.1",
                                        target_type="OS", unique_id="unit", reuse_clusters=False))
    status = {"phase": "Running", "updatedAt": "2026-11-27T14:53:02Z",
              "summary": {"percentageCompleted": 0, "shardsMigrated": 0, "shardsTotal": 0}}
    monkeypatch.setattr(case, "_backfill_status", lambda: status)
    case.argo_service = Mock()
    case.argo_service.get_workflow_status.return_value.value = {"phase": "Running"}
    read = Mock(side_effect=["createDeployment (Pending)",
                             "waitIndefinitelyForSnapshotMigration (Pending): "
                             "RFS: Running (0%, shards 0/0) updated 2026-11-27T14:53:02Z"])
    monkeypatch.setattr(basic_tests, "workflow_status_output", read)
    monkeypatch.setattr(basic_tests.time, "sleep", lambda _: None)
    case._check_step_view_while_running(30)
    assert read.call_count == 2

    # Once the step shows progress, an incorrect count should fail on the first read.
    read.side_effect = ["waitIndefinitelyForSnapshotMigration (Running): "
                        "RFS: Running (0%, shards 0/1) updated 2026-11-27T14:53:02Z"]
    with pytest.raises(AssertionError, match="SnapshotMigration reports"):
        case._check_step_view_while_running(30)
