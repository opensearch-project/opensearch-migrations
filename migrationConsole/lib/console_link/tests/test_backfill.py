import json
import os
import pathlib
from unittest.mock import ANY, MagicMock
from datetime import datetime, timezone

import pytest
import requests

from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.backfill_base import Backfill, BackfillStatus
from console_link.models.step_state import StepStateWithPause
from console_link.models.backfill_rfs import (DockerRFSBackfill, ECSRFSBackfill, RfsWorkersInProgress,
                                              WorkingIndexDoesntExist, compute_dervived_values)
from console_link.models.ecs_service import ECSService
from console_link.models.factories import UnsupportedBackfillTypeError, get_backfill
from console_link.models.utils import DeploymentStatus
from tests.utils import create_valid_cluster

TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"
AWS_REGION = "us-east-1"


@pytest.fixture
def ecs_rfs_backfill():
    ecs_rfs_config = {
        "reindex_from_snapshot": {
            "ecs": {
                "cluster_name": "migration-aws-integ-ecs-cluster",
                "service_name": "migration-aws-integ-reindex-from-snapshot"
            }
        }
    }
    return get_backfill(ecs_rfs_config, target_cluster=create_valid_cluster())


def test_get_backfill_valid_docker_rfs():
    docker_rfs_config = {
        "reindex_from_snapshot": {
            "docker": None
        }
    }
    docker_rfs_backfill = get_backfill(docker_rfs_config, target_cluster=create_valid_cluster())
    assert isinstance(docker_rfs_backfill, DockerRFSBackfill)
    assert isinstance(docker_rfs_backfill, Backfill)


def test_get_backfill_valid_ecs_rfs():
    ecs_rfs_config = {
        "reindex_from_snapshot": {
            "ecs": {
                "cluster_name": "migration-aws-integ-ecs-cluster",
                "service_name": "migration-aws-integ-reindex-from-snapshot"
            }
        }
    }
    ecs_rfs_backfill = get_backfill(ecs_rfs_config, target_cluster=create_valid_cluster())
    assert isinstance(ecs_rfs_backfill, ECSRFSBackfill)
    assert isinstance(ecs_rfs_backfill, Backfill)


def test_get_backfill_unsupported_type():
    unknown_config = {
        "fetch": {"data": "xyz"}
    }
    with pytest.raises(UnsupportedBackfillTypeError) as excinfo:
        get_backfill(unknown_config, None)
    assert "Unsupported backfill type" in str(excinfo.value.args[0])
    assert "fetch" in str(excinfo.value.args[1])


def test_get_backfill_multiple_types():
    unknown_config = {
        "fetch": {"data": "xyz"},
        "new_backfill": {"data": "abc"}
    }
    with pytest.raises(UnsupportedBackfillTypeError) as excinfo:
        get_backfill(unknown_config, None)
    assert "fetch" in excinfo.value.args[1]
    assert "new_backfill" in excinfo.value.args[1]


def test_cant_instantiate_with_multiple_rfs_deployment_types():
    config = {
        "reindex_from_snapshot": {
            "docker": None,
            "ecs": {"aws_region": "us-east-1"}
        }
    }
    with pytest.raises(ValueError) as excinfo:
        get_backfill(config, create_valid_cluster())
    assert "Invalid config file for RFS backfill" in str(excinfo.value.args[0])
    assert "More than one value is present" in str(excinfo.value.args[1]['reindex_from_snapshot'][0])


def test_ecs_rfs_backfill_start_sets_ecs_desired_count(ecs_rfs_backfill, mocker):
    assert ecs_rfs_backfill.default_scale == 5
    mock = mocker.patch.object(ECSService, 'set_desired_count', autospec=True)
    ecs_rfs_backfill.start()

    assert isinstance(ecs_rfs_backfill, ECSRFSBackfill)
    mock.assert_called_once_with(ecs_rfs_backfill.ecs_client, 5)


def test_ecs_rfs_backfill_pause_sets_ecs_desired_count(ecs_rfs_backfill, mocker):
    assert ecs_rfs_backfill.default_scale == 5
    mock = mocker.patch.object(ECSService, 'set_desired_count', autospec=True)
    ecs_rfs_backfill.pause()

    assert isinstance(ecs_rfs_backfill, ECSRFSBackfill)
    mock.assert_called_once_with(ecs_rfs_backfill.ecs_client, 0)


def test_ecs_rfs_backfill_stop_sets_ecs_desired_count(ecs_rfs_backfill, mocker):
    assert ecs_rfs_backfill.default_scale == 5
    mock = mocker.patch.object(ECSService, 'set_desired_count', autospec=True)
    ecs_rfs_backfill.stop()

    assert isinstance(ecs_rfs_backfill, ECSRFSBackfill)
    mock.assert_called_once_with(ecs_rfs_backfill.ecs_client, 0)


def test_ecs_rfs_backfill_scale_sets_ecs_desired_count(ecs_rfs_backfill, mocker):
    mock = mocker.patch.object(ECSService, 'set_desired_count', autospec=True)
    ecs_rfs_backfill.scale(3)

    assert isinstance(ecs_rfs_backfill, ECSRFSBackfill)
    mock.assert_called_once_with(ecs_rfs_backfill.ecs_client, 3)


def test_ecs_rfs_backfill_status_gets_ecs_instance_statuses(ecs_rfs_backfill, mocker):
    mocked_instance_status = DeploymentStatus(
        desired=3,
        running=1,
        pending=2
    )
    mock = mocker.patch.object(ECSService, 'get_instance_statuses', autospec=True, return_value=mocked_instance_status)
    value = ecs_rfs_backfill.get_status(deep_check=False)

    mock.assert_called_once_with(ecs_rfs_backfill.ecs_client)
    assert value.success
    assert BackfillStatus.RUNNING == value.value[0]
    assert str(mocked_instance_status) == value.value[1]


def test_ecs_rfs_calculates_backfill_status_from_ecs_instance_statuses_stopped(ecs_rfs_backfill, mocker):
    mocked_stopped_status = DeploymentStatus(
        desired=8,
        running=0,
        pending=0
    )
    mock = mocker.patch.object(ECSService, 'get_instance_statuses', autospec=True, return_value=mocked_stopped_status)
    value = ecs_rfs_backfill.get_status(deep_check=False)

    mock.assert_called_once_with(ecs_rfs_backfill.ecs_client)
    assert value.success
    assert BackfillStatus.STOPPED == value.value[0]
    assert str(mocked_stopped_status) == value.value[1]


def test_ecs_rfs_calculates_backfill_status_from_ecs_instance_statuses_starting(ecs_rfs_backfill, mocker):
    mocked_starting_status = DeploymentStatus(
        desired=8,
        running=0,
        pending=6
    )
    mock = mocker.patch.object(ECSService, 'get_instance_statuses', autospec=True, return_value=mocked_starting_status)
    value = ecs_rfs_backfill.get_status(deep_check=False)

    mock.assert_called_once_with(ecs_rfs_backfill.ecs_client)
    assert value.success
    assert BackfillStatus.STARTING == value.value[0]
    assert str(mocked_starting_status) == value.value[1]


def test_ecs_rfs_calculates_backfill_status_from_ecs_instance_statuses_running(ecs_rfs_backfill, mocker):
    mocked_running_status = DeploymentStatus(
        desired=1,
        running=3,
        pending=1
    )
    mock = mocker.patch.object(ECSService, 'get_instance_statuses', autospec=True, return_value=mocked_running_status)
    value = ecs_rfs_backfill.get_status(deep_check=False)

    mock.assert_called_once_with(ecs_rfs_backfill.ecs_client)
    assert value.success
    assert BackfillStatus.RUNNING == value.value[0]
    assert str(mocked_running_status) == value.value[1]


def test_ecs_rfs_get_status_deep_check(ecs_rfs_backfill, mocker):
    mocked_instance_status = DeploymentStatus(
        desired=1,
        running=1,
        pending=0
    )
    with open(TEST_DATA_DIRECTORY / "migrations_working_state_search.json") as f:
        data = json.load(f)
        total_shards = data['hits']['total']['value']

    # Mock the deployment status retrieval
    mock = mocker.patch.object(ECSService, 'get_instance_statuses', autospec=True,
                               return_value=mocked_instance_status)

    # Mock the detailed status function to return a known value
    mocked_detailed_status = f"Work items total: {total_shards}"
    mock_detailed = mocker.patch('console_link.models.backfill_rfs.get_detailed_status',
                                 autospec=True, return_value=mocked_detailed_status)

    # Call the function being tested
    value = ecs_rfs_backfill.get_status(deep_check=True)

    # Verify the mocks were called
    mock.assert_called_once_with(ecs_rfs_backfill.ecs_client)
    mock_detailed.assert_called_once()

    # Verify the output
    assert value.success
    assert BackfillStatus.RUNNING == value.value[0]
    assert str(mocked_instance_status) in value.value[1]
    assert str(total_shards) in value.value[1]


def test_ecs_rfs_deep_status_check_failure(ecs_rfs_backfill, mocker, caplog):
    mocked_instance_status = DeploymentStatus(
        desired=1,
        running=1,
        pending=0
    )
    mock_ecs = mocker.patch.object(ECSService, 'get_instance_statuses', autospec=True,
                                   return_value=mocked_instance_status)
    mock_api = mocker.patch.object(Cluster, 'call_api', side_effect=requests.exceptions.RequestException())

    # Call function being tested
    result = ecs_rfs_backfill.get_status(deep_check=True)

    # Verify the logs contain failure message
    assert "Failed to get detailed status" in caplog.text

    # Verify mock calls
    mock_ecs.assert_called_once_with(ecs_rfs_backfill.ecs_client)
    mock_api.assert_called_once()

    # Verify result
    assert result.success
    assert result.value[0] == BackfillStatus.RUNNING


def test_ecs_rfs_backfill_archive_as_expected(ecs_rfs_backfill, mocker, tmpdir):
    mocked_instance_status = DeploymentStatus(
        desired=0,
        running=0,
        pending=0
    )
    mocker.patch.object(ECSService, 'get_instance_statuses', autospec=True, return_value=mocked_instance_status)

    mocked_docs = [{"id": {"key": "value"}}]
    mocker.patch.object(Cluster, 'fetch_all_documents', autospec=True, return_value=mocked_docs)

    mock_api = mocker.patch.object(Cluster, 'call_api', autospec=True, return_value=requests.Response())

    result = ecs_rfs_backfill.archive(archive_dir_path=tmpdir.strpath, archive_file_name="backup.json")

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


def test_ecs_rfs_backfill_archive_no_index_as_expected(ecs_rfs_backfill, mocker, tmpdir):
    mocked_instance_status = DeploymentStatus(
        desired=0,
        running=0,
        pending=0
    )
    mocker.patch.object(ECSService, 'get_instance_statuses', autospec=True, return_value=mocked_instance_status)

    response_404 = requests.Response()
    response_404.status_code = 404
    mocker.patch.object(
        Cluster, 'fetch_all_documents', autospec=True,
        side_effect=requests.HTTPError(response=response_404, request=requests.Request())
    )

    result = ecs_rfs_backfill.archive()

    assert not result.success
    assert isinstance(result.value, WorkingIndexDoesntExist)


def test_ecs_rfs_backfill_archive_errors_if_in_progress(ecs_rfs_backfill, mocker):
    mocked_instance_status = DeploymentStatus(
        desired=3,
        running=1,
        pending=2
    )
    mock = mocker.patch.object(ECSService, 'get_instance_statuses', autospec=True, return_value=mocked_instance_status)
    result = ecs_rfs_backfill.archive()

    mock.assert_called_once_with(ecs_rfs_backfill.ecs_client)
    assert not result.success
    assert isinstance(result.value, RfsWorkersInProgress)


def test_docker_backfill_not_implemented_commands():
    docker_rfs_config = {
        "reindex_from_snapshot": {
            "docker": None
        }
    }
    docker_rfs_backfill = get_backfill(docker_rfs_config, target_cluster=create_valid_cluster())
    assert isinstance(docker_rfs_backfill, DockerRFSBackfill)

    with pytest.raises(NotImplementedError):
        docker_rfs_backfill.start()

    with pytest.raises(NotImplementedError):
        docker_rfs_backfill.stop()

    with pytest.raises(NotImplementedError):
        docker_rfs_backfill.scale(units=3)


class TestComputeDerivedValues:

    def setup_method(self):
        # Create a mock for the target_cluster
        self.mock_cluster = MagicMock()
        self.mock_cluster.call_api.return_value.json.return_value = {
            "aggregations": {"max_completed": {"value": 1000000000}}
        }

        # Test index name
        self.test_index = ".migrations_working_state"

    def test_zero_indices(self):
        """Test compute_dervived_values when there are 0 indices to migrate."""
        # When total=0, it should report as COMPLETED
        total = 0
        completed = 0
        started_epoch = None
        active_workers = False

        finished_iso, percentage_completed, eta_ms, status = compute_dervived_values(
            self.mock_cluster, self.test_index, total, completed, started_epoch, active_workers
        )

        assert status == StepStateWithPause.COMPLETED
        assert percentage_completed == 100.0
        assert eta_ms is None
        assert finished_iso is not None  # Should have a timestamp for completion

    def test_all_completed(self):
        """Test compute_dervived_values when all indices are completed."""
        total = 10
        completed = 10
        started_epoch = int(datetime.now(timezone.utc).timestamp()) - 3600  # Started 1 hour ago
        active_workers = False

        finished_iso, percentage_completed, eta_ms, status = compute_dervived_values(
            self.mock_cluster, self.test_index, total, completed, started_epoch, active_workers
        )

        assert status == StepStateWithPause.COMPLETED
        assert percentage_completed == 100.0
        assert eta_ms is None
        assert finished_iso is not None

    def test_partially_completed(self):
        """Test compute_dervived_values when some indices are still in progress."""
        total = 10
        completed = 5
        started_epoch = int(datetime.now(timezone.utc).timestamp()) - 3600  # Started 1 hour ago
        active_workers = True

        finished_iso, percentage_completed, eta_ms, status = compute_dervived_values(
            self.mock_cluster, self.test_index, total, completed, started_epoch, active_workers
        )

        assert status == StepStateWithPause.RUNNING
        assert percentage_completed == 50.0
        assert eta_ms is not None  # Should have an ETA
        assert finished_iso is None  # Not completed yet

    def test_partially_completed_paused(self):
        """Test compute_dervived_values when some indices are completed but workers are paused."""
        total = 10
        completed = 5
        started_epoch = int(datetime.now(timezone.utc).timestamp()) - 3600  # Started 1 hour ago
        active_workers = False

        finished_iso, percentage_completed, eta_ms, status = compute_dervived_values(
            self.mock_cluster, self.test_index, total, completed, started_epoch, active_workers
        )

        assert status == StepStateWithPause.PAUSED
        assert percentage_completed == 50.0
        assert eta_ms is None  # No ETA when paused
        assert finished_iso is None  # Not completed yet
