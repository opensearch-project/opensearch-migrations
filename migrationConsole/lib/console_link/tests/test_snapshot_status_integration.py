"""
Integration tests for snapshot status calculation across cluster versions.

Tests that SnapshotStatus.from_snapshot_info() correctly calculates progress
for both first (non-incremental) and subsequent (incremental) snapshots.

For incremental snapshots, progress is calculated using incremental bytes
(actual work for this snapshot) rather than total bytes (which includes
data from prior snapshots).
"""
import json
import pytest
import requests

from console_link.models.snapshot import SnapshotStatus, StepState
from tests.search_containers import SearchContainer, Version, CLUSTER_SNAPSHOT_DIR, elastic, opensearch


REPO_NAME = "test_repo"
SNAPSHOT_1 = "snapshot-1"
SNAPSHOT_2 = "snapshot-2"


def create_snapshot_repo(base_url: str, repo_name: str):
    """Create a filesystem snapshot repository."""
    resp = requests.put(
        f"{base_url}/_snapshot/{repo_name}",
        json={
            "type": "fs",
            "settings": {"location": CLUSTER_SNAPSHOT_DIR}
        }
    )
    resp.raise_for_status()


def create_index_with_data(base_url: str, index_name: str, doc_count: int, version: Version):
    """Create an index and bulk insert documents."""
    requests.put(f"{base_url}/{index_name}", json={
        "settings": {"number_of_shards": 1, "number_of_replicas": 0}
    }).raise_for_status()

    bulk_body = ""
    for i in range(doc_count):
        # ES 6.x requires _type field
        if version.major_version == 6:
            bulk_body += json.dumps({"index": {"_index": index_name, "_type": "_doc"}}) + "\n"
        else:
            bulk_body += json.dumps({"index": {"_index": index_name}}) + "\n"
        bulk_body += json.dumps({"field": f"value-{i}", "data": "x" * 100}) + "\n"

    requests.post(
        f"{base_url}/_bulk",
        data=bulk_body,
        headers={"Content-Type": "application/x-ndjson"}
    ).raise_for_status()

    requests.post(f"{base_url}/{index_name}/_refresh").raise_for_status()


def take_snapshot(base_url: str, repo_name: str, snapshot_name: str, wait: bool = True):
    """Take a snapshot and optionally wait for completion."""
    resp = requests.put(
        f"{base_url}/_snapshot/{repo_name}/{snapshot_name}",
        params={"wait_for_completion": str(wait).lower()}
    )
    resp.raise_for_status()
    return resp.json()


def get_snapshot_status_raw(base_url: str, repo_name: str, snapshot_name: str) -> dict:
    """Get detailed snapshot status via _status API - returns the snapshot info dict."""
    resp = requests.get(f"{base_url}/_snapshot/{repo_name}/{snapshot_name}/_status")
    resp.raise_for_status()
    data = resp.json()
    return data.get("snapshots", [{}])[0]


@pytest.fixture(params=[
    Version(elastic, 6, 8, 23),
    Version(elastic, 7, 10, 2),
    Version(opensearch, 1, 3, 16),
    Version(opensearch, 2, 19, 1),
])
def search_container(request):
    """Fixture that provides a running search container for each version."""
    version = request.param
    container = SearchContainer(version, mem_limit="2G")
    container.start()
    yield container, version
    container.stop()


@pytest.mark.slow
class TestSnapshotStatusCalculation:
    """Test SnapshotStatus calculation against real cluster responses."""

    def test_first_snapshot_shows_100_percent_when_complete(self, search_container):
        """First snapshot should show 100% when complete."""
        container, version = search_container
        base_url = container.get_url()

        create_snapshot_repo(base_url, REPO_NAME)
        create_index_with_data(base_url, "test-index-1", 50, version)
        take_snapshot(base_url, REPO_NAME, SNAPSHOT_1, wait=True)

        snapshot_info = get_snapshot_status_raw(base_url, REPO_NAME, SNAPSHOT_1)
        status = SnapshotStatus.from_snapshot_info(snapshot_info)

        print(f"\n[{version}] First snapshot status: {status.percentage_completed}%")
        print(f"  Raw stats: {json.dumps(snapshot_info.get('stats', {}), indent=2)}")

        assert status.status == StepState.COMPLETED
        assert status.percentage_completed == pytest.approx(100.0, rel=0.01), \
            f"First snapshot should be 100% complete, got {status.percentage_completed}%"

    def test_incremental_snapshot_progress_uses_incremental_bytes(self, search_container):
        """
        Test that incremental snapshot progress uses incremental bytes as denominator.

        For incremental snapshots:
        - incremental.size_in_bytes = actual new data to transfer
        - total.size_in_bytes = logical size including prior snapshot data

        Progress should be calculated as processed/incremental, not processed/total.
        """
        container, version = search_container
        base_url = container.get_url()

        # Setup: first snapshot
        create_snapshot_repo(base_url, REPO_NAME)
        create_index_with_data(base_url, "test-index-1", 50, version)
        take_snapshot(base_url, REPO_NAME, SNAPSHOT_1, wait=True)

        # Add more data and take incremental snapshot
        create_index_with_data(base_url, "test-index-2", 50, version)
        take_snapshot(base_url, REPO_NAME, SNAPSHOT_2, wait=True)

        # Get real response structure
        snapshot_info = get_snapshot_status_raw(base_url, REPO_NAME, SNAPSHOT_2)
        stats = snapshot_info.get("stats", {})

        incremental_bytes = stats.get("incremental", {}).get("size_in_bytes", 0)
        total_bytes = stats.get("total", {}).get("size_in_bytes", 0)

        print(f"\n[{version}] Real incremental snapshot stats:")
        print(f"  incremental: {incremental_bytes}")
        print(f"  total: {total_bytes}")

        # Skip if no stats (older ES versions)
        if not stats or incremental_bytes == 0:
            pytest.skip(f"Version {version} doesn't have incremental stats")

        # Simulate 50% progress during IN_PROGRESS state
        half_processed = incremental_bytes // 2
        simulated_in_progress = {
            "state": "IN_PROGRESS",
            "stats": {
                "incremental": {"size_in_bytes": incremental_bytes},
                "total": {"size_in_bytes": total_bytes},
                "processed": {"size_in_bytes": half_processed},
                "start_time_in_millis": 1000000,
                "time_in_millis": 5000
            }
        }

        status = SnapshotStatus.from_snapshot_info(simulated_in_progress)

        # Calculate what using total vs incremental would show
        pct_using_total = (half_processed / total_bytes) * 100 if total_bytes > 0 else 0
        pct_using_incremental = (half_processed / incremental_bytes) * 100 if incremental_bytes > 0 else 0

        print("  Simulated 50% in-progress:")
        print(f"    processed: {half_processed}")
        print(f"    Using total as denominator: {pct_using_total:.2f}%")
        print(f"    Using incremental as denominator: {pct_using_incremental:.2f}%")
        print(f"    Actual result: {status.percentage_completed:.2f}%")

        # The percentage should be ~50% (processed/incremental), not lower (processed/total)
        assert status.percentage_completed == pytest.approx(50.0, rel=0.1), \
            f"Expected ~50%, got {status.percentage_completed}%"

    def test_incremental_snapshot_progress_capped_at_100_percent(self, search_container):
        """
        Test that progress never exceeds 100% for incremental snapshots.

        When processed bytes equals incremental bytes, progress should be 100%.
        """
        container, version = search_container
        base_url = container.get_url()

        # Setup: first snapshot
        create_snapshot_repo(base_url, REPO_NAME)
        create_index_with_data(base_url, "test-index-1", 50, version)
        take_snapshot(base_url, REPO_NAME, SNAPSHOT_1, wait=True)

        # Add more data and take incremental snapshot
        create_index_with_data(base_url, "test-index-2", 50, version)
        take_snapshot(base_url, REPO_NAME, SNAPSHOT_2, wait=True)

        snapshot_info = get_snapshot_status_raw(base_url, REPO_NAME, SNAPSHOT_2)
        stats = snapshot_info.get("stats", {})

        incremental_bytes = stats.get("incremental", {}).get("size_in_bytes", 0)
        total_bytes = stats.get("total", {}).get("size_in_bytes", 0)

        if not stats or incremental_bytes == 0:
            pytest.skip(f"Version {version} doesn't have incremental stats")

        # Simulate fully processed incremental snapshot
        simulated_complete = {
            "state": "IN_PROGRESS",
            "stats": {
                "incremental": {"size_in_bytes": incremental_bytes},
                "total": {"size_in_bytes": total_bytes},
                "processed": {"size_in_bytes": incremental_bytes},
                "start_time_in_millis": 1000000,
                "time_in_millis": 5000
            }
        }

        status = SnapshotStatus.from_snapshot_info(simulated_complete)

        print(f"\n[{version}] Testing 100% cap:")
        print(f"  Result: {status.percentage_completed:.2f}%")

        assert status.percentage_completed <= 100.0, \
            f"Progress should never exceed 100%, got {status.percentage_completed}%"
        assert status.percentage_completed == pytest.approx(100.0, rel=0.01)
