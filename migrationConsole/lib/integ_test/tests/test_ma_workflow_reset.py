import subprocess
from unittest.mock import patch

import pytest

from integ_test.ma_workflow_test import _fail_if_migration_resources_exist, _run_workflow_reset


@patch("integ_test.ma_workflow_test.subprocess.run")
def test_run_workflow_reset_uses_requested_namespace(mock_run):
    mock_run.return_value = subprocess.CompletedProcess(
        args=[],
        returncode=0,
        stdout="reset complete",
        stderr="",
    )

    _run_workflow_reset(namespace="test-ns")

    mock_run.assert_called_once_with(
        [
            "workflow",
            "reset",
            "--all",
            "--include-proxies",
            "--delete-storage",
            "--namespace",
            "test-ns",
        ],
        capture_output=True,
        text=True,
        timeout=300,
    )


@patch("integ_test.ma_workflow_test.subprocess.run")
def test_run_workflow_reset_fails_test_on_nonzero_exit(mock_run):
    mock_run.return_value = subprocess.CompletedProcess(
        args=[],
        returncode=1,
        stdout="",
        stderr="reset failed",
    )

    with pytest.raises(pytest.fail.Exception, match="workflow reset exited with code 1"):
        _run_workflow_reset()


@patch("integ_test.ma_workflow_test.subprocess.run")
def test_run_workflow_reset_fails_test_on_timeout(mock_run):
    mock_run.side_effect = subprocess.TimeoutExpired(cmd=["workflow"], timeout=300)

    with pytest.raises(pytest.fail.Exception, match="workflow reset timed out after 300s"):
        _run_workflow_reset()


@patch("integ_test.ma_workflow_test.subprocess.run")
def test_run_workflow_reset_fails_test_when_cli_is_missing(mock_run):
    mock_run.side_effect = FileNotFoundError

    with pytest.raises(pytest.fail.Exception, match="'workflow' CLI not found"):
        _run_workflow_reset()


@patch("integ_test.ma_workflow_test.load_k8s_config")
@patch("integ_test.ma_workflow_test.list_migration_resources")
def test_fail_if_migration_resources_exist_allows_clean_namespace(mock_list, _mock_load):
    mock_list.return_value = []

    _fail_if_migration_resources_exist(namespace="ma")


@patch("integ_test.ma_workflow_test.load_k8s_config")
@patch("integ_test.ma_workflow_test.list_migration_resources")
def test_fail_if_migration_resources_exist_fails_with_resource_list(mock_list, _mock_load):
    mock_list.return_value = [
        ("kafkaclusters", "default", "Deleting", []),
        ("captureproxies", "capture-proxy", "Ready", []),
    ]

    with pytest.raises(pytest.fail.Exception, match="Migration resources already exist before test starts"):
        _fail_if_migration_resources_exist(namespace="ma")
