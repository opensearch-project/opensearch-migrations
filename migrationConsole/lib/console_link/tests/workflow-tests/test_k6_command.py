"""Tests for the `workflow k6` command group."""

import pytest
from unittest.mock import patch
from click.testing import CliRunner

from console_link.workflow.cli import workflow_cli
from console_link.workflow.commands.k6 import build_k6_parameters, submit_k6_run

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


class TestBuildAndSubmit:
    """Shared helpers used by both the CLI and the TUI launcher."""

    def test_defaults_and_omission(self):
        # only scenario given -> configName defaults, nothing else present
        assert build_k6_parameters(scenario="search") == {
            "scenario": "search", "configName": "search-steady"}

    def test_full_mapping(self):
        p = build_k6_parameters(
            scenario="mixed", config_name="mixed-steady", target_url="https://p:9200",
            rate="80", duration="10m", vus="20", registry_enabled=True, control_enabled=False,
            overrides_text="INGEST_RATE=80\nSEARCH_RATE=40", extra_args="--no-thresholds")
        assert p == {
            "scenario": "mixed", "configName": "mixed-steady", "targetUrl": "https://p:9200",
            "rate": "80", "duration": "10m", "vus": "20",
            "registryEnabled": "true", "controlEnabled": "false",
            "extraArgs": "--no-thresholds", "overrides": "INGEST_RATE=80\nSEARCH_RATE=40"}

    def test_blank_override_lines_ignored(self):
        p = build_k6_parameters(scenario="ingest", overrides_text="\n  \nFOO=bar\n")
        assert p["overrides"] == "FOO=bar"

    def test_bad_override_raises(self):
        with pytest.raises(ValueError):
            build_k6_parameters(scenario="ingest", overrides_text="NOEQUALS")

    @patch(f"{K6}.submit_workflow_from_template", return_value="k6-run-zz")
    def test_submit_labels_and_sa(self, mock_submit):
        name = submit_k6_run("ma", {"scenario": "mixed", "configName": "mixed-steady"})
        assert name == "k6-run-zz"
        kw = mock_submit.call_args.kwargs
        assert kw["labels"] == {"app": "k6-load-test", "k6-scenario": "mixed"}
        assert kw["service_account"] == "argo-workflow-executor"
        assert kw["generate_name"] == "k6-run-"


class TestIsolation:
    """A k6 run must be a standalone Workflow so it can't fail a migration workflow."""

    @patch("console_link.workflow.commands.argo_utils.client.CustomObjectsApi")
    def test_submitted_workflow_has_no_owner_references(self, mock_api_cls):
        from console_link.workflow.commands.argo_utils import submit_workflow_from_template
        mock_api = mock_api_cls.return_value
        mock_api.create_namespaced_custom_object.return_value = {"metadata": {"name": "k6-run-x"}}
        submit_workflow_from_template(
            "ma", "k6-load-test", parameters={"scenario": "ingest"},
            labels={"app": "k6-load-test"}, service_account="argo-workflow-executor",
            generate_name="k6-run-")
        body = mock_api.create_namespaced_custom_object.call_args.kwargs["body"]
        assert "ownerReferences" not in body["metadata"]           # no owner cascade
        assert body["metadata"]["generateName"] == "k6-run-"       # its own name
        assert body["spec"]["workflowTemplateRef"]["name"] == "k6-load-test"


class TestListActiveRuns:
    @patch(f"{K6}.list_workflows")
    def test_excludes_terminal_phases(self, mock_list):
        from console_link.workflow.commands.k6 import list_active_k6_runs
        mock_list.return_value = [
            {"metadata": {"name": "run-live", "labels": {"k6-scenario": "ingest"},
                          "creationTimestamp": "2026-07-27T00:00:00Z"}, "status": {"phase": "Running"}},
            {"metadata": {"name": "run-done", "labels": {"k6-scenario": "mixed"},
                          "creationTimestamp": "2026-07-27T00:00:00Z"}, "status": {"phase": "Succeeded"}},
        ]
        active = list_active_k6_runs("ma")
        assert [r["name"] for r in active] == ["run-live"]
        assert active[0]["scenario"] == "ingest" and active[0]["phase"] == "Running"


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
