from integ_test.test_cases.basic_tests import Test0003ApprovalGateIntegration as ApprovalGateIntegration
from integ_test.test_cases.ma_argo_test_base import MATestUserArguments


def test_approval_gate_integration_uses_canonical_snapshot_migration_slice_name():
    test_case = ApprovalGateIntegration(MATestUserArguments(
        source_version="ES_7.x",
        target_version="OS_2.x",
        unique_id="approval-gate-names",
        reuse_clusters=False,
    ))

    assert test_case.snapshot_migration_name == "source1-target1-testsnapshot-slice-0"
    assert test_case._approval_gate_names() == [
        "begin",
        "captureproxysetup.capture-proxy",
        "evaluatemetadata.source1-target1-testsnapshot-slice-0",
        "migratemetadata.source1-target1-testsnapshot-slice-0",
        "documentbackfill.source1-target1-testsnapshot-slice-0",
    ]
