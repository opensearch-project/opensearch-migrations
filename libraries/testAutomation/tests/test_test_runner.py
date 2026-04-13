"""Tests that verify the TestRunner failure detection logic.

The key invariant: if zero tests are executed, the run MUST fail.
"""
import sys
import os
import pytest
from unittest.mock import MagicMock, patch

# Add the testAutomation package to the path so bare `k8s_service` imports resolve
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'testAutomation'))

# Both imports use bare module names (matching how test_runner.py imports k8s_service)
# to ensure `except HelmCommandFailed` catches the same class identity.
from test_runner import TestRunner, TestsFailed, TestReport, TestSummary, TestEntry
from k8s_service import HelmCommandFailed


def _make_runner(combinations, skip_install=True):
    """Create a TestRunner with mocked K8sService."""
    k8s = MagicMock()
    return TestRunner(
        k8s_service=k8s,
        unique_id="test-123",
        test_ids=[],
        ma_chart_path="",
        combinations=combinations,
        skip_install=skip_install,
    )


def _make_report(passed=0, failed=0, tests=None, source="ES_7.10", target="OS_2.19", expected=None):
    """Create a TestReport with given summary values."""
    if tests is None:
        tests = []
        for i in range(passed):
            tests.append(TestEntry(name=f"pass_{i}", description="", result="passed", duration=0.1))
        for i in range(failed):
            tests.append(TestEntry(name=f"fail_{i}", description="", result="failed", duration=0.1))
    return TestReport(
        summary=TestSummary(passed=passed, failed=failed, source_version=source, target_version=target,
                            expected=expected),
        tests=tests,
    )


class TestFailureDetection:
    """Every scenario where zero tests pass must raise TestsFailed."""

    def test_zero_tests_executed_raises(self):
        runner = _make_runner(combinations=[("ES_7.10", "OS_2.19")])
        with patch.object(runner, "run_tests", return_value=_make_report(passed=0, failed=0)):
            with pytest.raises(TestsFailed, match="test failures"):
                runner.run()

    def test_all_tests_failed_raises(self):
        runner = _make_runner(combinations=[("ES_7.10", "OS_2.19")])
        with patch.object(runner, "run_tests", return_value=_make_report(passed=0, failed=3)):
            with pytest.raises(TestsFailed, match="test failures"):
                runner.run()

    def test_helm_failure_raises(self):
        runner = _make_runner(combinations=[("ES_7.10", "OS_2.19")])
        with patch.object(runner, "run_tests", side_effect=HelmCommandFailed("boom")):
            with pytest.raises(TestsFailed):
                runner.run()

    def test_timeout_failure_raises(self):
        runner = _make_runner(combinations=[("ES_7.10", "OS_2.19")])
        with patch.object(runner, "run_tests", side_effect=TimeoutError("timed out")):
            with pytest.raises(TestsFailed):
                runner.run()

    def test_empty_combinations_raises(self):
        runner = _make_runner(combinations=[])
        with pytest.raises(TestsFailed, match="No tests were executed"):
            runner.run()

    def test_passing_tests_succeeds(self):
        runner = _make_runner(combinations=[("ES_7.10", "OS_2.19")])
        with patch.object(runner, "run_tests", return_value=_make_report(passed=5, failed=0)):
            runner.run()  # Should not raise

    def test_mixed_results_raises(self):
        runner = _make_runner(combinations=[("ES_7.10", "OS_2.19")])
        with patch.object(runner, "run_tests", return_value=_make_report(passed=3, failed=1)):
            with pytest.raises(TestsFailed, match="test failures"):
                runner.run()

    def test_expected_mismatch_raises(self):
        runner = _make_runner(combinations=[("ES_7.10", "OS_2.19")])
        # 5 expected but only 3 passed
        with patch.object(runner, "run_tests", return_value=_make_report(passed=3, failed=0, expected=5)):
            with pytest.raises(TestsFailed, match="test failures"):
                runner.run()

    def test_expected_all_passed_succeeds(self):
        runner = _make_runner(combinations=[("ES_7.10", "OS_2.19")])
        with patch.object(runner, "run_tests", return_value=_make_report(passed=5, failed=0, expected=5)):
            runner.run()  # Should not raise

    def test_expected_none_skips_check(self):
        """Old reports without 'expected' field still work."""
        runner = _make_runner(combinations=[("ES_7.10", "OS_2.19")])
        with patch.object(runner, "run_tests", return_value=_make_report(passed=3, failed=0, expected=None)):
            runner.run()  # Should not raise

    def test_zero_expected_tests_succeeds(self):
        """Version pair with no compatible tests (expected=0) should not fail.

        Reproduces the OS_1.3 → OS_2.19 Jenkins failure: conftest.py reports
        expected=0, passed=0, failed=0 — this is a legitimately empty version
        pair, not a test failure.
        """
        runner = _make_runner(combinations=[("OS_1.3", "OS_2.19")])
        with patch.object(runner, "run_tests", return_value=_make_report(
                passed=0, failed=0, expected=0, source="OS_1.3", target="OS_2.19")):
            runner.run()  # Should not raise


from test_runner import get_version_combinations, TargetType, VALID_SOURCE_VERSIONS


class TestVersionCombinations:
    def test_same_version_filtered(self):
        combos = get_version_combinations("all", "all", TargetType.OPENSEARCH)
        assert ("OS_1.3", "OS_1.3") not in combos

    def test_aoss_ignores_target_version(self):
        combos = get_version_combinations("ES_7.10", "OS_2.19", TargetType.AOSS)
        assert combos == [("ES_7.10", "AOSS")]

    def test_single_versions(self):
        combos = get_version_combinations("ES_7.10", "OS_2.19", TargetType.OPENSEARCH)
        assert combos == [("ES_7.10", "OS_2.19")]

    def test_aoss_all_sources(self):
        combos = get_version_combinations("all", "OS_2.19", TargetType.AOSS)
        assert all(t == "AOSS" for _, t in combos)
        assert len(combos) == len(VALID_SOURCE_VERSIONS)
