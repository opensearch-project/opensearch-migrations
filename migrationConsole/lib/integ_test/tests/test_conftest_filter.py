"""Unit tests for conftest._filter_test_cases.

These tests pin down the expectation that test-id matching is anchored on
`Test{id}` and does not collide when test classes share a numeric prefix
(e.g. Test0040 and Test0041) or the requested id is a substring of a
longer one. They guard against the regression where the AOSS pipeline
selected `Test0041CdcFullE2eMountableTransforms` and
`Test0041CdcFullE2eAossTarget` together, then crashed at
`target_version.full_cluster_type` because the former is not AOSS-aware.
"""
from integ_test.conftest import _filter_test_cases  # noqa: E402

# Importing conftest re-runs the test_cases imports, so ALL_TEST_CASES is populated.
from integ_test.conftest import ALL_TEST_CASES  # noqa: E402


def _names(cases):
    return sorted(c.__name__ for c in cases)


def test_empty_filter_excludes_explicit_only_tests():
    cases = _filter_test_cases([])
    selected = _names(cases)
    # Explicit-only tests should be excluded by default
    for name in ("Test0031CdcOnlyLiveTraffic",
                 "Test0034CdcOnlyAossTarget",
                 "Test0041CdcFullE2eAossTarget",
                 "Test0041CdcFullE2eMountableTransforms"):
        assert name not in selected, f"{name} requires explicit selection"


def test_id_prefix_match_anchors_on_class_name():
    # The Test{id} prefix anchors matching at the class name. '004' still
    # matches Test0040*, Test0041*, ... (same set as old behavior), but a
    # stray '004' inside something like a description, file path, or longer
    # numeric does not leak in.
    cases = _filter_test_cases(["004"])
    for c in cases:
        assert c.__name__.startswith("Test004"), c.__name__
    # The narrower '0040' must NOT pick up Test0041* classes.
    cases = _filter_test_cases(["0040"])
    for c in cases:
        assert c.__name__.startswith("Test0040"), c.__name__


def test_specific_id_matches_only_that_id_classes():
    cases = _filter_test_cases(["0041"])
    selected = _names(cases)
    # Both 0041-numbered classes are returned. The pipeline-level fix
    # (target_type-aware constructor) drops the non-AOSS one for AOSS runs.
    assert "Test0041CdcFullE2eAossTarget" in selected
    assert "Test0041CdcFullE2eMountableTransforms" in selected
    # And nothing from neighbouring IDs leaks in.
    assert "Test0040CdcFullE2eSimpleBulk" not in selected


def test_does_not_select_test0040_when_asking_for_0041():
    cases = _filter_test_cases(["0041"])
    assert "Test0040CdcFullE2eSimpleBulk" not in _names(cases)


def test_full_pipeline_id_set_resolves_expected_classes():
    # Full E2E pipeline ID set
    cases = _filter_test_cases(["0031", "0032", "0033", "0040", "0041"])
    selected = _names(cases)
    expected_subset = {
        "Test0031CdcOnlyLiveTraffic",
        "Test0032CdcOnlyGenerateData",
        "Test0033CdcOnlyMixedOperations",
        "Test0040CdcFullE2eSimpleBulk",
        "Test0041CdcFullE2eMountableTransforms",
        "Test0041CdcFullE2eAossTarget",
    }
    assert expected_subset.issubset(set(selected))


def test_aoss_pipeline_id_set_resolves_expected_classes():
    cases = _filter_test_cases(["0034", "0041"])
    selected = _names(cases)
    expected_subset = {
        "Test0034CdcOnlyAossTarget",
        "Test0041CdcFullE2eAossTarget",
        "Test0041CdcFullE2eMountableTransforms",
    }
    assert expected_subset.issubset(set(selected))


def test_all_test_cases_have_test_prefix():
    # Sanity: the filter relies on every collected class starting with 'Test'.
    for case in ALL_TEST_CASES:
        assert case.__name__.startswith("Test"), case.__name__
