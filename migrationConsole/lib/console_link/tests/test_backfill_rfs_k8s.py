import json
import logging
from kubernetes import config
import os
import pathlib
from unittest.mock import ANY

import pytest
import requests

from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.backfill_base import Backfill, BackfillStatus
from console_link.models.backfill_rfs import (K8sRFSBackfill, RfsWorkersInProgress, WorkingIndexDoesntExist)
from console_link.models.factories import get_backfill
from console_link.models.kubectl_runner import KubectlRunner

from console_link.models.utils import DeploymentStatus
from tests.utils import create_valid_cluster


TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"


@pytest.fixture(autouse=True)
def mock_kube_config(monkeypatch):
    # Prevent actual config loading
    monkeypatch.setattr(config, "load_incluster_config", lambda: None)
    monkeypatch.setattr(config, "load_kube_config", lambda: None)


@pytest.fixture
def k8s_rfs_backfill():
    k8s_rfs_config = {
        "reindex_from_snapshot": {
            "k8s": {
                "namespace": "ma",
                "deployment_name": "ma-reindex-from-snapshot"
            }
        }
    }
    return get_backfill(k8s_rfs_config, target_cluster=create_valid_cluster())


def test_get_backfill_valid_k8s_rfs(k8s_rfs_backfill):
    assert isinstance(k8s_rfs_backfill, K8sRFSBackfill)
    assert isinstance(k8s_rfs_backfill, Backfill)


def test_k8s_rfs_backfill_start_sets_desired_count(k8s_rfs_backfill, mocker):
    assert k8s_rfs_backfill.default_scale == 5
    mock = mocker.patch.object(KubectlRunner, 'perform_scale_command', autospec=True)
    k8s_rfs_backfill.start()

    assert isinstance(k8s_rfs_backfill, K8sRFSBackfill)
    mock.assert_called_once_with(k8s_rfs_backfill.kubectl_runner, 5)


def test_k8s_rfs_backfill_pause_sets_desired_count(k8s_rfs_backfill, mocker):
    assert k8s_rfs_backfill.default_scale == 5
    mock = mocker.patch.object(KubectlRunner, 'perform_scale_command', autospec=True)
    k8s_rfs_backfill.pause()

    assert isinstance(k8s_rfs_backfill, K8sRFSBackfill)
    mock.assert_called_once_with(k8s_rfs_backfill.kubectl_runner, 0)


def test_k8s_rfs_backfill_stop_sets_desired_count(k8s_rfs_backfill, mocker):
    assert k8s_rfs_backfill.default_scale == 5
    mock = mocker.patch.object(KubectlRunner, 'perform_scale_command', autospec=True)
    k8s_rfs_backfill.stop()

    assert isinstance(k8s_rfs_backfill, K8sRFSBackfill)
    mock.assert_called_once_with(k8s_rfs_backfill.kubectl_runner, 0)


def test_k8s_rfs_backfill_scale_sets_desired_count(k8s_rfs_backfill, mocker):
    mock = mocker.patch.object(KubectlRunner, 'perform_scale_command', autospec=True)
    k8s_rfs_backfill.scale(3)

    assert isinstance(k8s_rfs_backfill, K8sRFSBackfill)
    mock.assert_called_once_with(k8s_rfs_backfill.kubectl_runner, 3)


def test_k8s_rfs_backfill_status_gets_deployment_status(k8s_rfs_backfill, mocker):
    mocked_instance_status = DeploymentStatus(
        desired=3,
        running=1,
        pending=2
    )
    mock = mocker.patch.object(KubectlRunner, 'retrieve_deployment_status', autospec=True,
                               return_value=mocked_instance_status)
    value = k8s_rfs_backfill.get_status(deep_check=False)

    mock.assert_called_once_with(k8s_rfs_backfill.kubectl_runner)
    assert value.success
    assert BackfillStatus.RUNNING == value.value[0]
    assert "Pods - Running: 1, Pending: 2, Desired: 3" == value.value[1]


def test_k8s_rfs_calculates_backfill_status_from_deployment_status_stopped(k8s_rfs_backfill, mocker):
    mocked_stopped_status = DeploymentStatus(
        desired=8,
        running=0,
        pending=0
    )
    mock = mocker.patch.object(KubectlRunner, 'retrieve_deployment_status', autospec=True,
                               return_value=mocked_stopped_status)
    value = k8s_rfs_backfill.get_status(deep_check=False)

    mock.assert_called_once_with(k8s_rfs_backfill.kubectl_runner)
    assert value.success
    assert BackfillStatus.STOPPED == value.value[0]
    assert "Pods - Running: 0, Pending: 0, Desired: 8" == value.value[1]


def test_k8s_rfs_calculates_backfill_status_from_deployment_status_starting(k8s_rfs_backfill, mocker):
    mocked_starting_status = DeploymentStatus(
        desired=8,
        running=0,
        pending=6
    )
    mock = mocker.patch.object(KubectlRunner, 'retrieve_deployment_status', autospec=True,
                               return_value=mocked_starting_status)
    value = k8s_rfs_backfill.get_status(deep_check=False)

    mock.assert_called_once_with(k8s_rfs_backfill.kubectl_runner)
    assert value.success
    assert BackfillStatus.STARTING == value.value[0]
    assert "Pods - Running: 0, Pending: 6, Desired: 8" == value.value[1]


def test_k8s_rfs_calculates_backfill_status_from_deployment_status_running(k8s_rfs_backfill, mocker):
    mocked_running_status = DeploymentStatus(
        desired=1,
        running=3,
        pending=1
    )
    mock = mocker.patch.object(KubectlRunner, 'retrieve_deployment_status', autospec=True,
                               return_value=mocked_running_status)
    value = k8s_rfs_backfill.get_status(deep_check=False)

    mock.assert_called_once_with(k8s_rfs_backfill.kubectl_runner)
    assert value.success
    assert BackfillStatus.RUNNING == value.value[0]
    assert "Pods - Running: 3, Pending: 1, Desired: 1" == value.value[1]


def test_k8s_rfs_get_status_deep_check(k8s_rfs_backfill, mocker):
    mocked_instance_status = DeploymentStatus(
        desired=1,
        running=1,
        pending=0
    )
    mock = mocker.patch.object(KubectlRunner, 'retrieve_deployment_status', autospec=True,
                               return_value=mocked_instance_status)
    with open(TEST_DATA_DIRECTORY / "migrations_working_state_search.json") as f:
        data = json.load(f)
        total_shards = data['hits']['total']['value']
    mocked_detailed_status = f"Work items total: {total_shards}"
    mock_detailed = mocker.patch('console_link.models.backfill_rfs.get_detailed_status',
                                 autospec=True, return_value=mocked_detailed_status)

    value = k8s_rfs_backfill.get_status(deep_check=True)

    mock.assert_called_once_with(k8s_rfs_backfill.kubectl_runner)
    mock_detailed.assert_called_once()
    assert value.success
    assert BackfillStatus.RUNNING == value.value[0]
    assert "Pods - Running: 1, Pending: 0, Desired: 1" in value.value[1]
    assert str(total_shards) in value.value[1]


def test_k8s_rfs_deep_status_check_failure(k8s_rfs_backfill, mocker, caplog):
    mocked_instance_status = DeploymentStatus(
        desired=1,
        running=1,
        pending=0
    )
    mock_k8s = mocker.patch.object(KubectlRunner, 'retrieve_deployment_status', autospec=True,
                                   return_value=mocked_instance_status)
    mock_api = mocker.patch.object(Cluster, 'call_api', side_effect=requests.exceptions.RequestException())

    with caplog.at_level(logging.DEBUG):
        result = k8s_rfs_backfill.get_status(deep_check=True)

    # still make sure we logged the reason
    assert "Failed to get detailed status" in caplog.text
    mock_api.assert_called_once()
    mock_k8s.assert_called_once()
    assert result.success
    assert result.value[0] == BackfillStatus.RUNNING


def test_k8s_rfs_backfill_archive_as_expected(k8s_rfs_backfill, mocker, tmpdir):
    mocked_instance_status = DeploymentStatus(
        desired=0,
        running=0,
        pending=0
    )
    mocker.patch.object(KubectlRunner, 'retrieve_deployment_status', autospec=True, return_value=mocked_instance_status)

    mocked_docs = [{"id": {"key": "value"}}]
    mocker.patch.object(Cluster, 'fetch_all_documents', autospec=True, return_value=mocked_docs)

    mock_api = mocker.patch.object(Cluster, 'call_api', autospec=True, return_value=requests.Response())

    result = k8s_rfs_backfill.archive(archive_dir_path=tmpdir.strpath, archive_file_name="backup.json")

    assert result.success
    expected_path = os.path.join(tmpdir.strpath, "backup.json")
    assert result.value == expected_path
    assert os.path.exists(expected_path)
    with open(expected_path, "r") as f:
        assert json.load(f) == mocked_docs

    mock_api.assert_called_once_with(
        ANY, "/.migrations_working_state", method=HttpMethod.DELETE,
        params={"ignore_unavailable": "true"}
    )


def test_k8s_rfs_backfill_archive_no_index_as_expected(k8s_rfs_backfill, mocker, tmpdir):
    mocked_instance_status = DeploymentStatus(
        desired=0,
        running=0,
        pending=0
    )
    mocker.patch.object(KubectlRunner, 'retrieve_deployment_status', autospec=True, return_value=mocked_instance_status)

    response_404 = requests.Response()
    response_404.status_code = 404
    mocker.patch.object(
        Cluster, 'fetch_all_documents', autospec=True,
        side_effect=requests.HTTPError(response=response_404, request=requests.Request())
    )

    result = k8s_rfs_backfill.archive()

    assert not result.success
    assert isinstance(result.value, WorkingIndexDoesntExist)


def test_k8s_rfs_backfill_archive_errors_if_in_progress(k8s_rfs_backfill, mocker):
    mocked_instance_status = DeploymentStatus(
        desired=3,
        running=1,
        pending=2
    )
    mock = mocker.patch.object(KubectlRunner, 'retrieve_deployment_status', autospec=True,
                               return_value=mocked_instance_status)
    result = k8s_rfs_backfill.archive()

    mock.assert_called_once_with(k8s_rfs_backfill.kubectl_runner)
    assert not result.success
    assert isinstance(result.value, RfsWorkersInProgress)
