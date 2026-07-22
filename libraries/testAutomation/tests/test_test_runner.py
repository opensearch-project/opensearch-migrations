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

    def test_skip_delete_does_not_skip_workflow_reset(self):
        runner = _make_runner(combinations=[("ES_7.10", "OS_2.19")])
        with patch.object(runner, "run_tests", return_value=_make_report(passed=1, failed=0)) as run_tests:
            runner.run(skip_delete=True)

        assert run_tests.call_args.kwargs["skip_workflow_reset"] is False
        runner.k8s_service.reset_migration_resources.assert_not_called()

    def test_trace_phase_uses_upgrade_overlay_and_trace_test_ids(self):
        runner = _make_runner(combinations=[("ES_7.10", "OS_2.19")])
        runner.ma_chart_path = "charts/ma"
        runner.trace_test_ids = ["0051", "0053"]
        runner.trace_values_file = "valuesTraceJaeger.yaml"
        runner.trace_backend = "jaeger"
        with patch.object(
            runner,
            "run_tests",
            side_effect=[
                _make_report(passed=2, failed=0, expected=2),
                _make_report(passed=2, failed=0, expected=2),
            ],
        ) as run_tests:
            runner.run()

        runner.k8s_service.reset_migration_resources.assert_called_once()
        runner.k8s_service.helm_upgrade.assert_called_once_with(
            chart_path="charts/ma",
            release_name="ma",
            values_file="valuesTraceJaeger.yaml",
            reuse_values=True,
            wait=True,
            timeout="10m",
        )
        runner.k8s_service.wait_for_daemonset_rollout.assert_called_once_with(
            "otel-trace-collector", timeout_seconds=600)
        runner.k8s_service.wait_for_service.assert_called_once_with("jaeger-query", timeout_seconds=300)

        trace_call_kwargs = run_tests.call_args_list[1].kwargs
        assert trace_call_kwargs["test_ids"] == ["0051", "0053"]
        assert trace_call_kwargs["unique_id"] == "test-123-trace"
        assert trace_call_kwargs["report_suffix"] == "trace"
        assert trace_call_kwargs["skip_workflow_reset"] is False


class TestPytestCommand:
    def test_capture_proxy_service_type_is_passed_to_pytest(self):
        runner = _make_runner(combinations=[("ES_8.19", "OS_3.1")])
        runner.capture_proxy_service_type = "ClusterIP"
        runner.k8s_service.exec_background_cmd.return_value = "migration-console-0"
        runner.k8s_service.poll_cmd_completion.return_value = 0
        runner.k8s_service.exec_migration_console_cmd.return_value = str({
            "summary": {
                "passed": 1,
                "failed": 0,
                "source_version": "ES_8.19",
                "target_version": "OS_3.1",
                "expected": 1,
            },
            "tests": [],
        })

        runner.run_tests(source_version="ES_8.19", target_version="OS_3.1")

        command_list = runner.k8s_service.exec_background_cmd.call_args.kwargs["command_list"]
        assert "--capture_proxy_service_type=ClusterIP" in command_list

    def test_skip_delete_does_not_disable_inter_case_reset(self):
        """--skip-delete preserves the deployment but must NOT pass
        --skip_workflow_reset to pytest. Per-case CRD reset is required for
        every multi-case run; otherwise case N+1's setup precondition trips on
        leftovers from case N (e.g. datasnapshot.source1-testsnapshot)."""
        runner = _make_runner(combinations=[("ES_7.10", "OS_1.3")])
        with patch.object(runner, "run_tests", return_value=_make_report(passed=2, failed=0)) as mock_run:
            runner.run(skip_delete=True)
            mock_run.assert_called_once()
            assert mock_run.call_args.kwargs.get("skip_workflow_reset", False) is False


from test_runner import get_version_combinations, parse_args, TargetType, VALID_SOURCE_VERSIONS, VALID_TARGET_VERSIONS


class TestVersionCombinations:
    def test_same_version_filtered(self):
        combos = get_version_combinations(VALID_SOURCE_VERSIONS, VALID_TARGET_VERSIONS, TargetType.OPENSEARCH)
        assert ("OS_1.3", "OS_1.3") not in combos

    def test_aoss_ignores_target_version(self):
        combos = get_version_combinations(["ES_7.10"], ["OS_2.19"], TargetType.AOSS)
        assert combos == [("ES_7.10", "AOSS")]

    def test_single_versions(self):
        combos = get_version_combinations(["ES_7.10"], ["OS_2.19"], TargetType.OPENSEARCH)
        assert combos == [("ES_7.10", "OS_2.19")]

    def test_aoss_all_sources(self):
        combos = get_version_combinations(VALID_SOURCE_VERSIONS, ["OS_2.19"], TargetType.AOSS)
        assert all(t == "AOSS" for _, t in combos)
        assert len(combos) == len(VALID_SOURCE_VERSIONS)

    def test_multi_source_list(self):
        combos = get_version_combinations(["SOLR_6.6", "SOLR_7.7", "SOLR_9.8"], ["OS_3.1"], TargetType.OPENSEARCH)
        assert combos == [("SOLR_6.6", "OS_3.1"), ("SOLR_7.7", "OS_3.1"), ("SOLR_9.8", "OS_3.1")]

    def test_multi_target_list(self):
        combos = get_version_combinations(["ES_7.10"], ["OS_2.19", "OS_3.1"], TargetType.OPENSEARCH)
        assert combos == [("ES_7.10", "OS_2.19"), ("ES_7.10", "OS_3.1")]


class TestSourceVersionArgParsing:
    def test_all_normalizes_to_lowercase(self):
        args = parse_args(["--source-version", "all", "--kube-context", "kind-ma"])
        assert args.source_version == ["all"]

    def test_all_case_insensitive(self):
        args = parse_args(["--source-version", "ALL", "--kube-context", "kind-ma"])
        assert args.source_version == ["all"]

    def test_all_expands_to_valid_source_versions(self):
        args = parse_args(["--source-version", "all", "--kube-context", "kind-ma"])
        source_versions = VALID_SOURCE_VERSIONS if args.source_version == ["all"] else args.source_version
        assert source_versions == VALID_SOURCE_VERSIONS

    def test_multiple_specific_versions(self):
        args = parse_args(["--source-version", "ES_7.10", "ES_8.19", "--kube-context", "kind-ma"])
        assert args.source_version == ["ES_7.10", "ES_8.19"]

    def test_mixed_all_and_specific_is_rejected(self):
        """Mixing 'all' with specific versions must be caught and rejected at the call site."""
        args = parse_args(["--source-version", "all", "ES_7.10", "--kube-context", "kind-ma"])
        # argparse accepts the list; the rejection happens in main() via sys.exit
        assert "all" in args.source_version
        assert len(args.source_version) > 1
