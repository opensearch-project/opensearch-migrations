"""Aggregate committed work-item metrics from one coordinator run or its Console archive."""

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


def completed_record(hit):
    source = hit["_source"]
    if source.get("completedAt") is None:
        raise ValueError(f"Work item is not complete: {hit['_id']}")
    if hit["_id"] == "shard_setup":
        return None
    record = source.get("committed_metrics")
    if record is None:
        raise ValueError(f"Work item has no committed metrics: {hit['_id']}")
    if record["workItemId"] != hit["_id"] or record["schemaVersion"] != 1:
        raise ValueError("Unrecognized committed metrics identity or schema")
    return record


def committed_records(values):
    records = {}
    for hit in coordinator_hits(values):
        record = completed_record(hit)
        if record is None:
            continue
        key = (record["snapshotName"], record["workItemId"])
        if key in records and records[key] != record:
            raise ValueError(f"Conflicting metrics for {hit['_id']}")
        records[key] = record
    return list(records.values())


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
    metrics = totals["metrics"]
    source, requests, snapshot = metrics["source"], metrics["requests"], metrics["snapshot"]
    metrics["averageSourceDocumentBytes"] = source["bytes"] / source["upserts"] if source["upserts"] else None
    total = requests["total"]
    metrics["averageRequestBytes"] = requests["bytes"] / total if total else None
    metrics["averageRequestDurationMillis"] = requests["durationMillis"] / total if total else None
    current, previous = snapshot["liveLuceneDocCount"], snapshot["previousLiveLuceneDocCount"]
    metrics["snapshotLiveLuceneDelta"] = current - previous if current is not None and previous is not None else None
    metrics["sourceRootDocumentDelta"] = (
        source["upserts"] - source["deletes"] if totals["deltaMode"] == "UPDATES_AND_DELETES" else None)


def aggregate(records, expected_shards=None):
    shards = defaultdict(list)
    for record in records:
        shards[(record["snapshotName"], record["indexName"], record["shardNumber"])].append(record)
    if expected_shards is not None and len(shards) != expected_shards:
        raise ValueError(f"Expected {expected_shards} snapshot shards; found {len(shards)}")
    indices = {}
    for identity, work in sorted(shards.items()):
        work.sort(key=lambda record: record["fromPosition"])
        validate_shard(identity, work)
        final = work[-1]
        key = identity[:2]
        totals = indices.setdefault(key, {
            "snapshotName": identity[0], "indexName": identity[1],
            "previousSnapshotName": final["previousSnapshotName"], "deltaMode": final["deltaMode"], "shards": 0,
            "metrics": {
                "source": dict.fromkeys(("upserts", "deletes", "bytes"), 0),
                "requests": dict.fromkeys(("batches", "total", "retries", "bytes", "durationMillis"), 0),
                "snapshot": {"liveLuceneDocCount": 0, "previousLiveLuceneDocCount": 0},
            },
        })
        totals["shards"] += 1
        for record in work:
            if (record["previousSnapshotName"], record["deltaMode"]) != (
                    totals["previousSnapshotName"], totals["deltaMode"]):
                raise ValueError(f"Snapshot mode changed within index {key}")
            add_metrics(totals["metrics"], record["metrics"])
        add_snapshot_counts(totals["metrics"]["snapshot"], final["metrics"]["snapshot"])
    for totals in indices.values():
        derive_averages_and_deltas(totals)
    return list(indices.values())


def archive_summary(values, expected_shards=None):
    try:
        if not any(hit["_source"].get("committed_metrics") is not None for hit in coordinator_hits(values)):
            return None
        totals = aggregate(committed_records(values), expected_shards)
        return {"schemaVersion": 1, "complete": True, "indices": totals}
    except (ValueError, KeyError, TypeError) as error:
        return {"schemaVersion": 1, "complete": False, "reason": str(error)}


def write_archive_summary(backup_path):
    path = Path(backup_path)
    summary = archive_summary(read_json(path))
    if summary is not None:
        path.with_suffix(".metrics.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path, help="Console archive or coordinator search results")
    parser.add_argument("--expected-shards", type=int, help="Optional independent source shard count")
    args = parser.parse_args()
    print(json.dumps(archive_summary(read_json(args.archive), args.expected_shards), indent=2))


if __name__ == "__main__":
    main()
