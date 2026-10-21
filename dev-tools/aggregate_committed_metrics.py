#!/usr/bin/env python3
"""Aggregate coordinator search hits or Console archives; optionally verify prepared S3 exports."""

import argparse
from collections import defaultdict
import gzip
import json
from pathlib import Path


def read_json(path):
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8") as stream:
        text = stream.read()
    try:
        value = json.loads(text)
        return value if isinstance(value, list) else [value]
    except json.JSONDecodeError:
        return [json.loads(line) for line in text.splitlines() if line.strip()]


def coordinator_hits(values):
    for value in values:
        if "hits" in value:
            if value.get("timed_out") or value.get("_shards", {}).get("failed", 0):
                raise ValueError("Coordinator search was incomplete")
            yield from value["hits"]["hits"]
        elif "_id" in value:
            yield value
        else:
            yield from ({"_id": work_id, "_source": source} for work_id, source in value.items())


def committed_records(values):
    records = {}
    for hit in coordinator_hits(values):
        if hit["_id"] == "shard_setup":
            continue
        source = hit["_source"]
        record = source.get("committed_metrics")
        if record is None or source.get("completedAt") is None:
            raise ValueError(f"Work item is not complete: {hit['_id']}")
        if record["workItemId"] != hit["_id"] or record["schemaVersion"] != 1:
            raise ValueError("Unrecognized committed metrics identity or schema")
        key = (record["sessionId"], record["snapshotName"], record["workItemId"])
        if key in records and records[key] != record:
            raise ValueError(f"Conflicting commits for {hit['_id']}")
        records[key] = record
    return list(records.values())


def verify_exports(records, exports):
    expected = {record["commitId"]: record for record in records}
    found = set()
    for record in exports:
        commit_id = record["commitId"]
        if commit_id in expected:
            if record != expected[commit_id]:
                raise ValueError(f"S3 record differs from its committed value: {commit_id}")
            found.add(commit_id)
    if found != expected.keys():
        raise ValueError("S3 export is missing committed work items")


def validate_shard(identity, work):
    position = 0
    for index, record in enumerate(work):
        end = record["nextPosition"]
        if record["fromPosition"] != position or end < position:
            raise ValueError(f"Gap or overlap in shard {identity}")
        if record["shardComplete"] != (index == len(work) - 1):
            raise ValueError(f"Missing or conflicting shard completion: {identity}")
        if not record["shardComplete"] and end == position:
            raise ValueError(f"Checkpoint made no progress: {identity}")
        position = end


def add_metrics(totals, metrics):
    for group in ("source", "requests"):
        for name in totals[group]:
            value = metrics[group][name]
            if type(value) is not int or value < 0:
                raise ValueError(f"Invalid {group}.{name} metric")
            totals[group][name] += value


def add_snapshot_counts(totals, final):
    for name in ("liveLuceneDocCount", "previousLiveLuceneDocCount"):
        value = final[name]
        if value is not None and (type(value) is not int or value < 0):
            raise ValueError(f"Invalid snapshot count: {name}")
        totals[name] = None if value is None or totals[name] is None else totals[name] + value


def derive_averages_and_deltas(totals):
    source, requests = totals["source"], totals["requests"]
    totals["averageSourceDocumentBytes"] = source["bytes"] / source["upserts"] if source["upserts"] else None
    attempts = requests["attempts"]
    totals["averageRequestBytes"] = requests["bytes"] / attempts if attempts else None
    totals["averageRequestDurationNanos"] = requests["durationNanos"] / attempts if attempts else None
    current, previous = totals["liveLuceneDocCount"], totals["previousLiveLuceneDocCount"]
    totals["snapshotLiveLuceneDelta"] = current - previous if current is not None and previous is not None else None
    totals["sourceRootDocumentDelta"] = (
        source["upserts"] - source["deletes"] if totals["deltaMode"] == "UPDATES_AND_DELETES" else None)


def aggregate(records, expected_shards):
    shards = defaultdict(list)
    for record in records:
        shards[(record["sessionId"], record["snapshotName"], record["indexName"], record["shardNumber"])].append(record)
    if len(shards) != expected_shards:
        raise ValueError(f"Expected {expected_shards} snapshot shards; found {len(shards)}")
    indices = {}
    for identity, work in sorted(shards.items()):
        work.sort(key=lambda record: record["fromPosition"])
        validate_shard(identity, work)
        final = work[-1]
        key = (*identity[:3], final["previousSnapshotName"], final["deltaMode"])
        totals = indices.setdefault(key, {
            "sessionId": identity[0], "snapshotName": identity[1], "indexName": identity[2],
            "previousSnapshotName": key[3], "deltaMode": key[4], "shards": 0,
            "source": dict.fromkeys(("upserts", "deletes", "bytes"), 0),
            "requests": dict.fromkeys(("batches", "attempts", "bytes", "durationNanos"), 0),
            "liveLuceneDocCount": 0, "previousLiveLuceneDocCount": 0,
        })
        totals["shards"] += 1
        for record in work:
            if (record["previousSnapshotName"], record["deltaMode"]) != key[3:]:
                raise ValueError(f"Snapshot mode changed within shard {identity}")
            add_metrics(totals, record["metrics"])
        add_snapshot_counts(totals, final)
    for totals in indices.values():
        derive_averages_and_deltas(totals)
    return list(indices.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("coordinator", type=Path, nargs="+",
                        help="JSON search responses, NDJSON hits, or Console archives")
    parser.add_argument("--expected-shards", type=int, required=True, help="Number of migrated source snapshot shards")
    parser.add_argument("--s3-records", type=Path, nargs="*", help="Downloaded JSON/NDJSON files, optionally gzipped")
    args = parser.parse_args()
    records = committed_records(value for path in args.coordinator for value in read_json(path))
    if args.s3_records is not None:
        verify_exports(records, (value for path in args.s3_records for value in read_json(path)))
    print(json.dumps(aggregate(records, args.expected_shards), indent=2))


if __name__ == "__main__":
    main()
