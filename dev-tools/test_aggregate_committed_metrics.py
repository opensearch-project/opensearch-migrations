import copy
import unittest

from aggregate_committed_metrics import aggregate, committed_records, verify_exports


def record(start, end, count, complete):
    return {
        "schemaVersion": 1, "commitId": f"commit-{start}", "workItemId": f"work-{start}",
        "sessionId": "run", "snapshotName": "new", "previousSnapshotName": "old",
        "deltaMode": "UPDATES_AND_DELETES", "indexName": "index", "shardNumber": 0,
        "fromPosition": start, "nextPosition": end, "shardComplete": complete,
        "liveLuceneDocCount": 14 if complete else None,
        "previousLiveLuceneDocCount": 12 if complete else None,
        "metrics": {
            "source": {"upserts": count, "deletes": 0, "bytes": count * 10},
            "requests": {"batches": 1, "attempts": 1, "bytes": count * 20, "durationNanos": count * 100},
        },
    }


class CommittedMetricsTest(unittest.TestCase):
    def setUp(self):
        self.records = [record(0, 1, 1, False), record(1, 4, 3, True)]

    def test_sums_ranges_and_derives_weighted_averages(self):
        self.records[0]["metrics"]["requests"] = {
            "batches": 1, "attempts": 2, "bytes": 40, "durationNanos": 200}
        result = aggregate(self.records, 1)[0]
        self.assertEqual(4, result["source"]["upserts"])
        self.assertAlmostEqual(100 / 3, result["averageRequestBytes"])
        self.assertAlmostEqual(500 / 3, result["averageRequestDurationNanos"])
        self.assertEqual(10, result["averageSourceDocumentBytes"])
        self.assertEqual(14, result["liveLuceneDocCount"])
        self.assertEqual(2, result["snapshotLiveLuceneDelta"])

    def test_duplicate_exports_and_abandoned_attempts_do_not_add_metrics(self):
        abandoned = copy.deepcopy(self.records[1])
        abandoned["commitId"] = "abandoned"
        abandoned["metrics"]["source"]["upserts"] = 1000
        verify_exports(self.records, [*self.records, *self.records, abandoned])
        hits = [{"_id": item["workItemId"], "_source": {"completedAt": 1, "committed_metrics": item}}
                for item in self.records]
        self.assertEqual(self.records, committed_records([*hits, *hits]))

    def test_console_archive_preserves_commits_after_coordinator_cleanup(self):
        archive = [{"shard_setup": {"completedAt": 1}}]
        archive.extend({item["workItemId"]: {"completedAt": 1, "committed_metrics": item}}
                       for item in self.records)
        restored = committed_records(archive)
        verify_exports(restored, self.records)
        self.assertEqual(4, aggregate(restored, 1)[0]["source"]["upserts"])

    def test_missing_or_conflicting_export_fails(self):
        with self.assertRaises(ValueError):
            verify_exports(self.records, self.records[:1])
        conflicting = copy.deepcopy(self.records)
        conflicting[0]["nextPosition"] = 2
        with self.assertRaises(ValueError):
            verify_exports(self.records, conflicting)

    def test_missing_shards_ranges_or_completion_fail(self):
        for records, count in ((self.records, 2), (self.records[1:], 1), (self.records[:1], 1)):
            with self.subTest(records=records, count=count), self.assertRaises(ValueError):
                aggregate(records, count)
        overlap = copy.deepcopy(self.records)
        overlap[1]["fromPosition"] = 0
        with self.assertRaises(ValueError):
            aggregate(overlap, 1)

    def test_zero_and_unknown_are_distinct(self):
        empty = record(0, 0, 0, True)
        empty["liveLuceneDocCount"] = 0
        self.assertEqual(0, aggregate([empty], 1)[0]["liveLuceneDocCount"])
        empty["liveLuceneDocCount"] = None
        self.assertIsNone(aggregate([empty], 1)[0]["liveLuceneDocCount"])

    def test_incomplete_coordinator_work_is_not_committed_export(self):
        with self.assertRaises(ValueError):
            committed_records([{"_id": "work-0", "_source": {"committed_metrics": self.records[0]}}])
        with self.assertRaises(ValueError):
            committed_records([{"timed_out": True, "hits": {"hits": []}}])


if __name__ == "__main__":
    unittest.main()
