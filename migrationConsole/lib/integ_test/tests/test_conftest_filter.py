"""Unit tests for conftest._filter_test_cases.

Anchors test-id matching on the `Test{id}` prefix so a stray substring
(e.g. '004' inside a longer numeric, description, or file path) cannot
leak into the selection set. Test IDs are themselves expected to be
unique per class — see Test0041 (CDC full E2E AOSS) vs Test0042 (CDC
full E2E mountable transforms).
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
                 "Test0042CdcFullE2eMountableTransforms"):
        assert name not in selected, f"{name} requires explicit selection"


def test_id_prefix_match_anchors_on_class_name():
    # The Test{id} prefix anchors matching at the class name. '004' still
    # matches Test0040*, Test0041*, Test0042*, ... (same set as old
    # behavior), but a stray '004' inside a description, file path, or
    # longer numeric does not leak in.
    cases = _filter_test_cases(["004"])
    for c in cases:
        assert c.__name__.startswith("Test004"), c.__name__
    # The narrower '0040' must NOT pick up Test0041* / Test0042* classes.
    cases = _filter_test_cases(["0040"])
    for c in cases:
        assert c.__name__.startswith("Test0040"), c.__name__


def test_unique_id_returns_single_class():
    cases = _filter_test_cases(["0042"])
    selected = _names(cases)
    assert "Test0042CdcFullE2eMountableTransforms" in selected
    # Unique ID — no other test class uses the 0042 prefix.
    for name in selected:
        assert name.startswith("Test0042"), name


def test_id_0041_only_returns_aoss_target_class():
    cases = _filter_test_cases(["0041"])
    selected = _names(cases)
    assert selected == ["Test0041CdcFullE2eAossTarget"]


def test_does_not_select_test0040_when_asking_for_0041():
    cases = _filter_test_cases(["0041"])
    assert "Test0040CdcFullE2eSimpleBulk" not in _names(cases)


def test_full_pipeline_id_set_resolves_expected_classes():
    # Full E2E pipeline ID set: 0031, 0032, 0033, 0040, 0042.
    # 0041 is intentionally excluded — it's the AOSS-target variant.
    cases = _filter_test_cases(["0031", "0032", "0033", "0040", "0042"])
    selected = _names(cases)
    expected = {
        "Test0031CdcOnlyLiveTraffic",
        "Test0032CdcOnlyGenerateData",
        "Test0033CdcOnlyMixedOperations",
        "Test0040CdcFullE2eSimpleBulk",
        "Test0042CdcFullE2eMountableTransforms",
    }
    assert expected.issubset(set(selected))
    # The AOSS-target variant must NOT be selected for the full E2E run.
    assert "Test0041CdcFullE2eAossTarget" not in selected


def test_aoss_pipeline_id_set_resolves_expected_classes():
    cases = _filter_test_cases(["0034", "0041"])
    selected = _names(cases)
    expected = {
        "Test0034CdcOnlyAossTarget",
        "Test0041CdcFullE2eAossTarget",
    }
    assert expected.issubset(set(selected))
    # The mountable-transforms variant lives at 0042 — must not collide
    # with the AOSS pipeline's 0041 selection.
    assert "Test0042CdcFullE2eMountableTransforms" not in selected


def test_all_test_cases_have_test_prefix():
    # Sanity: the filter relies on every collected class starting with 'Test'.
    for case in ALL_TEST_CASES:
        assert case.__name__.startswith("Test"), case.__name__


def test_no_duplicate_test_ids():
    """Guard against future test classes accidentally reusing a numeric ID.

    Class names follow the convention `Test<4-digit-id><Description>`.
    Two classes sharing the same 4-digit id collide under any test_ids
    selection that names that id, and pipeline-level filtering cannot
    distinguish them by ID alone — a previous regression had Test0041
    used by both Test0041CdcFullE2eAossTarget and (formerly) the
    mountable-transforms variant, which then crashed AOSS pipelines.
    """
    seen = {}
    for case in ALL_TEST_CASES:
        name = case.__name__
        # Extract the 4 digits after 'Test'
        if not name.startswith("Test") or len(name) < 8:
            continue
        prefix = name[:8]  # 'Test' + 4 digits
        if not prefix[4:].isdigit():
            continue
        seen.setdefault(prefix, []).append(name)
    duplicates = {k: v for k, v in seen.items() if len(v) > 1}
    assert not duplicates, f"Duplicate test IDs detected: {duplicates}"
