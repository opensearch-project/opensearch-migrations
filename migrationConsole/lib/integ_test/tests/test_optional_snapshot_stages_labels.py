"""Regression tests for Test0008's per-run snapshot labels.

Test0008 submits three workflows against one set of imported clusters: the baseline
plus a metadata-only and a backfill-only extra run. The snapshot label names the
DataSnapshot CR (source1-<label>) and the SnapshotMigration CR
(source1-target1-<label>-migration-N), and those CRs' repoPathUri /
snapshotRepoPathUri carry changeRestriction "impossible" in
migrationResourceProjections.ts. Since fullMigrationImportedClusters.yaml suffixes
the repo path with the workflow uid, two runs sharing a label resolve to the same CRs
with different repo paths, and the ValidatingAdmissionPolicy rejects the apply
outright ("cannot be changed. Delete and recreate.", exit 64, non-retryable).

That failure only reproduces against a live cluster, so these tests pin the
invariant at the config-construction layer instead.
"""
import re
from unittest.mock import MagicMock

import pytest

from integ_test.test_cases.basic_tests import Test0008OptionalSnapshotStages
from integ_test.test_cases.ma_argo_test_base import MATestUserArguments

# The label the workflow template falls back to when snapshotConfig.snapshotLabel is
# absent. The baseline run uses it, so no extra run may.
DEFAULT_SNAPSHOT_LABEL = "testsnapshot"

# CR names are built by concatenating the label, so it has to survive as a k8s name.
RFC_1123_SUBDOMAIN = re.compile(r"[a-z0-9]([-a-z0-9]*[a-z0-9])?")


def _make_test_case():
    test_case = Test0008OptionalSnapshotStages(user_args=MATestUserArguments(
        source_version="ES_7.10", target_version="OS_3.1", target_type="OS",
        unique_id="unit-test", reuse_clusters=False,
    ))
    test_case.source_cluster = MagicMock()
    test_case.source_cluster.config = {"endpoint": "http://source:9200", "version": "ES_7.10"}
    test_case.target_cluster = MagicMock()
    test_case.target_cluster.config = {"endpoint": "http://target:9200", "version": "OS_3.1"}
    return test_case


def _snapshot_config(params):
    return params["source-configs"][0]["snapshot-and-migration-configs"][0]["snapshotConfig"]


def test_extra_run_parameters_carry_the_requested_snapshot_label():
    test_case = _make_test_case()
    params = test_case._extra_run_parameters({"metadataMigrationConfig": {}}, snapshot_label="meta-abcd")
    assert _snapshot_config(params)["snapshotLabel"] == "meta-abcd"


def test_extra_run_labels_are_distinct_from_each_other_and_from_the_baseline():
    test_case = _make_test_case()
    labels = [test_case.metadata_only_snapshot_label, test_case.backfill_only_snapshot_label]
    assert len(set(labels)) == len(labels), \
        f"the two extra runs must not share a snapshot label: {labels}"
    for label in labels:
        assert label != DEFAULT_SNAPSHOT_LABEL, \
            (f"extra-run label {label!r} collides with the baseline's default label, so its apply "
             f"would try to mutate the baseline's immutable CR repo path")


def test_extra_run_labels_are_valid_kubernetes_name_components():
    test_case = _make_test_case()
    for label in (test_case.metadata_only_snapshot_label, test_case.backfill_only_snapshot_label):
        data_snapshot_name = f"source1-{label}"
        snapshot_migration_name = f"source1-target1-{label}-migration-0"
        for name in (data_snapshot_name, snapshot_migration_name):
            assert RFC_1123_SUBDOMAIN.fullmatch(name), f"{name!r} is not a valid resource name"
            assert len(name) <= 253, f"{name!r} exceeds the 253-character resource name limit"


def test_two_instances_do_not_reuse_labels():
    """Concurrent or retried runs against a shared namespace must not collide either."""
    first, second = _make_test_case(), _make_test_case()
    assert first.metadata_only_snapshot_label != second.metadata_only_snapshot_label
    assert first.backfill_only_snapshot_label != second.backfill_only_snapshot_label


# --- Reading stage phases off the inner workflow -----------------------------------
#
# full-migration-imported-clusters is only a wrapper: its own nodes are
# generate-migration-configs / configureAndSubmitWorkflow / monitorWorkflow /
# evaluateWorkflowResult / deleteMigrationWorkflow. metadataMigrate and
# bulkLoadDocuments live in the inner workflow that configureAndSubmitWorkflow.sh
# submits, so asserting against the wrapper could never match anything.


def test_extra_runs_keep_the_inner_workflow_alive():
    """The wrapper's last step deletes the inner workflow unless we ask it not to.

    Without this the phases are gone before they can be read.
    """
    test_case = _make_test_case()
    params = test_case._extra_run_parameters({"metadataMigrationConfig": {}}, snapshot_label="meta-abcd")
    assert params["keepMigrationWorkflow"] == "true"


def test_stale_inner_workflow_is_rejected():
    """The inner name is fixed and reused, so a leftover must not be asserted against."""
    outer = {"metadata": {"creationTimestamp": "2026-08-04T21:54:02Z"}}
    stale_inner = {"metadata": {"creationTimestamp": "2026-08-04T21:46:16Z"}}
    with pytest.raises(AssertionError, match="leftover from an earlier run"):
        Test0008OptionalSnapshotStages._assert_inner_workflow_is_not_stale(outer, stale_inner, "metadata-only")


def test_inner_workflow_created_after_the_wrapper_is_accepted():
    outer = {"metadata": {"creationTimestamp": "2026-08-04T21:54:02Z"}}
    inner = {"metadata": {"creationTimestamp": "2026-08-04T21:54:03Z"}}
    Test0008OptionalSnapshotStages._assert_inner_workflow_is_not_stale(outer, inner, "metadata-only")


def test_missing_creation_timestamp_is_rejected():
    """Absent timestamps can't confirm ownership, so don't silently trust the workflow."""
    outer = {"metadata": {"creationTimestamp": "2026-08-04T21:54:02Z"}}
    with pytest.raises(AssertionError, match="cannot confirm the inner workflow belongs to this run"):
        Test0008OptionalSnapshotStages._assert_inner_workflow_is_not_stale(outer, {"metadata": {}}, "metadata-only")


def test_wrapper_node_names_would_not_satisfy_the_stage_assertions():
    """Pins why the wrapper is the wrong workflow to read: the stage names aren't in it.

    These are the node names the ES7x run actually reported when the assertion failed.
    """
    wrapper_phases = {
        "configureAndSubmitWorkflow": "Succeeded",
        "monitorWorkflow": "Succeeded",
        "evaluateWorkflowResult": "Succeeded",
        "deleteMigrationWorkflow": "Succeeded",
        "generate-migration-configs": "Succeeded",
        "run-full-migration-with-workflow-cli": "Succeeded",
    }
    for step_name in ("metadataMigrate", "bulkLoadDocuments"):
        with pytest.raises(AssertionError, match=f"no workflow node whose displayName contains '{step_name}'"):
            Test0008OptionalSnapshotStages._assert_node_phase(
                wrapper_phases, step_name, "Succeeded", "metadata-only")
