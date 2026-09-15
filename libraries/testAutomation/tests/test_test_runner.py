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
from test_runner import (TestRunner, TestsFailed, TestReport, TestSummary, TestEntry,
                         K6_RELEASE_NAME, MA_RELEASE_NAME)
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


def _make_report(passed=0, failed=0, tests=None, source="ES_7.10", target="OS_2.19", expected=None,
                 uncollectable=None):
    """Create a TestReport with given summary values."""
    if tests is None:
        tests = []
        for i in range(passed):
            tests.append(TestEntry(name=f"pass_{i}", description="", result="passed", duration=0.1))
        for i in range(failed):
            tests.append(TestEntry(name=f"fail_{i}", description="", result="failed", duration=0.1))
    return TestReport(
        summary=TestSummary(passed=passed, failed=failed, source_version=source, target_version=target,
                            expected=expected, uncollectable=uncollectable or []),
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

    def test_nonzero_pytest_exit_fails_even_when_zero_tests_are_expected(self):
        runner = _make_runner(combinations=[("ES_2.4", "OS_3.1")])
        report = _make_report(passed=0, failed=0, expected=0)
        report.exit_code = 2
        with patch.object(runner, "run_tests", return_value=report):
            with pytest.raises(TestsFailed, match="test failures"):
                runner.run()

    def test_uncollectable_test_cases_raise_even_when_pytest_exits_clean(self):
        """A test class skipped at collection time must not be mistaken for an empty pair.

        conftest now isolates a broken constructor instead of letting it abort the
        session, so pytest exits 0 and the exit_code check cannot catch this. The
        report is otherwise byte-identical to a legitimately-empty version pair
        (expected=0, passed=0, failed=0) — the case directly above, which must still
        pass. Only 'uncollectable' distinguishes them.
        """
        runner = _make_runner(combinations=[("ES_7.10", "OS_3.1")])
        with patch.object(runner, "run_tests", return_value=_make_report(
                passed=0, failed=0, expected=0,
                uncollectable=["Test0010ExternalSnapshotMigration"])):
            with pytest.raises(TestsFailed, match="test failures"):
                runner.run()

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

    def test_pytest_exit_code_is_preserved_in_report(self):
        runner = _make_runner(combinations=[("ES_8.19", "OS_3.1")])
        runner.k8s_service.exec_background_cmd.return_value = "migration-console-0"
        runner.k8s_service.poll_cmd_completion.return_value = 2
        runner.k8s_service.exec_migration_console_cmd.return_value = str({
            "summary": {
                "passed": 0,
                "failed": 0,
                "source_version": "ES_8.19",
                "target_version": "OS_3.1",
                "expected": 0,
            },
            "tests": [],
        })

        report = runner.run_tests(source_version="ES_8.19", target_version="OS_3.1")

        assert report.exit_code == 2
        assert runner._report_failed(report)

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


class TestLoadTestChartSelection:
    """The k6LoadTest chart follows the selected test IDs — a load-test case cannot submit
    TestRuns without it, and every other run must stay free of the k6 operator."""

    def _runner_with_ids(self, test_ids):
        runner = _make_runner(combinations=[("ES_7.10", "OS_2.19")])
        runner.test_ids = test_ids
        return runner

    def test_load_test_id_requests_the_chart(self):
        assert self._runner_with_ids(["0080"])._load_test_requested() is True

    def test_load_test_id_among_others_requests_the_chart(self):
        assert self._runner_with_ids(["0031", "0080"])._load_test_requested() is True

    def test_ordinary_ids_do_not_request_the_chart(self):
        assert self._runner_with_ids(["0031", "0042"])._load_test_requested() is False

    def test_no_ids_do_not_request_the_chart(self):
        assert self._runner_with_ids([])._load_test_requested() is False

    def test_chart_is_installed_for_a_load_test_id(self):
        runner = self._runner_with_ids(["0080"])
        with patch.object(runner, "run_tests", return_value=_make_report(passed=1, failed=0)), \
                patch.object(runner, "_install_load_test_chart") as mock_install:
            runner.run(skip_delete=True)
            mock_install.assert_called_once()

    def test_chart_is_not_installed_for_an_ordinary_run(self):
        runner = self._runner_with_ids(["0031"])
        with patch.object(runner, "run_tests", return_value=_make_report(passed=1, failed=0)), \
                patch.object(runner, "_install_load_test_chart") as mock_install:
            runner.run(skip_delete=True)
            mock_install.assert_not_called()


class TestLoadTestChartTeardown:
    """The k6 release comes down through the script that installed it, and a failed teardown must
    name the release that actually failed."""

    def _runner(self):
        runner = _make_runner(combinations=[("ES_7.10", "OS_2.19")])
        runner.test_ids = ["0080"]
        runner.k6_install_script = "/repo/deployment/k8s/installK6Chart.sh"
        runner.k6_chart_path = "/repo/deployment/k8s/charts/components/k6LoadTest"
        runner.k8s_service.namespace = "ma"
        runner.k8s_service.kube_context = "kind-ma"
        return runner

    def test_install_and_uninstall_name_the_same_release(self):
        """Both directions go through installK6Chart.sh with one release name, so the two cannot
        drift apart."""
        runner = self._runner()
        with patch("test_runner.subprocess.run") as mock_run:
            mock_run.return_value.returncode = 0
            runner._install_load_test_chart()
            install_cmd = mock_run.call_args.args[0]
            runner._uninstall_load_test_chart()
            uninstall_cmd = mock_run.call_args.args[0]

        assert install_cmd[:2] == [runner.k6_install_script, "install"]
        assert uninstall_cmd[:2] == [runner.k6_install_script, "uninstall"]
        assert install_cmd[install_cmd.index("--release") + 1] == K6_RELEASE_NAME
        assert uninstall_cmd[uninstall_cmd.index("--release") + 1] == K6_RELEASE_NAME

    def test_uninstall_passes_no_install_only_options(self):
        """installK6Chart.sh rejects the image and chart options on an uninstall."""
        runner = self._runner()
        runner.registry_prefix = "1234.dkr.ecr.us-east-1.amazonaws.com/repo"
        runner.k6_runner_image = "repo:migrations_k6_runner_latest"
        with patch("test_runner.subprocess.run") as mock_run:
            mock_run.return_value.returncode = 0
            runner._uninstall_load_test_chart()

        cmd = set(mock_run.call_args.args[0])
        assert not cmd & {"--chart", "--runner-image", "--registry-prefix"}

    def test_k6_uninstall_failure_does_not_blame_the_ma_release(self):
        runner = self._runner()
        with patch.object(runner, "_uninstall_load_test_chart",
                          side_effect=HelmCommandFailed("k6 uninstall failed")):
            with pytest.raises(HelmCommandFailed) as excinfo:
                runner.cleanup_deployment()

        message = str(excinfo.value)
        assert f"'{K6_RELEASE_NAME}'" in message
        assert f"'{MA_RELEASE_NAME}'" not in message

    def test_ma_uninstall_failure_names_the_ma_release(self):
        runner = self._runner()
        runner.k8s_service.helm_uninstall.side_effect = HelmCommandFailed("ma uninstall failed")
        with patch.object(runner, "_uninstall_load_test_chart"):
            with pytest.raises(HelmCommandFailed) as excinfo:
                runner.cleanup_deployment()

        message = str(excinfo.value)
        assert f"'{MA_RELEASE_NAME}'" in message
        assert f"'{K6_RELEASE_NAME}'" not in message


from test_runner import (get_version_combinations, parse_args, TargetType, VALID_SOURCE_VERSIONS,
                         VALID_TARGET_VERSIONS)


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
