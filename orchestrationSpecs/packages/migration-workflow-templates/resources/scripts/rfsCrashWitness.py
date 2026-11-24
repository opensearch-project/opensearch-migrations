#!/usr/bin/env python3
"""RFS crash-witness sidecar.

Watches ITS OWN pod via the Kubernetes API and appends one JSONL record to the shared emptyDir
verdict file per termination of the watched (bulk-loader) container:

    {"finishedAt": "<ISO-8601>", "exitCode": <int>, "reason": "<kubelet reason>",
     "restartCount": <int>, "container": "<name>"}

The RFS worker's startup dirty-lease sweep reads this file to attribute a predecessor's death:
OOM iff exitCode == 3 (ExitOnOutOfMemoryError) or reason == "OOMKilled" — never bare 137.

Properties this process must keep:
  * Pure witness: it holds no coordinator credentials and mutates nothing in the cluster.
  * Event-driven, with reconciliation: on every (re)connect it reads the pod's current
    containerStatuses, so a termination it did not watch live (including one that happened while
    this sidecar itself was down — lastState persists until the NEXT termination) is still
    recorded late.
  * Append-only with in-memory dedup keyed on (restartCount, finishedAt, exitCode); the dedup
    set is rebuilt from the file on startup so a sidecar restart never duplicates records.
"""

import json
import logging
import os
import sys
import time

from kubernetes import client, config, watch

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("rfs-crash-witness")

POD_NAME = os.environ["POD_NAME"]
POD_NAMESPACE = os.environ["POD_NAMESPACE"]
WATCHED_CONTAINER_NAME = os.environ.get("WATCHED_CONTAINER_NAME", "bulk-loader")
CRASH_VERDICT_FILE = os.environ["CRASH_VERDICT_FILE"]


def dedup_key(record: dict):
    return (record.get("restartCount"), record.get("finishedAt"), record.get("exitCode"))


def load_seen_keys(path: str):
    seen = set()
    try:
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    seen.add(dedup_key(json.loads(line)))
                except ValueError:
                    logger.warning("Skipping malformed verdict line during startup scan: %r", line)
    except FileNotFoundError:
        pass
    return seen


def append_record(path: str, record: dict):
    with open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(record, separators=(",", ":")) + "\n")
        f.flush()
        os.fsync(f.fileno())


def termination_records(pod) -> list:
    """Extract termination records for the watched container from a pod object."""
    records = []
    statuses = (pod.status.container_statuses or []) if pod.status else []
    for status in statuses:
        if status.name != WATCHED_CONTAINER_NAME:
            continue
        restart_count = status.restart_count or 0
        for state_holder, restart_offset in ((status.last_state, -1), (status.state, 0)):
            terminated = getattr(state_holder, "terminated", None) if state_holder else None
            if terminated is None or terminated.finished_at is None:
                continue
            records.append({
                # last_state describes the run BEFORE the current restartCount's run; the live
                # state (if terminated) is the current run. The offset only matters for dedup.
                "restartCount": restart_count + restart_offset,
                "finishedAt": terminated.finished_at.isoformat(),
                "exitCode": terminated.exit_code,
                "reason": terminated.reason,
                "container": status.name,
            })
    return records


def reconcile(pod, seen_keys) -> None:
    for record in termination_records(pod):
        key = dedup_key(record)
        if key in seen_keys:
            continue
        seen_keys.add(key)
        append_record(CRASH_VERDICT_FILE, record)
        logger.info("Recorded termination verdict: %s", record)


def main() -> int:
    config.load_incluster_config()
    core = client.CoreV1Api()
    seen_keys = load_seen_keys(CRASH_VERDICT_FILE)
    logger.info("Watching pod %s/%s container %s; %d prior verdict(s) loaded",
                POD_NAMESPACE, POD_NAME, WATCHED_CONTAINER_NAME, len(seen_keys))
    field_selector = f"metadata.name={POD_NAME}"
    while True:
        try:
            # Reconcile from the current state first so nothing is lost across reconnects.
            pod = core.read_namespaced_pod(name=POD_NAME, namespace=POD_NAMESPACE)
            reconcile(pod, seen_keys)
            resource_version = pod.metadata.resource_version
            w = watch.Watch()
            for event in w.stream(core.list_namespaced_pod, namespace=POD_NAMESPACE,
                                  field_selector=field_selector,
                                  resource_version=resource_version,
                                  timeout_seconds=300):
                obj = event.get("object")
                if obj is None or not hasattr(obj, "status"):
                    continue
                reconcile(obj, seen_keys)
        except Exception:  # noqa: BLE001 - a witness must outlive transient API errors
            logger.exception("Watch loop error; reconnecting shortly")
            time.sleep(5)


if __name__ == "__main__":
    sys.exit(main())
