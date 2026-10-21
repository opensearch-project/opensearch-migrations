import copy
import json
from unittest.mock import Mock

import pytest

from console_link.models.backfill_rfs import perform_archive
from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.committed_metrics import archive_summary, read_json
from console_link.models.utils import DeploymentStatus


def work_item(shard=0, start=0, end=4, complete=True, previous=None, mode=None):
    work_id = f"b3JkZXJz__{shard}__{start}"
    return {work_id: {
        "completedAt": 100,
        "committed_metrics": {
            "schemaVersion": 1, "workItemId": work_id,
            "snapshotName": "snapshot", "indexName": "orders", "shardNumber": shard,
            "fromPosition": start, "nextPosition": end, "shardComplete": complete,
            "previousSnapshotName": previous, "deltaMode": mode,
            "metrics": {
                "source": {"upserts": 3, "deletes": 1 if previous else 0, "bytes": 300},
                "requests": {"batches": 2, "total": 3, "retries": 1, "bytes": 900, "durationMillis": 90},
            },
            "liveLuceneDocCount": 16 if complete else None,
            "previousLiveLuceneDocCount": 14 if complete and previous else None,
        },
    }}


def metrics(item):
    return next(iter(item.values()))["committed_metrics"]


def test_aggregate_ranges_shards_and_weighted_averages():
    first = work_item(end=2, complete=False)
    last = work_item(start=2)
    second_shard = work_item(shard=1)
    metrics(first)["metrics"]["requests"] = {
        "batches": 1, "total": 1, "retries": 0, "bytes": 100, "durationMillis": 10,
    }
    summary = archive_summary([last, first, second_shard, copy.deepcopy(first)], expected_shards=2)
    assert summary["complete"]
    totals, = summary["indices"]
    assert totals["shards"] == 2
    assert totals["source"] == {"upserts": 9, "deletes": 0, "bytes": 900}
    assert totals["requests"] == {
        "batches": 5, "total": 7, "retries": 2, "bytes": 1900, "durationMillis": 190,
    }
    assert totals["liveLuceneDocCount"] == 32
    assert totals["previousLiveLuceneDocCount"] is None
    assert totals["averageSourceDocumentBytes"] == 100
    assert totals["averageRequestBytes"] == pytest.approx(1900 / 7)
    assert totals["averageRequestDurationMillis"] == pytest.approx(190 / 7)
    assert totals["sourceRootDocumentDelta"] is None


@pytest.mark.parametrize("mode,root_delta", [
    ("UPDATES_AND_DELETES", 2), ("UPDATES_ONLY", None), ("DELETES_ONLY", None),
])
def test_delta_counts(mode, root_delta):
    totals, = archive_summary([work_item(previous="previous", mode=mode)])["indices"]
    assert totals["snapshotLiveLuceneDelta"] == 2
    assert totals["sourceRootDocumentDelta"] == root_delta


@pytest.mark.parametrize("count", [None, 0])
def test_empty_and_unknown_counts_remain_distinct(count):
    item = work_item(end=0)
    record = metrics(item)
    record["liveLuceneDocCount"] = count
    for group in record["metrics"].values():
        for key in group:
            group[key] = 0
    summary = archive_summary([item])
    assert summary["complete"]
    totals, = summary["indices"]
    assert totals["liveLuceneDocCount"] == count
    assert totals["averageSourceDocumentBytes"] is None
    assert totals["averageRequestBytes"] is None
    assert totals["averageRequestDurationMillis"] is None


@pytest.mark.parametrize("records,expected_shards,reason", [
    ([work_item(start=1)], None, "Gap or overlap"),
    ([work_item(end=3, complete=False), work_item(start=2)], None, "Gap or overlap"),
    ([work_item(complete=False)], None, "shard completion"),
    ([work_item(), work_item(start=4, end=5)], None, "shard completion"),
    ([work_item(end=0, complete=False), work_item(start=1)], None, "no progress"),
    ([work_item()], 2, "Expected 2"),
    ([work_item(), {"legacy": {"completedAt": 100}}], None, "no committed metrics"),
    ([work_item(), {"pending": {}}], None, "not complete"),
    ([work_item(), {"shard_setup": {}}], None, "not complete"),
    ([work_item(), work_item(shard=1, previous="previous", mode="UPDATES_AND_DELETES")],
     None, "Snapshot mode changed"),
])
def test_incomplete_archives_do_not_publish_totals(records, expected_shards, reason):
    summary = archive_summary(records, expected_shards)
    assert not summary["complete"]
    assert "indices" not in summary
    assert reason in summary["reason"]


def test_conflicting_duplicates_are_rejected():
    item = work_item()
    conflict = copy.deepcopy(item)
    metrics(conflict)["metrics"]["source"]["upserts"] += 1
    assert "Conflicting" in archive_summary([item, conflict])["reason"]


@pytest.mark.parametrize("field,value", [("upserts", -1), ("bytes", True)])
def test_invalid_metrics_are_rejected(field, value):
    item = work_item()
    metrics(item)["metrics"]["source"][field] = value
    assert "Invalid source" in archive_summary([item])["reason"]


def test_search_response_and_ndjson_inputs(tmp_path):
    item = work_item()
    work_id, source = next(iter(item.items()))
    hit = {"_id": work_id, "_source": source}
    path = tmp_path / "hits.ndjson"
    path.write_text(json.dumps(hit) + "\n" + json.dumps(hit) + "\n")
    expected = archive_summary([item])
    assert archive_summary(read_json(path)) == expected
    assert archive_summary([{"hits": {"hits": [hit]}}]) == expected
    assert not archive_summary([{"timed_out": True, "hits": {"hits": [hit]}}])["complete"]


@pytest.mark.parametrize("records,complete", [
    ([work_item(), {"shard_setup": {"completedAt": 100}}], True),
    ([work_item(), {"pending": {}}], False),
    ([{"legacy": {"completedAt": 100}}], None),
])
def test_archive_writes_raw_documents_and_summary_before_deleting(tmp_path, records, complete):
    cluster = Mock(spec=Cluster)
    cluster.fetch_all_documents.return_value = iter(records)
    raw_path = tmp_path / "backup.json"
    summary_path = tmp_path / "backup.metrics.json"

    def check_archive(*args, **kwargs):
        assert json.loads(raw_path.read_text()) == records
        if complete is None:
            assert not summary_path.exists()
        else:
            assert json.loads(summary_path.read_text())["complete"] is complete

    cluster.call_api.side_effect = check_archive
    result = perform_archive(cluster, DeploymentStatus(0, 0, 0), str(tmp_path), raw_path.name)
    assert result.success
    assert result.value == str(raw_path)
    cluster.call_api.assert_called_once_with(
        "/.migrations_working_state", method=HttpMethod.DELETE, params={"ignore_unavailable": "true"})


def test_archive_write_failure_keeps_coordinator(tmp_path):
    cluster = Mock(spec=Cluster)
    records = [work_item()]
    cluster.fetch_all_documents.return_value = iter(records)
    (tmp_path / "backup.metrics.json").mkdir()
    with pytest.raises(OSError):
        perform_archive(cluster, DeploymentStatus(0, 0, 0), str(tmp_path), "backup.json")
    assert json.loads((tmp_path / "backup.json").read_text()) == records
    cluster.call_api.assert_not_called()
