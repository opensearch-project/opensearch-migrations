"""Tests for the `workflow k6` command group."""

from unittest.mock import patch
from click.testing import CliRunner

from console_link.workflow.cli import workflow_cli

K6 = "console_link.workflow.commands.k6"

FAKE_RUNS = [
    {"metadata": {"name": "k6-run-a", "labels": {"k6-scenario": "ingest"},
                  "creationTimestamp": "2026-07-27T00:00:00Z"},
     "status": {"phase": "Running", "progress": "1/1"}},
    {"metadata": {"name": "k6-run-b", "labels": {"k6-scenario": "mixed"},
                  "creationTimestamp": "2026-07-27T00:00:00Z"},
     "status": {"phase": "Succeeded", "progress": "2/2"}},
]


def _runner():
    return CliRunner()


class TestK6Help:
    def test_help_renders(self):
        for args in (["k6", "-h"], ["k6", "run", "-h"], ["k6", "list", "-h"],
                     ["k6", "stop", "-h"], ["k6", "logs", "-h"]):
            result = _runner().invoke(workflow_cli, args)
            assert result.exit_code == 0
            assert "Usage:" in result.output


class TestK6Run:
    @patch(f"{K6}.load_k8s_config")
    @patch(f"{K6}.submit_workflow_from_template", return_value="k6-run-xy")
    def test_param_mapping_and_labels(self, mock_submit, _cfg):
        result = _runner().invoke(workflow_cli, [
            "k6", "run", "--scenario", "mixed", "--config", "mixed-steady",
            "--target", "https://p:9200", "--rate", "80", "--registry-enabled",
            "-o", "INGEST_RATE=80", "-o", "SEARCH_RATE=40", "--extra-args", "--no-thresholds",
        ])
        assert result.exit_code == 0
        kw = mock_submit.call_args.kwargs
        assert mock_submit.call_args.args[1] == "k6-load-test"
        assert kw["parameters"] == {
            "scenario": "mixed", "configName": "mixed-steady", "targetUrl": "https://p:9200",
            "rate": "80", "registryEnabled": "true",
            "overrides": "INGEST_RATE=80\nSEARCH_RATE=40", "extraArgs": "--no-thresholds",
        }
        assert kw["labels"] == {"app": "k6-load-test", "k6-scenario": "mixed"}
        assert kw["service_account"] == "argo-workflow-executor"
        assert kw["generate_name"] == "k6-run-"

    @patch(f"{K6}.load_k8s_config")
    @patch(f"{K6}.submit_workflow_from_template", return_value="k6-run-xy")
    def test_default_config_is_scenario_steady(self, mock_submit, _cfg):
        result = _runner().invoke(workflow_cli, ["k6", "run", "--scenario", "search"])
        assert result.exit_code == 0
        assert mock_submit.call_args.kwargs["parameters"]["configName"] == "search-steady"

    @patch(f"{K6}.load_k8s_config")
    @patch(f"{K6}.submit_workflow_from_template", return_value="k6-run-xy")
    def test_tristate_flags(self, mock_submit, _cfg):
        # omitted -> not present (keep preset)
        _runner().invoke(workflow_cli, ["k6", "run"])
        assert "registryEnabled" not in mock_submit.call_args.kwargs["parameters"]
        assert "controlEnabled" not in mock_submit.call_args.kwargs["parameters"]
        # explicit false -> "false"
        _runner().invoke(workflow_cli, ["k6", "run", "--no-registry-enabled"])
        assert mock_submit.call_args.kwargs["parameters"]["registryEnabled"] == "false"

    @patch(f"{K6}.load_k8s_config")
    @patch(f"{K6}.submit_workflow_from_template")
    def test_bad_override_rejected(self, mock_submit, _cfg):
        result = _runner().invoke(workflow_cli, ["k6", "run", "-o", "NOEQUALS"])
        assert result.exit_code == 2
        mock_submit.assert_not_called()

    @patch(f"{K6}.load_k8s_config")
    @patch(f"{K6}.submit_workflow_from_template", side_effect=RuntimeError("boom"))
    def test_submit_failure_exits_nonzero(self, _submit, _cfg):
        result = _runner().invoke(workflow_cli, ["k6", "run"])
        assert result.exit_code == 1
        assert "Error submitting k6 run" in result.output


class TestK6List:
    @patch(f"{K6}.load_k8s_config")
    @patch(f"{K6}.list_workflows", return_value=FAKE_RUNS)
    def test_list_selector_and_rows(self, mock_list, _cfg):
        result = _runner().invoke(workflow_cli, ["k6", "list"])
        assert result.exit_code == 0
        assert mock_list.call_args.kwargs["label_selector"] == "app=k6-load-test"
        assert "k6-run-a" in result.output and "Succeeded" in result.output

    @patch(f"{K6}.load_k8s_config")
    @patch(f"{K6}.list_workflows", return_value=FAKE_RUNS)
    def test_list_scenario_filter(self, mock_list, _cfg):
        _runner().invoke(workflow_cli, ["k6", "list", "--scenario", "mixed"])
        assert mock_list.call_args.kwargs["label_selector"] == "app=k6-load-test,k6-scenario=mixed"

    @patch(f"{K6}.load_k8s_config")
    @patch(f"{K6}.list_workflows", return_value=[])
    def test_list_empty(self, _list, _cfg):
        result = _runner().invoke(workflow_cli, ["k6", "list"])
        assert "No k6 runs found." in result.output


class TestK6Stop:
    @patch(f"{K6}.load_k8s_config")
    @patch(f"{K6}.delete_workflow", return_value=True)
    @patch(f"{K6}.stop_workflow", return_value=True)
    def test_stop_by_name(self, mock_stop, mock_delete, _cfg):
        result = _runner().invoke(workflow_cli, ["k6", "stop", "k6-run-a"])
        assert result.exit_code == 0
        mock_stop.assert_called_once_with("ma", "k6-run-a")
        mock_delete.assert_not_called()

    @patch(f"{K6}.load_k8s_config")
    @patch(f"{K6}.list_workflows", return_value=[FAKE_RUNS[1]])
    @patch(f"{K6}.delete_workflow", return_value=True)
    @patch(f"{K6}.stop_workflow", return_value=True)
    def test_stop_scenario_with_delete(self, mock_stop, mock_delete, mock_list, _cfg):
        result = _runner().invoke(workflow_cli, ["k6", "stop", "--scenario", "mixed", "--delete"])
        assert result.exit_code == 0
        assert mock_list.call_args.kwargs["label_selector"] == "app=k6-load-test,k6-scenario=mixed"
        mock_stop.assert_called_once_with("ma", "k6-run-b")
        mock_delete.assert_called_once_with("ma", "k6-run-b")

    @patch(f"{K6}.load_k8s_config")
    def test_stop_conflicting_selectors(self, _cfg):
        result = _runner().invoke(workflow_cli, ["k6", "stop", "somename", "--all"])
        assert result.exit_code == 2

    @patch(f"{K6}.load_k8s_config")
    @patch(f"{K6}.list_workflows", return_value=[])
    def test_stop_no_match(self, _list, _cfg):
        result = _runner().invoke(workflow_cli, ["k6", "stop", "--all"])
        assert "No matching k6 runs." in result.output


class TestK6Logs:
    @patch(f"{K6}.subprocess.run")
    def test_logs_builds_kubectl_command(self, mock_run):
        _runner().invoke(workflow_cli, ["k6", "logs", "k6-run-a"])
        cmd = mock_run.call_args.args[0]
        assert cmd[:2] == ["kubectl", "logs"]
        assert "-c" in cmd and "main" in cmd
        assert "workflows.argoproj.io/workflow=k6-run-a" in cmd
        assert "-f" not in cmd

    @patch(f"{K6}.subprocess.run")
    def test_logs_follow(self, mock_run):
        _runner().invoke(workflow_cli, ["k6", "logs", "k6-run-a", "-f"])
        assert "-f" in mock_run.call_args.args[0]
