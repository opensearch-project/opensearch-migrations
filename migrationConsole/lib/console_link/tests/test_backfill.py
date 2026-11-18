from unittest.mock import MagicMock
from datetime import datetime, timezone

import pytest
from console_link.models.backfill_base import Backfill
from console_link.models.step_state import StepStateWithPause
from console_link.models.backfill_rfs import DockerRFSBackfill, compute_dervived_values
from console_link.models.factories import UnsupportedBackfillTypeError, get_backfill
from tests.utils import create_valid_cluster


def test_get_backfill_valid_docker_rfs():
    docker_rfs_config = {
        "reindex_from_snapshot": {
            "docker": None
        }
    }
    docker_rfs_backfill = get_backfill(docker_rfs_config, target_cluster=create_valid_cluster())
    assert isinstance(docker_rfs_backfill, DockerRFSBackfill)
    assert isinstance(docker_rfs_backfill, Backfill)


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
            "k8s": {"namespace": "ma", "deployment_name": "ma-reindex-from-snapshot"}
        }
    }
    with pytest.raises(ValueError) as excinfo:
        get_backfill(config, create_valid_cluster())
    assert "Invalid config file for RFS backfill" in str(excinfo.value.args[0])
    assert "More than one value is present" in str(excinfo.value.args[1]['reindex_from_snapshot'][0])


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
