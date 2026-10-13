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
                                              WorkingIndexDoesntExist, compute_dervived_values,
                                              generate_doc_counts_query, get_detailed_status,
                                              get_detailed_status_obj, parse_doc_counts_response)
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


def test_ecs_rfs_get_status_deep_check_threads_has_failed_documents(ecs_rfs_backfill, mocker):
    # Presence must reach get_detailed_status for the plain-text path.
    mocker.patch.object(ECSService, 'get_instance_statuses', autospec=True,
                        return_value=DeploymentStatus(desired=1, running=1, pending=0))
    mock_detailed = mocker.patch('console_link.models.backfill_rfs.get_detailed_status',
                                 autospec=True, return_value="detail")

    ecs_rfs_backfill.get_status(deep_check=True, has_failed_documents=True)

    assert mock_detailed.call_args.kwargs["has_failed_documents"] is True


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

    def test_completed_with_failed_documents(self):
        """A completed backfill with failed documents reports COMPLETED_WITH_ERRORS."""
        _, _, _, status = compute_dervived_values(
            self.mock_cluster, self.test_index, 10, 10, None, False, has_failed_documents=True
        )
        assert status == StepStateWithPause.COMPLETED_WITH_ERRORS

    def test_completed_with_no_failed_documents(self):
        """No failures leaves a completed backfill as COMPLETED."""
        _, _, _, status = compute_dervived_values(
            self.mock_cluster, self.test_index, 10, 10, None, False, has_failed_documents=False
        )
        assert status == StepStateWithPause.COMPLETED

    def test_completed_without_failed_document_stream(self):
        """No stream configured (None) must not be guessed as an error."""
        _, _, _, status = compute_dervived_values(
            self.mock_cluster, self.test_index, 10, 10, None, False, has_failed_documents=None
        )
        assert status == StepStateWithPause.COMPLETED

    def test_in_progress_ignores_failed_documents(self):
        """A still-running backfill is never promoted, regardless of failures."""
        started_epoch = int(datetime.now(timezone.utc).timestamp()) - 3600
        _, _, _, status = compute_dervived_values(
            self.mock_cluster, self.test_index, 10, 5, started_epoch, True, has_failed_documents=True
        )
        assert status == StepStateWithPause.RUNNING


class TestDocCounts:
    """Succeeded/failed document totals summed from completed work items in the working state index."""

    INDEX = ".migrations_working_state"

    @staticmethod
    def _response(body):
        response = MagicMock()
        response.json.return_value = body
        return response

    def _fake_cluster(self, doc_counts_body=None, doc_counts_error=None):
        """A target cluster whose working state index reports all work items completed."""
        def call_api(path, method=HttpMethod.GET, data=None, headers=None, **kwargs):
            if path == f"/{self.INDEX}":
                return self._response({})
            if path == f"/{self.INDEX}/_doc/shard_setup":
                return self._response({"_source": {"completedAt": 1_000_000_000}})
            query = json.loads(data) if data else {}
            aggs = query.get("aggs", {})
            if "docsSucceeded" in aggs:
                if doc_counts_error is not None:
                    raise doc_counts_error
                return self._response(doc_counts_body)
            if "max_completed" in aggs:
                return self._response({"aggregations": {"max_completed": {"value": 1_000_003_600}}})
            if "unique_pair_count" in aggs:
                return self._response({"hits": {"total": {"value": 4}, "hits": []}})
            if query.get("query") == {"match": {"_id": "shard_setup"}}:
                return self._response({"hits": {"hits": [{"_source": {"completedAt": 1_000_000_000}}]}})
            raise AssertionError(f"Unexpected call: {path} {data}")

        cluster = MagicMock()
        cluster.call_api.side_effect = call_api
        return cluster

    def test_query_sums_counts_over_completed_work_items_only(self):
        query = generate_doc_counts_query()
        assert query["size"] == 0
        assert query["query"] == {"bool": {"must": [{"exists": {"field": "completedAt"}}],
                                           "must_not": [{"match": {"_id": "shard_setup"}}]}}
        assert query["aggs"] == {"docsSucceeded": {"sum": {"field": "docsSucceeded"}},
                                 "docsFailed": {"sum": {"field": "docsFailed"}}}

    def test_parse_returns_totals(self):
        cluster = self._fake_cluster({"aggregations": {"docsSucceeded": {"value": 180.0},
                                                       "docsFailed": {"value": 5.0}}})
        assert parse_doc_counts_response(generate_doc_counts_query(), cluster, self.INDEX) == (180, 5)

    def test_parse_returns_none_when_aggregations_missing(self):
        cluster = self._fake_cluster({"hits": {"total": {"value": 0}, "hits": []}})
        assert parse_doc_counts_response(generate_doc_counts_query(), cluster, self.INDEX) == (None, None)

    def test_parse_returns_none_when_query_fails(self):
        cluster = self._fake_cluster(doc_counts_error=requests.exceptions.ConnectionError("boom"))
        assert parse_doc_counts_response(generate_doc_counts_query(), cluster, self.INDEX) == (None, None)

    def test_detailed_status_obj_includes_totals(self):
        cluster = self._fake_cluster({"aggregations": {"docsSucceeded": {"value": 180.0},
                                                       "docsFailed": {"value": 5.0}}})
        status = get_detailed_status_obj(cluster)
        assert status.status == StepStateWithPause.COMPLETED
        assert status.docs_succeeded == 180
        assert status.docs_failed == 5
        dumped = status.model_dump(mode="json")
        assert dumped["docs_succeeded"] == 180
        assert dumped["docs_failed"] == 5

    def test_detailed_status_obj_older_run_reports_zero(self):
        # Sum aggregations over a field no work item has return 0.
        cluster = self._fake_cluster({"aggregations": {"docsSucceeded": {"value": 0.0},
                                                       "docsFailed": {"value": 0.0}}})
        status = get_detailed_status_obj(cluster)
        assert status.docs_succeeded == 0
        assert status.docs_failed == 0

    def test_detailed_status_obj_survives_doc_count_query_failure(self):
        cluster = self._fake_cluster(doc_counts_error=requests.exceptions.ConnectionError("boom"))
        status = get_detailed_status_obj(cluster)
        assert status.status == StepStateWithPause.COMPLETED
        assert status.shard_total == 4
        assert status.docs_succeeded is None
        assert status.docs_failed is None

    def test_detailed_status_obj_initializing_leaves_totals_unset(self, mocker):
        mocker.patch('console_link.models.backfill_rfs.parse_shard_setup_response', return_value=False)
        status = get_detailed_status_obj(self._fake_cluster())
        assert status.docs_succeeded is None
        assert status.docs_failed is None

    def test_detailed_status_text_includes_totals(self):
        cluster = self._fake_cluster({"aggregations": {"docsSucceeded": {"value": 180.0},
                                                       "docsFailed": {"value": 5.0}}})
        text = get_detailed_status(cluster, session_name=None)
        assert "Documents succeeded (completed work items): 180" in text
        assert "Documents failed (completed work items): 5" in text

    def test_detailed_status_text_shows_na_when_totals_unavailable(self):
        cluster = self._fake_cluster(doc_counts_error=requests.exceptions.ConnectionError("boom"))
        text = get_detailed_status(cluster, session_name=None)
        assert "Documents succeeded (completed work items): N/A" in text
        assert "Documents failed (completed work items): N/A" in text
