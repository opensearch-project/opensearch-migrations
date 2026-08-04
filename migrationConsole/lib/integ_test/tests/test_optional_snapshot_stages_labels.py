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
