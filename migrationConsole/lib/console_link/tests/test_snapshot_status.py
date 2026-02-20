import pytest
from datetime import datetime

from console_link.models.snapshot import SnapshotStatus, StepState


@pytest.fixture
def opensearch_snapshot_info():
    """OpenSearch format with byte-level stats."""
    return {
        "snapshot": "test-snapshot",
        "state": "IN_PROGRESS",
        "stats": {
            "total": {
                "size_in_bytes": 1073741824,  # 1 GB
                "file_count": 1000
            },
            "processed": {
                "size_in_bytes": 536870912,  # 512 MB (50%)
                "file_count": 500
            },
            "incremental": {
                "size_in_bytes": 0,
                "file_count": 0
            },
            "start_time_in_millis": 1627980000000,  # August 3, 2021
            "time_in_millis": 60000  # 1 minute
        }
    }


@pytest.fixture
def es_7_8_snapshot_info():
    """Elasticsearch 7.8+ format with shard-level stats."""
    return {
        "snapshot": "test-snapshot",
        "state": "IN_PROGRESS",
        "shards_stats": {
            "initializing": 0,
            "started": 50,
            "finalizing": 0,
            "done": 50,
            "failed": 0,
            "total": 100
        },
        "start_time_in_millis": 1627980000000,  # August 3, 2021
        "time_in_millis": 60000  # 1 minute
    }


@pytest.fixture
def es_pre_7_8_snapshot_info():
    """Elasticsearch <7.8 format with simple shards summary."""
    return {
        "snapshot": "test-snapshot",
        "state": "IN_PROGRESS",
        "shards": {
            "total": 100,
            "successful": 25,
            "failed": 0
        },
        "start_time_in_millis": 1627980000000,  # August 3, 2021
        "time_in_millis": 30000  # 30 seconds
    }


@pytest.fixture
def completed_snapshot_info():
    """A snapshot that is complete."""
    return {
        "snapshot": "test-snapshot",
        "state": "SUCCESS",
        "stats": {
            "total": {
                "size_in_bytes": 1073741824,  # 1 GB
                "file_count": 1000
            },
            "processed": {
                "size_in_bytes": 1073741824,  # 1 GB (100%)
                "file_count": 1000
            },
            "incremental": {
                "size_in_bytes": 0,
                "file_count": 0
            },
            "start_time_in_millis": 1627980000000,  # August 3, 2021
            "time_in_millis": 120000  # 2 minutes
        }
    }


@pytest.fixture
def failed_snapshot_info():
    """A snapshot that failed."""
    return {
        "snapshot": "test-snapshot",
        "state": "FAILED",
        "stats": {
            "total": {
                "size_in_bytes": 1073741824,  # 1 GB
                "file_count": 1000
            },
            "processed": {
                "size_in_bytes": 536870912,  # 512 MB (50%)
                "file_count": 500
            },
            "incremental": {
                "size_in_bytes": 0,
                "file_count": 0
            },
            "start_time_in_millis": 1627980000000,  # August 3, 2021
            "time_in_millis": 60000  # 1 minute
        }
    }


@pytest.fixture
def partial_snapshot_info():
    """A partial snapshot."""
    return {
        "snapshot": "test-snapshot",
        "state": "PARTIAL",
        "shards_stats": {
            "initializing": 0,
            "started": 0,
            "finalizing": 0,
            "done": 75,
            "failed": 25,
            "total": 100
        },
        "start_time_in_millis": 1627980000000,  # August 3, 2021
        "time_in_millis": 60000  # 1 minute
    }


def test_from_snapshot_info_with_opensearch_format(opensearch_snapshot_info):
    """Test from_snapshot_info with OpenSearch format."""
    status = SnapshotStatus.from_snapshot_info(opensearch_snapshot_info)

    # Verify status
    assert status.status == StepState.RUNNING
    assert status.percentage_completed == pytest.approx(50.0)

    # Verify timestamps
    assert status.started == datetime.fromtimestamp(1627980000000 / 1000)
    expected_finished = datetime.fromtimestamp((1627980000000 + 60000) / 1000)
    assert status.finished == expected_finished

    # Verify ETA
    assert status.eta_ms == pytest.approx(60000.0)  # 1 minute left


def test_from_snapshot_info_with_es_7_8_format(es_7_8_snapshot_info):
    """Test from_snapshot_info with Elasticsearch 7.8+ format."""
    status = SnapshotStatus.from_snapshot_info(es_7_8_snapshot_info)

    # Verify status
    assert status.status == StepState.RUNNING
    assert status.percentage_completed == pytest.approx(50.0)

    # Verify timestamps
    assert status.started == datetime.fromtimestamp(1627980000000 / 1000)
    expected_finished = datetime.fromtimestamp((1627980000000 + 60000) / 1000)
    assert status.finished == expected_finished

    # Verify ETA
    assert status.eta_ms == pytest.approx(60000.0)  # 1 minute left


def test_from_snapshot_info_with_es_pre_7_8_format(es_pre_7_8_snapshot_info):
    """Test from_snapshot_info with Elasticsearch <7.8 format."""
    status = SnapshotStatus.from_snapshot_info(es_pre_7_8_snapshot_info)

    # Verify status
    assert status.status == StepState.RUNNING
    assert status.percentage_completed == pytest.approx(25.0)

    # Verify timestamps
    assert status.started == datetime.fromtimestamp(1627980000000 / 1000)
    expected_finished = datetime.fromtimestamp((1627980000000 + 30000) / 1000)
    assert status.finished == expected_finished

    # Verify ETA
    assert status.eta_ms == pytest.approx(90000.0)  # 1.5 minutes left


def test_from_snapshot_info_with_completed_snapshot(completed_snapshot_info):
    """Test from_snapshot_info with a completed snapshot."""
    status = SnapshotStatus.from_snapshot_info(completed_snapshot_info)

    # Verify status
    assert status.status == StepState.COMPLETED
    assert status.percentage_completed == pytest.approx(100.0)

    # Verify timestamps
    assert status.started == datetime.fromtimestamp(1627980000000 / 1000)
    expected_finished = datetime.fromtimestamp((1627980000000 + 120000) / 1000)
    assert status.finished == expected_finished

    # Verify ETA is 0 for completed snapshots
    assert status.eta_ms == 0.0


def test_from_snapshot_info_with_failed_snapshot(failed_snapshot_info):
    """Test from_snapshot_info with a failed snapshot."""
    status = SnapshotStatus.from_snapshot_info(failed_snapshot_info)

    # Verify status
    assert status.status == StepState.FAILED
    assert status.percentage_completed == pytest.approx(50.0)

    # Verify timestamps
    assert status.started == datetime.fromtimestamp(1627980000000 / 1000)
    expected_finished = datetime.fromtimestamp((1627980000000 + 60000) / 1000)
    assert status.finished == expected_finished

    # Verify ETA is still calculated even for failed snapshots
    assert status.eta_ms == pytest.approx(60000.0)


def test_from_snapshot_info_with_partial_snapshot(partial_snapshot_info):
    """Test from_snapshot_info with a partial snapshot."""
    status = SnapshotStatus.from_snapshot_info(partial_snapshot_info)

    # Verify status
    assert status.status == StepState.FAILED  # PARTIAL maps to FAILED
    assert status.percentage_completed == pytest.approx(100.0)

    # Verify timestamps
    assert status.started == datetime.fromtimestamp(1627980000000 / 1000)
    expected_finished = datetime.fromtimestamp((1627980000000 + 60000) / 1000)
    assert status.finished == expected_finished

    # Verify ETA
    assert status.eta_ms is None


def test_from_snapshot_info_with_zero_total_units():
    """Test from_snapshot_info when total units is zero."""
    snapshot_info = {
        "snapshot": "test-snapshot",
        "state": "IN_PROGRESS",
        "stats": {
            "total": {
                "size_in_bytes": 0,
                "file_count": 0
            },
            "processed": {
                "size_in_bytes": 0,
                "file_count": 0
            },
            "start_time_in_millis": 1627980000000,
            "time_in_millis": 1000
        }
    }

    status = SnapshotStatus.from_snapshot_info(snapshot_info)

    # Should not raise ZeroDivisionError
    assert status.percentage_completed == pytest.approx(0.0)
    assert status.eta_ms is None


def test_from_snapshot_info_with_missing_fields():
    """Test from_snapshot_info with missing fields."""
    # Minimal snapshot info with only the state field
    snapshot_info = {
        "snapshot": "test-snapshot",
        "state": "IN_PROGRESS",
    }

    status = SnapshotStatus.from_snapshot_info(snapshot_info)

    # Should handle missing fields gracefully
    assert status.status == StepState.RUNNING
    assert status.percentage_completed == pytest.approx(0.0)
    assert status.started is None
    assert status.finished is None
    assert status.eta_ms is None


def test_from_snapshot_info_with_mixed_stats_sources():
    """Test from_snapshot_info with stats from multiple sources."""
    snapshot_info = {
        "snapshot": "test-snapshot",
        "state": "IN_PROGRESS",
        "shards_stats": {
            "initializing": 0,
            "started": 25,
            "finalizing": 0,
            "done": 75,
            "failed": 0,
            "total": 100
        },
        "stats": {
            "total": {
                "size_in_bytes": 1073741824,  # 1 GB
                "file_count": 1000
            },
            "processed": {
                "size_in_bytes": 805306368,  # 768 MB (75%)
                "file_count": 750
            },
            "incremental": {
                "size_in_bytes": 0,
                "file_count": 0
            }
        },
        "start_time_in_millis": 1627980000000,
        "time_in_millis": 90000
    }

    status = SnapshotStatus.from_snapshot_info(snapshot_info)

    # Stats should take precedence over shards_stats
    assert status.percentage_completed == pytest.approx(75.0)
    assert status.eta_ms is not None
