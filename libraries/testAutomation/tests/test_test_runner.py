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


def _make_report(passed=0, failed=0, tests=None, source="ES_7.10", target="OS_2.19"):
    """Create a TestReport with given summary values."""
    if tests is None:
        tests = []
        for i in range(passed):
            tests.append(TestEntry(name=f"pass_{i}", description="", result="passed", duration=0.1))
        for i in range(failed):
            tests.append(TestEntry(name=f"fail_{i}", description="", result="failed", duration=0.1))
    return TestReport(
        summary=TestSummary(passed=passed, failed=failed, source_version=source, target_version=target),
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
