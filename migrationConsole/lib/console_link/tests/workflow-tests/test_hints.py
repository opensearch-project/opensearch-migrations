"""Tests for next-step hint logic.

Section 1 — Unit tests for hints.py: verify _hint_for_phase dispatch and every
             public hint function produces the expected text.
Section 2 — CLI integration tests: invoke each workflow command via CliRunner
             and assert the right hint (or no hint) appears in output.
"""

import subprocess
import pytest
from click.testing import CliRunner
from unittest.mock import Mock, patch

from console_link.workflow.cli import workflow_cli
from console_link.workflow.models.config import WorkflowConfig
from console_link.models.command_result import CommandResult


# ── shared constants ──────────────────────────────────────────────────────────

HINT_PREFIX = "Hint:"
SUBMIT_HINT = "`workflow submit`"
MANAGE_HINT = "`workflow manage`"
FIX_HINT = "workflow configure edit"
COMPLETE_HINT = "migration complete"
STILL_RUNNING_HINT = "workflow still running"   # manage-specific wording
IS_RUNNING_HINT = "workflow is running"          # status-specific wording
FAILED_HINT = "workflow failed"
NO_FURTHER_ACTION_HINT = "no further action needed"
FIX_ABOVE_HINT = "fix the issue above"
MONITOR_ERROR_HINT = "could not be monitored"    # submit --wait, monitoring failed


# ── helpers ───────────────────────────────────────────────────────────────────

def _make_gate(name, category="step", status="waiting"):
    from console_link.workflow.commands.approve import GateInfo
    return GateInfo(name=name, category=category, status=status)


def _workflow_response(phase):
    """Minimal Argo workflow dict with a given phase."""
    return {
        "metadata": {"name": "migration-workflow", "namespace": "ma"},
        "status": {
            "phase": phase,
            "startedAt": "2024-01-01T10:00:00Z",
            "finishedAt": "2024-01-01T11:00:00Z" if phase != "Running" else None,
            "nodes": {},
        },
    }


def _mock_requests_get(phase):
    """Return a mock requests.Response for the Argo API returning the given phase."""
    mock_resp = Mock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = _workflow_response(phase)
    return mock_resp


# ══════════════════════════════════════════════════════════════════════════════
# Section 1 — hints.py unit tests
# ══════════════════════════════════════════════════════════════════════════════

class TestHintForPhase:
    """_hint_for_phase dispatch logic."""

    def _call(self, phase, capsys):
        from console_link.workflow.commands.hints import _hint_for_phase
        _hint_for_phase(phase, running_hint="R", succeeded_hint="S", failed_hint="F")
        return capsys.readouterr().out

    @pytest.mark.parametrize("phase", ["Running", "Pending", "running", "pending"])
    def test_running_phases_emit_running_hint(self, phase, capsys):
        assert "R" in self._call(phase, capsys)

    @pytest.mark.parametrize("phase", ["Succeeded", "succeeded"])
    def test_succeeded_phase_emits_succeeded_hint(self, phase, capsys):
        assert "S" in self._call(phase, capsys)

    @pytest.mark.parametrize("phase", ["Failed", "Error", "Stopped", "failed", "error"])
    def test_failed_phases_emit_failed_hint(self, phase, capsys):
        assert "F" in self._call(phase, capsys)

    @pytest.mark.parametrize("phase", ["", None, "Unknown", "SomeFuturePhase"])
    def test_unknown_phases_emit_nothing(self, phase, capsys):
        out = self._call(phase, capsys)
        assert out == ""


class TestPublicHintFunctions:
    """Each public function in hints.py produces the expected text."""

    def test_hint_after_configure_edit(self, capsys):
        from console_link.workflow.commands.hints import hint_after_configure_edit
        hint_after_configure_edit()
        out = capsys.readouterr().out
        assert HINT_PREFIX in out
        assert SUBMIT_HINT in out

    def test_hint_configure_fix(self, capsys):
        from console_link.workflow.commands.hints import hint_configure_fix
        hint_configure_fix()
        out = capsys.readouterr().out
        assert HINT_PREFIX in out
        assert FIX_HINT in out

    def test_hint_after_submit(self, capsys):
        from console_link.workflow.commands.hints import hint_after_submit
        hint_after_submit()
        out = capsys.readouterr().out
        assert HINT_PREFIX in out
        assert MANAGE_HINT in out

    @pytest.mark.parametrize("phase,expected", [
        ("Succeeded", COMPLETE_HINT),
        ("Running", MANAGE_HINT),
        ("Failed", FIX_HINT),
        ("", None),
    ])
    def test_hint_after_submit_wait(self, phase, expected, capsys):
        from console_link.workflow.commands.hints import hint_after_submit_wait
        hint_after_submit_wait(phase)
        out = capsys.readouterr().out
        if expected:
            assert HINT_PREFIX in out
            assert expected in out
        else:
            assert HINT_PREFIX not in out

    def test_hint_after_submit_wait_error(self, capsys):
        from console_link.workflow.commands.hints import hint_after_submit_wait_error
        hint_after_submit_wait_error()
        out = capsys.readouterr().out
        assert HINT_PREFIX in out
        assert MONITOR_ERROR_HINT in out
        # monitoring failure is not a config problem — must not nudge a reconfigure/resubmit
        assert FIX_HINT not in out

    def test_hint_on_submit_error(self, capsys):
        from console_link.workflow.commands.hints import hint_on_submit_error
        hint_on_submit_error()
        out = capsys.readouterr().out
        assert HINT_PREFIX in out
        assert FIX_ABOVE_HINT in out

    def test_hint_after_approve_step_mentions_list(self, capsys):
        from console_link.workflow.commands.hints import hint_after_approve_step
        hint_after_approve_step()
        out = capsys.readouterr().out
        assert HINT_PREFIX in out
        assert MANAGE_HINT in out
        assert "--list" in out

    def test_hint_after_approve_change_no_list(self, capsys):
        from console_link.workflow.commands.hints import hint_after_approve_change
        hint_after_approve_change()
        out = capsys.readouterr().out
        assert HINT_PREFIX in out
        assert MANAGE_HINT in out
        assert "--list" not in out

    def test_hint_after_approve_retry(self, capsys):
        from console_link.workflow.commands.hints import hint_after_approve_retry
        hint_after_approve_retry()
        out = capsys.readouterr().out
        assert HINT_PREFIX in out
        assert MANAGE_HINT in out

    @pytest.mark.parametrize("phase,expected", [
        ("Running", IS_RUNNING_HINT),
        ("Pending", IS_RUNNING_HINT),
        ("Succeeded", COMPLETE_HINT),
        ("Failed", FAILED_HINT),
        ("Error", FAILED_HINT),
        ("", None),
    ])
    def test_hint_after_status(self, phase, expected, capsys):
        from console_link.workflow.commands.hints import hint_after_status
        hint_after_status(phase)
        out = capsys.readouterr().out
        if expected:
            assert HINT_PREFIX in out
            assert expected in out
        else:
            assert HINT_PREFIX not in out

    @pytest.mark.parametrize("phase,expected", [
        ("Running", STILL_RUNNING_HINT),
        ("Succeeded", COMPLETE_HINT),
        ("Failed", FAILED_HINT),
        ("", None),
    ])
    def test_hint_after_manage(self, phase, expected, capsys):
        from console_link.workflow.commands.hints import hint_after_manage
        hint_after_manage(phase)
        out = capsys.readouterr().out
        if expected:
            assert HINT_PREFIX in out
            assert expected in out
        else:
            assert HINT_PREFIX not in out

    def test_hint_after_show(self, capsys):
        from console_link.workflow.commands.hints import hint_after_show
        hint_after_show()
        out = capsys.readouterr().out
        assert HINT_PREFIX in out
        assert NO_FURTHER_ACTION_HINT in out


# ══════════════════════════════════════════════════════════════════════════════
# Section 2 — CLI integration tests
# ══════════════════════════════════════════════════════════════════════════════

class TestConfigureEditHints:
    """workflow configure edit — all save/discard/error paths."""

    # ── stdin path ────────────────────────────────────────────────────────────

    @patch("console_link.workflow.commands.configure.process_secrets")
    @patch("console_link.workflow.commands.configure.validate_and_find_secrets")
    @patch("console_link.workflow.commands.configure.get_credentials_secret_store")
    @patch("console_link.workflow.commands.configure.get_workflow_config_store")
    def test_stdin_valid_config_shows_submit_hint(
        self, mock_cfg_store, mock_sec_store, mock_validate, mock_process
    ):
        mock_cfg_store.return_value.load_config.return_value = None
        mock_cfg_store.return_value.save_config.return_value = "Configuration saved"
        mock_validate.return_value = {"valid": True, "validSecrets": []}

        result = CliRunner().invoke(
            workflow_cli, ["configure", "edit", "--stdin"],
            input="sourceClusters: {}\n",
        )

        assert result.exit_code == 0
        assert HINT_PREFIX in result.output
        assert SUBMIT_HINT in result.output

    @patch("console_link.workflow.commands.configure.validate_and_find_secrets")
    @patch("console_link.workflow.commands.configure.get_credentials_secret_store")
    @patch("console_link.workflow.commands.configure.get_workflow_config_store")
    def test_stdin_invalid_config_shows_fix_hint_not_submit_hint(
        self, mock_cfg_store, mock_sec_store, mock_validate
    ):
        mock_cfg_store.return_value.load_config.return_value = None
        mock_cfg_store.return_value.save_config.return_value = "Configuration saved"
        mock_validate.return_value = {"valid": False, "errors": "missing required field"}

        result = CliRunner().invoke(
            workflow_cli, ["configure", "edit", "--stdin"],
            input="bad: yaml\n",
        )

        assert result.exit_code != 0
        assert HINT_PREFIX in result.output
        assert FIX_HINT in result.output
        assert SUBMIT_HINT not in result.output

    # ── interactive editor path ────────────────────────────────────────────────

    @patch("console_link.workflow.commands.configure.process_secrets")
    @patch("console_link.workflow.commands.configure.validate_and_find_secrets")
    @patch("console_link.workflow.commands.configure.get_credentials_secret_store")
    @patch("console_link.workflow.commands.configure.get_workflow_config_store")
    @patch("console_link.workflow.commands.configure._launch_editor_for_config")
    def test_editor_valid_save_shows_submit_hint(
        self, mock_editor, mock_cfg_store, mock_sec_store, mock_validate, mock_process
    ):
        mock_editor.return_value = CommandResult(success=True, value="sourceClusters: {}")
        mock_cfg_store.return_value.load_config.return_value = None
        mock_cfg_store.return_value.save_config.return_value = "Configuration saved"
        mock_validate.return_value = {"valid": True, "validSecrets": []}

        result = CliRunner().invoke(workflow_cli, ["configure", "edit"])

        assert result.exit_code == 0
        assert HINT_PREFIX in result.output
        assert SUBMIT_HINT in result.output

    @patch("console_link.workflow.commands.configure.validate_and_find_secrets")
    @patch("console_link.workflow.commands.configure.get_credentials_secret_store")
    @patch("console_link.workflow.commands.configure.get_workflow_config_store")
    @patch("console_link.workflow.commands.configure._launch_editor_for_config")
    def test_editor_save_anyway_shows_fix_hint(
        self, mock_editor, mock_cfg_store, mock_sec_store, mock_validate
    ):
        mock_editor.return_value = CommandResult(success=True, value="bad: yaml")
        mock_cfg_store.return_value.load_config.return_value = None
        mock_cfg_store.return_value.save_config.return_value = "Configuration saved"
        mock_validate.return_value = {"valid": False, "errors": "missing required field"}

        result = CliRunner().invoke(workflow_cli, ["configure", "edit"], input="s\n")

        assert result.exit_code == 0
        assert HINT_PREFIX in result.output
        assert FIX_HINT in result.output
        assert SUBMIT_HINT not in result.output

    @patch("console_link.workflow.commands.configure.validate_and_find_secrets")
    @patch("console_link.workflow.commands.configure.get_credentials_secret_store")
    @patch("console_link.workflow.commands.configure.get_workflow_config_store")
    @patch("console_link.workflow.commands.configure._launch_editor_for_config")
    def test_editor_discard_shows_fix_hint(
        self, mock_editor, mock_cfg_store, mock_sec_store, mock_validate
    ):
        mock_editor.return_value = CommandResult(success=True, value="bad: yaml")
        mock_cfg_store.return_value.load_config.return_value = None
        mock_cfg_store.return_value.save_config.return_value = "Configuration saved"
        mock_validate.return_value = {"valid": False, "errors": "missing required field"}

        result = CliRunner().invoke(workflow_cli, ["configure", "edit"], input="d\n")

        assert result.exit_code == 0
        assert HINT_PREFIX in result.output
        assert FIX_HINT in result.output
        assert SUBMIT_HINT not in result.output

    @patch("console_link.workflow.commands.configure.process_secrets")
    @patch("console_link.workflow.commands.configure.validate_and_find_secrets")
    @patch("console_link.workflow.commands.configure.get_credentials_secret_store")
    @patch("console_link.workflow.commands.configure.get_workflow_config_store")
    @patch("console_link.workflow.commands.configure._launch_editor_for_config")
    def test_editor_edit_again_then_valid_shows_only_submit_hint(
        self, mock_editor, mock_cfg_store, mock_sec_store, mock_validate, mock_process
    ):
        # First call: invalid → user picks 'e'. Second call: valid.
        mock_editor.side_effect = [
            CommandResult(success=True, value="bad: yaml"),
            CommandResult(success=True, value="sourceClusters: {}"),
        ]
        mock_cfg_store.return_value.load_config.return_value = None
        mock_cfg_store.return_value.save_config.return_value = "Configuration saved"
        mock_validate.side_effect = [
            {"valid": False, "errors": "missing required field"},
            {"valid": True, "validSecrets": []},
        ]

        result = CliRunner().invoke(workflow_cli, ["configure", "edit"], input="e\n")

        assert result.exit_code == 0
        assert SUBMIT_HINT in result.output
        assert FIX_HINT not in result.output


class TestSubmitHints:
    """workflow submit — success, --wait, and error paths."""

    def _base_patches(self):
        """Return list of patch targets always needed for submit."""
        return [
            patch("console_link.workflow.commands.submit.verify_configured_secrets_exist"),
            patch("console_link.workflow.commands.submit.get_credentials_secret_store_for_namespace"),
            patch("console_link.workflow.commands.submit.workflow_exists", return_value=False),
            patch("console_link.workflow.commands.submit.load_k8s_config"),
        ]

    def _setup_store(self, mock_store_class):
        mock_store = Mock()
        mock_store_class.return_value = mock_store
        # data must be non-empty: submit.py guards on `not config.data`
        mock_store.load_config.return_value = WorkflowConfig(
            data={"sourceClusters": {}},
            raw_yaml="sourceClusters: {}\n",
        )
        return mock_store

    @patch("console_link.workflow.commands.submit.WorkflowConfigStore")
    @patch("console_link.workflow.commands.submit.ScriptRunner")
    @patch("console_link.workflow.commands.submit.load_k8s_config")
    @patch("console_link.workflow.commands.submit.workflow_exists", return_value=False)
    @patch("console_link.workflow.commands.submit.get_credentials_secret_store_for_namespace")
    @patch("console_link.workflow.commands.submit.verify_configured_secrets_exist")
    def test_success_no_wait_shows_manage_hint(
        self, _vfy, _ss, _exists, _k8s, mock_runner_class, mock_store_class
    ):
        mock_runner_class.return_value.submit_workflow.return_value = {
            "workflow_name": "migration-workflow", "warnings": []
        }
        self._setup_store(mock_store_class)

        result = CliRunner().invoke(workflow_cli, ["submit"])

        assert result.exit_code == 0
        assert HINT_PREFIX in result.output
        assert MANAGE_HINT in result.output

    @patch("console_link.workflow.commands.submit.WorkflowConfigStore")
    @patch("console_link.workflow.commands.submit.WorkflowService")
    @patch("console_link.workflow.commands.submit.ScriptRunner")
    @patch("console_link.workflow.commands.submit.load_k8s_config")
    @patch("console_link.workflow.commands.submit.workflow_exists", return_value=False)
    @patch("console_link.workflow.commands.submit.get_credentials_secret_store_for_namespace")
    @patch("console_link.workflow.commands.submit.verify_configured_secrets_exist")
    def test_wait_succeeded_shows_complete_hint(
        self, _vfy, _ss, _exists, _k8s, mock_runner_class, mock_svc_class, mock_store_class
    ):
        mock_runner_class.return_value.submit_workflow.return_value = {
            "workflow_name": "migration-workflow", "warnings": []
        }
        mock_svc_class.return_value.wait_for_workflow_completion.return_value = ("Succeeded", None)
        self._setup_store(mock_store_class)

        result = CliRunner().invoke(workflow_cli, ["submit", "--wait", "--timeout", "60"])

        assert result.exit_code == 0
        assert HINT_PREFIX in result.output
        assert COMPLETE_HINT in result.output

    @patch("console_link.workflow.commands.submit.WorkflowConfigStore")
    @patch("console_link.workflow.commands.submit.WorkflowService")
    @patch("console_link.workflow.commands.submit.ScriptRunner")
    @patch("console_link.workflow.commands.submit.load_k8s_config")
    @patch("console_link.workflow.commands.submit.workflow_exists", return_value=False)
    @patch("console_link.workflow.commands.submit.get_credentials_secret_store_for_namespace")
    @patch("console_link.workflow.commands.submit.verify_configured_secrets_exist")
    def test_wait_failed_shows_fix_hint(
        self, _vfy, _ss, _exists, _k8s, mock_runner_class, mock_svc_class, mock_store_class
    ):
        mock_runner_class.return_value.submit_workflow.return_value = {
            "workflow_name": "migration-workflow", "warnings": []
        }
        mock_svc_class.return_value.wait_for_workflow_completion.return_value = ("Failed", None)
        self._setup_store(mock_store_class)

        result = CliRunner().invoke(workflow_cli, ["submit", "--wait", "--timeout", "60"])

        assert result.exit_code == 0
        assert HINT_PREFIX in result.output
        assert FIX_HINT in result.output

    @patch("console_link.workflow.commands.submit.WorkflowConfigStore")
    @patch("console_link.workflow.commands.submit.WorkflowService")
    @patch("console_link.workflow.commands.submit.ScriptRunner")
    @patch("console_link.workflow.commands.submit.load_k8s_config")
    @patch("console_link.workflow.commands.submit.workflow_exists", return_value=False)
    @patch("console_link.workflow.commands.submit.get_credentials_secret_store_for_namespace")
    @patch("console_link.workflow.commands.submit.verify_configured_secrets_exist")
    def test_wait_timeout_shows_manage_hint(
        self, _vfy, _ss, _exists, _k8s, mock_runner_class, mock_svc_class, mock_store_class
    ):
        mock_runner_class.return_value.submit_workflow.return_value = {
            "workflow_name": "migration-workflow", "warnings": []
        }
        mock_svc_class.return_value.wait_for_workflow_completion.side_effect = TimeoutError("timed out")
        self._setup_store(mock_store_class)

        result = CliRunner().invoke(workflow_cli, ["submit", "--wait", "--timeout", "1"])

        assert result.exit_code == 0
        assert HINT_PREFIX in result.output
        assert MANAGE_HINT in result.output

    @patch("console_link.workflow.commands.submit.WorkflowConfigStore")
    @patch("console_link.workflow.commands.submit.WorkflowService")
    @patch("console_link.workflow.commands.submit.ScriptRunner")
    @patch("console_link.workflow.commands.submit.load_k8s_config")
    @patch("console_link.workflow.commands.submit.workflow_exists", return_value=False)
    @patch("console_link.workflow.commands.submit.get_credentials_secret_store_for_namespace")
    @patch("console_link.workflow.commands.submit.verify_configured_secrets_exist")
    def test_wait_monitor_error_shows_monitor_hint(
        self, _vfy, _ss, _exists, _k8s, mock_runner_class, mock_svc_class, mock_store_class
    ):
        # Submission succeeds, but monitoring the workflow raises a non-timeout error.
        # The user should be told the workflow is submitted and to check it manually —
        # not nudged to fix the config (the submit itself worked).
        mock_runner_class.return_value.submit_workflow.return_value = {
            "workflow_name": "migration-workflow", "warnings": []
        }
        mock_svc_class.return_value.wait_for_workflow_completion.side_effect = Exception("argo unreachable")
        self._setup_store(mock_store_class)

        result = CliRunner().invoke(workflow_cli, ["submit", "--wait", "--timeout", "60"])

        assert result.exit_code == 0
        assert HINT_PREFIX in result.output
        assert MONITOR_ERROR_HINT in result.output
        assert FIX_HINT not in result.output

    @patch("console_link.workflow.commands.submit.WorkflowConfigStore")
    @patch("console_link.workflow.commands.submit.ScriptRunner")
    @patch("console_link.workflow.commands.submit.load_k8s_config")
    @patch("console_link.workflow.commands.submit.workflow_exists", return_value=False)
    @patch("console_link.workflow.commands.submit.get_credentials_secret_store_for_namespace")
    @patch("console_link.workflow.commands.submit.verify_configured_secrets_exist")
    def test_script_error_shows_fix_above_hint(
        self, _vfy, _ss, _exists, _k8s, mock_runner_class, mock_store_class
    ):
        mock_runner_class.return_value.submit_workflow.side_effect = (
            subprocess.CalledProcessError(1, ["cmd"], stderr="config validation failed")
        )
        self._setup_store(mock_store_class)

        result = CliRunner().invoke(workflow_cli, ["submit"])

        assert result.exit_code != 0
        assert HINT_PREFIX in result.output
        assert FIX_ABOVE_HINT in result.output

    @patch("console_link.workflow.commands.submit.WorkflowConfigStore")
    @patch("console_link.workflow.commands.submit.ScriptRunner")
    @patch("console_link.workflow.commands.submit.load_k8s_config")
    @patch("console_link.workflow.commands.submit.workflow_exists", return_value=False)
    @patch("console_link.workflow.commands.submit.get_credentials_secret_store_for_namespace")
    @patch("console_link.workflow.commands.submit.verify_configured_secrets_exist")
    def test_general_exception_shows_fix_above_hint(
        self, _vfy, _ss, _exists, _k8s, mock_runner_class, mock_store_class
    ):
        mock_runner_class.return_value.submit_workflow.side_effect = Exception("unexpected failure")
        self._setup_store(mock_store_class)

        result = CliRunner().invoke(workflow_cli, ["submit"])

        assert result.exit_code != 0
        assert HINT_PREFIX in result.output
        assert FIX_ABOVE_HINT in result.output

    @patch("console_link.workflow.commands.submit.WorkflowConfigStore")
    @patch("console_link.workflow.commands.submit.ScriptRunner")
    @patch("console_link.workflow.commands.submit.load_k8s_config")
    @patch("console_link.workflow.commands.submit.workflow_exists", return_value=False)
    @patch("console_link.workflow.commands.submit.get_credentials_secret_store_for_namespace")
    @patch("console_link.workflow.commands.submit.verify_configured_secrets_exist")
    def test_file_not_found_does_not_show_fix_hint(
        self, _vfy, _ss, _exists, _k8s, mock_runner_class, mock_store_class
    ):
        mock_runner_class.return_value.submit_workflow.side_effect = FileNotFoundError("no such script")
        self._setup_store(mock_store_class)

        result = CliRunner().invoke(workflow_cli, ["submit"])

        assert result.exit_code != 0
        assert HINT_PREFIX not in result.output


class TestApproveHints:
    """workflow approve step/change/retry — hint after approval, no hint for --list."""

    @patch("console_link.workflow.commands.approve.approve_gate", return_value=True)
    @patch("console_link.workflow.commands.approve._gather_gates")
    @patch("console_link.workflow.commands.approve.load_k8s_config")
    def test_approve_step_shows_manage_and_list_hint(self, _k8s, mock_gather, _approve):
        mock_gather.return_value = [_make_gate("captureproxysetup.proxy-1")]

        result = CliRunner().invoke(
            workflow_cli, ["approve", "step", "captureproxysetup.proxy-1"]
        )

        assert result.exit_code == 0
        assert HINT_PREFIX in result.output
        assert MANAGE_HINT in result.output
        assert "--list" in result.output

    @patch("console_link.workflow.commands.approve.approve_gate", return_value=True)
    @patch("console_link.workflow.commands.approve._gather_gates")
    @patch("console_link.workflow.commands.approve.load_k8s_config")
    def test_approve_step_all_shows_hint(self, _k8s, mock_gather, _approve):
        mock_gather.return_value = [
            _make_gate("captureproxysetup.proxy-1"),
            _make_gate("documentbackfill.backfill-1"),
        ]

        result = CliRunner().invoke(workflow_cli, ["approve", "step", "--all"])

        assert result.exit_code == 0
        assert HINT_PREFIX in result.output
        assert MANAGE_HINT in result.output

    @patch("console_link.workflow.commands.approve._gather_gates")
    @patch("console_link.workflow.commands.approve.load_k8s_config")
    def test_approve_step_list_does_not_show_hint(self, _k8s, mock_gather):
        mock_gather.return_value = [_make_gate("captureproxysetup.proxy-1")]

        result = CliRunner().invoke(workflow_cli, ["approve", "step", "--list"])

        assert result.exit_code == 0
        assert HINT_PREFIX not in result.output

    @patch("console_link.workflow.commands.approve.approve_gate", return_value=True)
    @patch("console_link.workflow.commands.approve._gather_gates")
    @patch("console_link.workflow.commands.approve.load_k8s_config")
    def test_approve_change_shows_manage_hint_without_list(self, _k8s, mock_gather, _approve):
        mock_gather.return_value = [_make_gate("cp.proxy-1.vapretry", category="change")]

        result = CliRunner().invoke(workflow_cli, ["approve", "change", "--all"])

        assert result.exit_code == 0
        assert HINT_PREFIX in result.output
        assert MANAGE_HINT in result.output
        assert "--list" not in result.output

    @patch("console_link.workflow.commands.approve.approve_gate", return_value=True)
    @patch("console_link.workflow.commands.approve._gather_gates")
    @patch("console_link.workflow.commands.approve.load_k8s_config")
    def test_approve_retry_shows_manage_hint(self, _k8s, mock_gather, _approve):
        mock_gather.return_value = [_make_gate("cp.proxy-1.vapretry", category="retry")]

        result = CliRunner().invoke(workflow_cli, ["approve", "retry", "--all"])

        assert result.exit_code == 0
        assert HINT_PREFIX in result.output
        assert MANAGE_HINT in result.output

    @patch("console_link.workflow.commands.approve._gather_gates")
    @patch("console_link.workflow.commands.approve.load_k8s_config")
    def test_no_matching_gates_does_not_show_hint(self, _k8s, mock_gather):
        mock_gather.return_value = []

        result = CliRunner().invoke(workflow_cli, ["approve", "step", "no-such-gate"])

        assert result.exit_code != 0
        assert HINT_PREFIX not in result.output


class TestStatusHints:
    """workflow status — phase-aware hint for single workflow; absent for --all-workflows."""

    @pytest.mark.parametrize("phase,expected_fragment", [
        ("Running", IS_RUNNING_HINT),
        ("Pending", IS_RUNNING_HINT),
        ("Succeeded", COMPLETE_HINT),
        ("Failed", FAILED_HINT),
        ("Error", FAILED_HINT),
    ])
    @patch("console_link.workflow.commands.status.requests.get")
    @patch("console_link.workflow.commands.status.WorkflowService")
    def test_single_workflow_phase_hint(
        self, mock_svc, mock_get, phase, expected_fragment
    ):
        mock_get.return_value = _mock_requests_get(phase)

        result = CliRunner().invoke(
            workflow_cli,
            ["status", "--step-view", "--workflow-name", "migration-workflow"],
        )

        assert result.exit_code == 0
        assert HINT_PREFIX in result.output
        assert expected_fragment in result.output

    @patch("console_link.workflow.commands.status.requests.get")
    @patch("console_link.workflow.commands.status.WorkflowService")
    def test_all_workflows_does_not_show_hint(self, mock_svc, mock_get):
        mock_svc.return_value.list_workflows.return_value = {
            "success": True, "workflows": ["wf-1"], "count": 1
        }
        mock_get.return_value = _mock_requests_get("Running")

        result = CliRunner().invoke(workflow_cli, ["status", "--step-view", "--all-workflows"])

        assert result.exit_code == 0
        assert HINT_PREFIX not in result.output

    @patch("console_link.workflow.commands.status.requests.get")
    @patch("console_link.workflow.commands.status.WorkflowService")
    def test_workflow_not_found_does_not_show_hint(self, mock_svc, mock_get):
        mock_resp = Mock()
        mock_resp.status_code = 404
        mock_resp.json.return_value = {}
        mock_get.return_value = mock_resp

        result = CliRunner().invoke(
            workflow_cli,
            ["status", "--step-view", "--workflow-name", "migration-workflow"],
        )

        assert result.exit_code != 0
        assert HINT_PREFIX not in result.output


class TestManageHints:
    """workflow manage — phase-aware hint after TUI exits."""

    def _invoke_manage(self, workflow_data):
        with (
            patch("console_link.workflow.commands.manage._configure_file_logging"),
            patch("console_link.workflow.commands.manage._initialize_k8s_client", return_value=Mock()),
            patch("console_link.workflow.commands.manage.make_argo_service", return_value=Mock()),
            patch("console_link.workflow.commands.manage.make_k8s_pod_scraper", return_value=Mock()),
            patch("console_link.workflow.commands.manage.WaiterInterface"),
            patch("console_link.workflow.commands.manage.WorkflowTreeApp") as mock_app_cls,
            patch("console_link.workflow.commands.manage.get_workflow", return_value=workflow_data),
        ):
            mock_app_cls.return_value.run.return_value = None
            return CliRunner().invoke(workflow_cli, ["manage"])

    @pytest.mark.parametrize("phase,expected_fragment", [
        ("Running", STILL_RUNNING_HINT),
        ("Succeeded", COMPLETE_HINT),
        ("Failed", FAILED_HINT),
        ("Error", FAILED_HINT),
    ])
    def test_phase_hint(self, phase, expected_fragment):
        result = self._invoke_manage(_workflow_response(phase))
        assert result.exit_code == 0
        assert HINT_PREFIX in result.output
        assert expected_fragment in result.output

    def test_workflow_not_found_no_hint(self):
        result = self._invoke_manage(None)
        assert result.exit_code == 0
        assert HINT_PREFIX not in result.output

    def test_get_workflow_exception_no_hint(self):
        with (
            patch("console_link.workflow.commands.manage._configure_file_logging"),
            patch("console_link.workflow.commands.manage._initialize_k8s_client", return_value=Mock()),
            patch("console_link.workflow.commands.manage.make_argo_service", return_value=Mock()),
            patch("console_link.workflow.commands.manage.make_k8s_pod_scraper", return_value=Mock()),
            patch("console_link.workflow.commands.manage.WaiterInterface"),
            patch("console_link.workflow.commands.manage.WorkflowTreeApp") as mock_app_cls,
            patch("console_link.workflow.commands.manage.get_workflow",
                  side_effect=Exception("k8s unavailable")),
        ):
            mock_app_cls.return_value.run.return_value = None
            result = CliRunner().invoke(workflow_cli, ["manage"])

        assert result.exit_code == 0
        assert HINT_PREFIX not in result.output

    def test_tui_crash_no_hint(self):
        with (
            patch("console_link.workflow.commands.manage._configure_file_logging"),
            patch("console_link.workflow.commands.manage._initialize_k8s_client", return_value=Mock()),
            patch("console_link.workflow.commands.manage.make_argo_service", return_value=Mock()),
            patch("console_link.workflow.commands.manage.make_k8s_pod_scraper", return_value=Mock()),
            patch("console_link.workflow.commands.manage.WaiterInterface"),
            patch("console_link.workflow.commands.manage.WorkflowTreeApp") as mock_app_cls,
        ):
            mock_app_cls.return_value.run.side_effect = Exception("TUI crash")
            result = CliRunner().invoke(workflow_cli, ["manage"])

        assert result.exit_code != 0
        assert HINT_PREFIX not in result.output


class TestShowHints:
    """workflow show — hint for content views; absent for --list, --history, --run."""

    def _mock_resource_show(self):
        """Patch show internals so a resource show completes without K8s calls."""
        return (
            patch("console_link.workflow.commands.show._load_k8s_config_or_exit", return_value=True),
            patch("console_link.workflow.commands.show._handle_resource_show"),
        )

    def _mock_task_show(self):
        return (
            patch("console_link.workflow.commands.show._load_k8s_config_or_exit", return_value=True),
            patch("console_link.workflow.commands.show._handle_task_show"),
        )

    def test_resource_show_displays_hint(self):
        with (
            patch("console_link.workflow.commands.show._load_k8s_config_or_exit", return_value=True),
            patch("console_link.workflow.commands.show._handle_resource_show"),
        ):
            result = CliRunner().invoke(
                workflow_cli, ["show", "snapshotmigration.migration-0"]
            )

        assert result.exit_code == 0
        assert HINT_PREFIX in result.output
        assert NO_FURTHER_ACTION_HINT in result.output

    def test_task_show_displays_hint(self):
        with (
            patch("console_link.workflow.commands.show._load_k8s_config_or_exit", return_value=True),
            patch("console_link.workflow.commands.show._handle_task_show"),
        ):
            result = CliRunner().invoke(
                workflow_cli, ["show", "evaluatemetadata", "snapshotmigration.migration-0"]
            )

        assert result.exit_code == 0
        assert HINT_PREFIX in result.output
        assert NO_FURTHER_ACTION_HINT in result.output

    @patch("console_link.workflow.commands.show.load_k8s_config")
    @patch("console_link.workflow.commands.show._list_show_targets")
    def test_list_without_resource_does_not_show_hint(self, mock_list, _k8s):
        mock_list.return_value = None

        result = CliRunner().invoke(workflow_cli, ["show", "--list"])

        assert result.exit_code == 0
        assert HINT_PREFIX not in result.output

    def test_history_flag_does_not_show_hint(self):
        with (
            patch("console_link.workflow.commands.show._load_k8s_config_or_exit", return_value=True),
            patch("console_link.workflow.commands.show._handle_resource_show"),
        ):
            result = CliRunner().invoke(
                workflow_cli, ["show", "snapshotmigration.migration-0", "--history"]
            )

        assert result.exit_code == 0
        assert HINT_PREFIX not in result.output

    def test_run_flag_does_not_show_hint(self):
        with (
            patch("console_link.workflow.commands.show._load_k8s_config_or_exit", return_value=True),
            patch("console_link.workflow.commands.show._handle_resource_show"),
        ):
            result = CliRunner().invoke(
                workflow_cli, ["show", "snapshotmigration.migration-0", "--run", "1"]
            )

        assert result.exit_code == 0
        assert HINT_PREFIX not in result.output

    def test_clean_flag_does_not_show_hint(self):
        # --clean is script-friendly raw output; the hint must not leak into it.
        with (
            patch("console_link.workflow.commands.show._load_k8s_config_or_exit", return_value=True),
            patch("console_link.workflow.commands.show._handle_resource_show"),
        ):
            result = CliRunner().invoke(
                workflow_cli, ["show", "snapshotmigration.migration-0", "--clean"]
            )

        assert result.exit_code == 0
        assert HINT_PREFIX not in result.output

    @patch("console_link.workflow.commands.show.load_k8s_config")
    @patch("console_link.workflow.commands.show._show_all_current_outputs")
    def test_all_flag_without_resource_does_not_show_hint(self, mock_all, _k8s):
        """--all with no resource goes through _handle_show_without_resource and returns early."""
        result = CliRunner().invoke(workflow_cli, ["show", "--all"])

        assert result.exit_code == 0
        assert HINT_PREFIX not in result.output
