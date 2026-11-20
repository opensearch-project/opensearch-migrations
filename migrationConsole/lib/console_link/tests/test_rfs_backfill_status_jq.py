"""Covers how the RFS monitor CronJob turns `console --json backfill status --deep-check` into
SnapshotMigration.status.documentBackfill: the rfsBackfillStatus.jq mapping, fed the console's own
status model, and rfsBackfillStatusLib.sh's patches, run against a stand-in kubectl.
"""
import json
import os
import shutil
import stat
import subprocess
from pathlib import Path

import pytest

from console_link.models.backfill_base import BackfillOverallStatus, ShardFailedDocuments
from console_link.models.step_state import StepStateWithPause

REPO_ROOT = Path(__file__).resolve().parents[4]
SCRIPTS = REPO_ROOT / "orchestrationSpecs/packages/migration-workflow-templates/resources/scripts"
JQ_SCRIPT = SCRIPTS / "rfsBackfillStatus.jq"
STATUS_LIB = SCRIPTS / "rfsBackfillStatusLib.sh"

pytestmark = pytest.mark.skipif(shutil.which("jq") is None, reason="jq is not installed")


def document_backfill(console_json: dict, phase="Running", message="Document backfill is Running") -> dict:
    result = subprocess.run(
        ["jq", "-c", "--arg", "phase", phase, "--arg", "message", message,
         "--arg", "updatedAt", "2026-11-20T07:03:02Z", "-f", str(JQ_SCRIPT)],
        input=json.dumps(console_json), capture_output=True, text=True, check=True)
    return json.loads(result.stdout)


def console_status(**fields) -> dict:
    """What `console --json backfill status --deep-check` prints: the status model, plus the stream keys."""
    stream = {k: fields.pop(k) for k in ("failed_document_stream_location", "failed_documents_present")
              if k in fields}
    return {**BackfillOverallStatus(**fields).model_dump(mode="json"), **stream}


def test_maps_progress_and_failed_document_counts():
    backfill = document_backfill(console_status(
        status=StepStateWithPause.RUNNING, percentage_completed=40.0, eta_ms=87452.6,
        started="2026-11-20T07:05:04+00:00", shard_total=5, shard_complete=2, shard_in_progress=1,
        shard_waiting=2, documents_failed=8, documents_failed_non_retryable=7,
        documents_failed_retries_exhausted=1,
        shards_with_failures=[ShardFailedDocuments(index="idx-b", shard=0, non_retryable=5, retries_exhausted=0),
                              ShardFailedDocuments(index="idx-a", shard=0, non_retryable=2, retries_exhausted=1)],
        failed_document_stream_location="s3://bucket/prefix/session=abc/", failed_documents_present=True))

    assert backfill["phase"] == "Running" and backfill["updatedAt"] == "2026-11-20T07:03:02Z"
    assert backfill["summary"] == {
        "percentageCompleted": 40.0, "etaMs": 87452.6, "started": "2026-11-20T07:05:04+00:00", "finished": None,
        "shardsTotal": 5, "shardsMigrated": 2, "shardsInProgress": 1, "shardsWaiting": 2,
        "documentsFailed": 8, "documentsFailedNonRetryable": 7, "documentsFailedRetriesExhausted": 1,
    }
    assert backfill["failures"] == {
        "shardCount": 2,
        "shards": [{"index": "idx-b", "shard": 0, "nonRetryable": 5, "retriesExhausted": 0},
                   {"index": "idx-a", "shard": 0, "nonRetryable": 2, "retriesExhausted": 1}],
        "indexCount": 2,
        "indices": [{"index": "idx-b", "nonRetryable": 5, "retriesExhausted": 0},
                    {"index": "idx-a", "nonRetryable": 2, "retriesExhausted": 1}],
        "streamLocation": "s3://bucket/prefix/session=abc/",
        "streamHasRecords": True,
    }


def test_keeps_the_20_shards_and_indices_with_the_most_failures_and_counts_all_of_them():
    shards = [ShardFailedDocuments(index=f"idx-{i % 30}", shard=i, non_retryable=100 - i, retries_exhausted=0)
              for i in range(45)]
    failures = document_backfill(console_status(
        status=StepStateWithPause.RUNNING, percentage_completed=50.0, shards_with_failures=shards))["failures"]

    assert (failures["shardCount"], len(failures["shards"])) == (45, 20)
    assert failures["shards"][0] == {"index": "idx-0", "shard": 0, "nonRetryable": 100, "retriesExhausted": 0}
    assert (failures["indexCount"], len(failures["indices"])) == (30, 20)
    # idx-0 has shards 0 and 30 (100 + 70), more than any other index.
    assert failures["indices"][0] == {"index": "idx-0", "nonRetryable": 170, "retriesExhausted": 0}


def test_without_a_status_result_reports_no_progress_and_unknown_failures():
    backfill = document_backfill({}, phase="Unknown", message="Status check is not available yet")

    assert backfill["summary"]["shardsTotal"] == 0
    assert backfill["summary"]["documentsFailed"] is None
    assert backfill["failures"] == {"shardCount": 0, "shards": [], "indexCount": 0, "indices": [],
                                    "streamLocation": None, "streamHasRecords": None}


def test_both_views_show_the_failed_documents_the_monitor_writes():
    """Console status -> the monitor's mapping -> `workflow status`'s resource view and step view."""
    from console_link.workflow.resource_tree import ResourceNode, format_live_status
    from console_link.workflow.tree_utils import _format_snapshot_migration_backfill_status

    shards = [ShardFailedDocuments(index="logs", shard=i, non_retryable=25 - i, retries_exhausted=0)
              for i in range(22)]
    backfill = document_backfill(console_status(
        status=StepStateWithPause.RUNNING, percentage_completed=40.0, shard_total=50, shard_complete=20,
        documents_failed=sum(25 - i for i in range(22)), documents_failed_non_retryable=319,
        documents_failed_retries_exhausted=0, shards_with_failures=shards,
        failed_document_stream_location="s3://bucket/prefix/session=abc/", failed_documents_present=True))
    resource = ResourceNode(name="sm-0", plural="snapshotmigrations", phase="Running", depends_on=[], spec={},
                            status={"documentBackfill": backfill})

    summary, details = format_live_status(resource)

    assert summary == "Backfill status: 40%, shards 20/50, failed 319"
    assert "failed documents: 319 (non-retryable 319, retries exhausted 0)" in details
    shard_lines = [d for d in details if d.startswith("failed in ")]
    assert (len(shard_lines), shard_lines[0], shard_lines[-1]) == (
        20, "failed in logs shard 0: 25", "failed in logs shard 19: 6")
    assert "... and 2 more shards with failures (console backfill status --deep-check lists them)" in details
    assert "failed document stream: s3://bucket/prefix/session=abc/" in details
    assert "failed 319)" in _format_snapshot_migration_backfill_status(backfill)


def test_views_say_nothing_about_failures_when_none_were_counted():
    from console_link.workflow.resource_tree import ResourceNode, format_live_status
    from console_link.workflow.tree_utils import _format_snapshot_migration_backfill_status

    backfill = document_backfill(console_status(
        status=StepStateWithPause.RUNNING, percentage_completed=40.0, shard_total=5, shard_complete=2,
        documents_failed=0, documents_failed_non_retryable=0, documents_failed_retries_exhausted=0,
        shards_with_failures=[]))
    resource = ResourceNode(name="sm-0", plural="snapshotmigrations", phase="Running", depends_on=[], spec={},
                            status={"documentBackfill": backfill})

    summary, details = format_live_status(resource)

    assert "failed" not in summary and not any("failed" in d for d in details)
    assert "failed" not in _format_snapshot_migration_backfill_status(backfill)


def run_status_lib(tmp_path: Path, script: str, fail_json_patch: bool):
    """Run rfsBackfillStatusLib.sh with a kubectl that records each call and, optionally, rejects JSON patches
    (as it does before status exists)."""
    calls = tmp_path / "calls.jsonl"
    kubectl = tmp_path / "kubectl"
    reject_json_patch = 'case " $* " in *" --type=json "*) exit 1;; esac\n' if fail_json_patch else ""
    kubectl.write_text(
        "#!/usr/bin/env bash\n"
        f"python3 -c 'import json,sys; print(json.dumps(sys.argv[1:]))' \"$@\" >> {calls}\n"
        f"{reject_json_patch}exit 0\n")
    kubectl.chmod(kubectl.stat().st_mode | stat.S_IEXEC)
    env = {**os.environ, "PATH": f"{tmp_path}:{os.environ['PATH']}", "SNAPSHOT_MIGRATION_NAME": "sm-0",
           "RFS_SCRIPT_DIR": str(SCRIPTS)}
    subprocess.run(["bash", "-c", f'set -eu; . "{STATUS_LIB}"; {script}'], env=env, check=True)
    return [json.loads(line) for line in calls.read_text().splitlines()]


def patch_of(call: list) -> tuple:
    return call[call.index("--type") + 1] if "--type" in call else next(
        a.split("=", 1)[1] for a in call if a.startswith("--type=")), json.loads(call[call.index("-p") + 1])


def test_replaces_document_backfill_whole_with_a_json_patch(tmp_path):
    calls = run_status_lib(tmp_path, 'replace_document_backfill "$(document_backfill Pending installed)" '
                                     '\'[{"op":"add","path":"/status/phase","value":"Completed"}]\'',
                           fail_json_patch=False)

    assert len(calls) == 1
    patch_type, patch = patch_of(calls[0])
    assert patch_type == "json"
    assert [(op["op"], op["path"]) for op in patch] == [("add", "/status/phase"), ("add", "/status/documentBackfill")]
    assert patch[1]["value"]["phase"] == "Pending" and patch[1]["value"]["message"] == "installed"


def test_falls_back_to_a_merge_patch_before_status_exists(tmp_path):
    calls = run_status_lib(tmp_path, 'replace_document_backfill "$(document_backfill Pending installed)" '
                                     '\'[{"op":"add","path":"/status/phase","value":"Completed"}]\'',
                           fail_json_patch=True)

    assert [patch_of(call)[0] for call in calls] == ["json", "merge"]
    merge = patch_of(calls[1])[1]
    assert merge["status"]["phase"] == "Completed"
    assert merge["status"]["documentBackfill"]["phase"] == "Pending"
