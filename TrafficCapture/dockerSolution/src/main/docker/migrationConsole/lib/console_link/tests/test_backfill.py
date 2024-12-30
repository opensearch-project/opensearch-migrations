import json
import os
import pathlib
from unittest.mock import ANY

import pytest
import requests
import requests_mock

from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.backfill_base import Backfill, BackfillStatus
from console_link.models.backfill_osi import OpenSearchIngestionBackfill
from console_link.models.backfill_rfs import (DockerRFSBackfill, ECSRFSBackfill, RfsWorkersInProgress,
                                              WorkingIndexDoesntExist)
from console_link.models.ecs_service import ECSService, InstanceStatuses
from console_link.models.factories import UnsupportedBackfillTypeError, get_backfill
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
    return get_backfill(ecs_rfs_config, None, target_cluster=create_valid_cluster())


def test_get_backfill_valid_osi():
    osi_config = {
        "opensearch_ingestion": {
            "pipeline_role_arn": "arn:aws:iam::123456789012:role/OSMigrations-pipelineRole",
            "vpc_subnet_ids": [
                "subnet-024004957a02ce923"
            ],
            "security_group_ids": [
                "sg-04536940716d101f6"
            ],
            "aws_region": "us-west-2",
            "pipeline_name": "unit-test-pipeline",
            "index_regex_selection": [
                "index-.*"
            ],
            "log_group_name": "/aws/vendedlogs/osi-unit-test-default",
            "tags": [
                "migration_deployment=1.0.0"
            ]
        }
    }
    osi_backfill = get_backfill(osi_config, source_cluster=create_valid_cluster(),
                                target_cluster=create_valid_cluster())
    assert isinstance(osi_backfill, OpenSearchIngestionBackfill)
    assert isinstance(osi_backfill, Backfill)


def test_get_backfill_osi_missing_clusters():
    osi_config = {
        "opensearch_ingestion": {
            "pipeline_role_arn": "arn:aws:iam::123456789012:role/OSMigrations-pipelineRole",
            "vpc_subnet_ids": [
                "subnet-024004957a02ce923"
            ],
            "security_group_ids": [
                "sg-04536940716d101f6"
            ],
            "aws_region": "us-west-2",
            "pipeline_name": "unit-test-pipeline",
            "index_regex_selection": [
                "index-.*"
            ],
            "log_group_name": "/aws/vendedlogs/osi-unit-test-default",
            "tags": [
                "migration_deployment=1.0.0"
            ]
        }
    }
    with pytest.raises(ValueError) as excinfo:
        get_backfill(osi_config, None, create_valid_cluster())
    assert "source_cluster" in str(excinfo.value.args[0])
    with pytest.raises(ValueError) as excinfo:
        get_backfill(osi_config, create_valid_cluster(), None)
    assert "target_cluster" in str(excinfo.value.args[0])


def test_get_backfill_valid_docker_rfs():
    docker_rfs_config = {
        "reindex_from_snapshot": {
            "docker": None
        }
    }
    docker_rfs_backfill = get_backfill(docker_rfs_config, None, target_cluster=create_valid_cluster())
    assert isinstance(docker_rfs_backfill, DockerRFSBackfill)
    assert isinstance(docker_rfs_backfill, Backfill)


def test_get_backfill_rfs_missing_target_cluster():
    docker_rfs_config = {
        "reindex_from_snapshot": {
            "docker": None
        }
    }
    with pytest.raises(ValueError) as excinfo:
        get_backfill(docker_rfs_config, create_valid_cluster(), None)
    assert "target_cluster" in str(excinfo.value.args[0])


def test_get_backfill_valid_ecs_rfs():
    ecs_rfs_config = {
        "reindex_from_snapshot": {
            "ecs": {
                "cluster_name": "migration-aws-integ-ecs-cluster",
                "service_name": "migration-aws-integ-reindex-from-snapshot"
            }
        }
    }
    ecs_rfs_backfill = get_backfill(ecs_rfs_config, None, target_cluster=create_valid_cluster())
    assert isinstance(ecs_rfs_backfill, ECSRFSBackfill)
    assert isinstance(ecs_rfs_backfill, Backfill)


def test_get_backfill_unsupported_type():
    unknown_config = {
        "fetch": {"data": "xyz"}
    }
    with pytest.raises(UnsupportedBackfillTypeError) as excinfo:
        get_backfill(unknown_config, None, None)
    assert "Unsupported backfill type" in str(excinfo.value.args[0])
    assert "fetch" in str(excinfo.value.args[1])


def test_get_backfill_multiple_types():
    unknown_config = {
        "fetch": {"data": "xyz"},
        "new_backfill": {"data": "abc"}
    }
    with pytest.raises(UnsupportedBackfillTypeError) as excinfo:
        get_backfill(unknown_config, None, None)
    assert "fetch" in excinfo.value.args[1]
    assert "new_backfill" in excinfo.value.args[1]


def test_cant_instantiate_with_multiple_types():
    config = {
        "opensearch_ingestion": {
            "pipeline_role_arn": "arn:aws:iam::123456789012:role/OSMigrations-pipelineRole",
        },
        "reindex_from_snapshot": {
            "docker": None
        }
    }
    with pytest.raises(ValueError) as excinfo:
        get_backfill(config, create_valid_cluster(), create_valid_cluster())
    assert "Invalid config file for backfill" in str(excinfo.value.args[0])
    assert "More than one value is present" in str(excinfo.value.args[1]['backfill'][0])


def test_cant_instantiate_with_multiple_rfs_deployment_types():
    config = {
        "reindex_from_snapshot": {
            "docker": None,
            "ecs": {"aws_region": "us-east-1"}
        }
    }
    with pytest.raises(ValueError) as excinfo:
        get_backfill(config, create_valid_cluster(), create_valid_cluster())
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
    mocked_instance_status = InstanceStatuses(
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
    mocked_stopped_status = InstanceStatuses(
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
    mocked_starting_status = InstanceStatuses(
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
    mocked_running_status = InstanceStatuses(
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
    target = create_valid_cluster()
    mocked_instance_status = InstanceStatuses(
        desired=1,
        running=1,
        pending=0
    )
    mock = mocker.patch.object(ECSService, 'get_instance_statuses', autospec=True, return_value=mocked_instance_status)
    with open(TEST_DATA_DIRECTORY / "migrations_working_state_search.json") as f:
        data = json.load(f)
        total_shards = data['hits']['total']['value']
    with requests_mock.Mocker() as rm:
        rm.get(f"{target.endpoint}/.migrations_working_state", status_code=200)
        rm.get(f"{target.endpoint}/.migrations_working_state/_search",
               status_code=200,
               json=data)
        value = ecs_rfs_backfill.get_status(deep_check=True)

    mock.assert_called_once_with(ecs_rfs_backfill.ecs_client)
    assert value.success
    assert BackfillStatus.RUNNING == value.value[0]
    assert str(mocked_instance_status) in value.value[1]
    assert str(total_shards) in value.value[1]


def test_ecs_rfs_deep_status_check_failure(ecs_rfs_backfill, mocker, caplog):
    mocked_instance_status = InstanceStatuses(
        desired=1,
        running=1,
        pending=0
    )
    mock_ecs = mocker.patch.object(ECSService, 'get_instance_statuses', autospec=True,
                                   return_value=mocked_instance_status)
    mock_api = mocker.patch.object(Cluster, 'call_api', side_effect=requests.exceptions.RequestException())
    result = ecs_rfs_backfill.get_status(deep_check=True)
    assert "Working state index does not yet exist" in caplog.text
    mock_ecs.assert_called_once()
    mock_api.assert_called_once()
    assert result.success
    assert result.value[0] == BackfillStatus.RUNNING


def test_ecs_rfs_backfill_archive_as_expected(ecs_rfs_backfill, mocker, tmpdir):
    mocked_instance_status = InstanceStatuses(
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
    mocked_instance_status = InstanceStatuses(
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
    mocked_instance_status = InstanceStatuses(
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
    docker_rfs_backfill = get_backfill(docker_rfs_config, None, target_cluster=create_valid_cluster())
    assert isinstance(docker_rfs_backfill, DockerRFSBackfill)

    with pytest.raises(NotImplementedError):
        docker_rfs_backfill.start()

    with pytest.raises(NotImplementedError):
        docker_rfs_backfill.stop()

    with pytest.raises(NotImplementedError):
        docker_rfs_backfill.scale(units=3)
