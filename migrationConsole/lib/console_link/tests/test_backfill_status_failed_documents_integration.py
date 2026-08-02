"""Integration tests for the failed-document signal in `console backfill status`.

A backfill whose shards have all finished reports ``Completed`` — unless documents failed,
in which case it reports ``CompletedWithErrors``. These tests exercise that whole path with
nothing stubbed in between: real gzipped NDJSON failure records in a moto-backed S3 bucket
(laid out under the same ``session=/index=/worker=`` keys the RFS S3 sink writes), a real
``.migrations_working_state`` index in a live OpenSearch container, and the real
``console backfill status`` command.

Only the two lookups that would need a live control plane are replaced: the ECS deployment
status, and the Kubernetes SnapshotMigration listing that ``load_config`` resolves the
bucket/prefix/session from.
"""
from __future__ import annotations

import gzip
import io
import json
import os
import tempfile
from typing import List

import boto3
import pytest
import yaml
from click.testing import CliRunner
from moto import mock_aws

import console_link.cli as cli_module
from console_link.cli import cli
from console_link.environment import Environment
from console_link.models.ecs_service import ECSService
from console_link.models.utils import DeploymentStatus
from tests.search_containers import SearchContainer, Version
from tests.test_backfill_rfs_queries import (
    create_all_completed_working_state,
    create_working_state_index,
)

TEST_VERSION = Version("OPENSEARCH", 2, 19, 1)

BUCKET = "failed-document-stream-integ"
PREFIX = "rfs-failed-document-stream/"
# The session id is the owning SnapshotMigration's UID; it selects the S3 prefix to read.
SESSION_ID = "3f8b1c2a-1111-4c4d-9e77-0123456789ab"
REGION = "us-east-1"
SESSION_URI = f"s3://{BUCKET}/{PREFIX}session={SESSION_ID}/"


# ---------- seeding helpers -------------------------------------------------

def _gz(text: str) -> bytes:
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb") as gz:
        gz.write(text.encode("utf-8"))
    return buf.getvalue()


def _ndjson(records: List[dict]) -> str:
    return "\n".join(json.dumps(r) for r in records) + "\n"


def _failure_record(document_id: str, target_index: str = "index0") -> dict:
    """Shaped like FailedDocumentStreamRecord, which is what RFS workers emit."""
    return {
        "sessionId": SESSION_ID,
        "workerId": "ma-bulk-document-loader-88c85578f-rfn5c",
        "workItemId": f"{target_index}__0__0",
        "targetIndex": target_index,
        "documentId": document_id,
        "failureType": "mapper_parsing_exception",
        "failureClass": "NON_RETRYABLE",
        "timestamp": "2026-07-21T15:38:47Z",
        "requestItem": {"index": {"_index": target_index, "_id": document_id}},
        "responseItem": {"index": {"status": 400,
                                   "error": {"type": "mapper_parsing_exception",
                                             "reason": "failed to parse field [year]"}}},
    }


def _put_failure_records(s3, records: List[dict], target_index: str = "index0",
                         worker: str = "worker-1", seq: int = 0) -> None:
    """Write one NDJSON.gz object using the sink's real key layout."""
    key = (f"{PREFIX}session={SESSION_ID}/index={target_index}/worker={worker}/"
           f"failed-document-stream-20260721T153847Z-{seq}.ndjson.gz")
    s3.put_object(Bucket=BUCKET, Key=key, Body=_gz(_ndjson(records)))


def _snapshot_migration(bucket: str = BUCKET) -> dict:
    """The SnapshotMigration CR the console reads failed-document-stream config from."""
    return {
        "metadata": {"name": "migration-0", "uid": SESSION_ID},
        "spec": {
            "documentBackfillFailedDocumentStreamS3Bucket": bucket,
            "documentBackfillFailedDocumentStreamS3Prefix": PREFIX,
            "documentBackfillFailedDocumentStreamS3Region": REGION,
        },
    }


# ---------- fixtures --------------------------------------------------------

@pytest.fixture
def runner():
    return CliRunner()


@pytest.fixture(scope="module")
def completed_backfill_config():
    """A live target cluster whose working state says every shard finished, plus the
    services.yaml pointing the console at it. Returns the config file path."""
    container = SearchContainer(TEST_VERSION, mem_limit="3G")
    container.start()
    config_path = None
    try:
        endpoint = f"http://{container.get_container_host_ip()}:{container.get_exposed_port(9200)}"
        services_config = {
            "target_cluster": {"endpoint": endpoint, "allow_insecure": True, "no_auth": {}},
            "backfill": {
                "reindex_from_snapshot": {
                    "ecs": {"cluster_name": "migration-cluster", "service_name": "rfs-service"}
                }
            },
        }
        with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False) as temp_config:
            yaml.dump(services_config, temp_config)
            config_path = temp_config.name

        target_cluster = Environment(config_file=config_path).target_cluster
        create_working_state_index(target_cluster)
        create_all_completed_working_state(target_cluster)

        yield config_path
    finally:
        container.stop()
        if config_path:
            os.remove(config_path)


@pytest.fixture
def aws_credentials():
    os.environ["AWS_ACCESS_KEY_ID"] = "testing"
    os.environ["AWS_SECRET_ACCESS_KEY"] = "testing"
    os.environ["AWS_SECURITY_TOKEN"] = "testing"
    os.environ["AWS_SESSION_TOKEN"] = "testing"
    os.environ["AWS_DEFAULT_REGION"] = REGION


@pytest.fixture
def failed_document_stream(aws_credentials, mocker):
    """An empty failed document stream the console will actually read from. Yields the S3
    client so a test can seed failures into it."""
    # No workers left running, as on a finished backfill.
    mocker.patch.object(ECSService, "get_instance_statuses", autospec=True,
                        return_value=DeploymentStatus(desired=0, running=0, pending=0))
    with mock_aws():
        s3 = boto3.client("s3", region_name=REGION)
        s3.create_bucket(Bucket=BUCKET)
        mocker.patch.object(cli_module.failed_document_stream_, "_list_snapshot_migrations",
                            return_value=[_snapshot_migration()])
        yield s3


# ---------- plain-text status ----------------------------------------------

@pytest.mark.slow
def test_deep_check_reports_completed_with_errors_when_documents_failed(
        runner, completed_backfill_config, failed_document_stream):
    _put_failure_records(failed_document_stream, [_failure_record("doc-1"), _failure_record("doc-2")])

    result = runner.invoke(cli, ["--config-file", completed_backfill_config,
                                 "backfill", "status", "--deep-check"],
                           catch_exceptions=False)

    assert result.exit_code == 0
    assert "Backfill status: CompletedWithErrors" in result.output
    assert f"failed document stream location: {SESSION_URI}" in result.output
    assert "Failed documents present: yes" in result.output
    # The shards themselves all finished — the errors come only from the failed documents.
    assert "Percent completed: 100.0%" in result.output


@pytest.mark.slow
def test_deep_check_reports_completed_when_stream_holds_no_failures(
        runner, completed_backfill_config, failed_document_stream):
    # Same backfill, nothing written to the stream.
    result = runner.invoke(cli, ["--config-file", completed_backfill_config,
                                 "backfill", "status", "--deep-check"],
                           catch_exceptions=False)

    assert result.exit_code == 0
    assert "Backfill status: Completed" in result.output
    assert "CompletedWithErrors" not in result.output
    assert "Failed documents present: no" in result.output


@pytest.mark.slow
def test_deep_check_reports_failures_from_any_index_partition(
        runner, completed_backfill_config, failed_document_stream):
    # Records are multiplexed per target index and worker; the read spans the whole session
    # prefix, so a failure under any partition must surface.
    _put_failure_records(failed_document_stream, [_failure_record("doc-a", target_index="index0")],
                         target_index="index0", worker="worker-1")
    _put_failure_records(failed_document_stream, [_failure_record("doc-b", target_index="index1")],
                         target_index="index1", worker="worker-2")

    result = runner.invoke(cli, ["--config-file", completed_backfill_config,
                                 "backfill", "status", "--deep-check"],
                           catch_exceptions=False)

    assert result.exit_code == 0
    assert "Backfill status: CompletedWithErrors" in result.output
    assert "Failed documents present: yes" in result.output


@pytest.mark.slow
def test_deep_check_ignores_failures_from_another_session(
        runner, completed_backfill_config, failed_document_stream):
    # A previous backfill's failures live under a different session= prefix and must not
    # mark this one as errored.
    failed_document_stream.put_object(
        Bucket=BUCKET,
        Key=f"{PREFIX}session=some-other-run/index=index0/worker=w/failed-document-stream-0.ndjson.gz",
        Body=_gz(_ndjson([_failure_record("doc-from-earlier-run")])))

    result = runner.invoke(cli, ["--config-file", completed_backfill_config,
                                 "backfill", "status", "--deep-check"],
                           catch_exceptions=False)

    assert result.exit_code == 0
    assert "Backfill status: Completed" in result.output
    assert "CompletedWithErrors" not in result.output
    assert "Failed documents present: no" in result.output


@pytest.mark.slow
def test_deep_check_reports_unavailable_when_stream_cannot_be_read(
        runner, completed_backfill_config, failed_document_stream, mocker):
    # Bucket doesn't exist (misconfiguration / missing permissions): S3 raises, and an
    # unreadable stream must not be guessed either way — the status stays Completed.
    mocker.patch.object(cli_module.failed_document_stream_, "_list_snapshot_migrations",
                        return_value=[_snapshot_migration(bucket="no-such-bucket")])

    result = runner.invoke(cli, ["--config-file", completed_backfill_config,
                                 "backfill", "status", "--deep-check"],
                           catch_exceptions=False)

    assert result.exit_code == 0
    assert "Backfill status: Completed" in result.output
    assert "CompletedWithErrors" not in result.output
    assert "Failed documents present: unavailable" in result.output


# ---------- JSON status -----------------------------------------------------

@pytest.mark.slow
def test_json_deep_check_reports_failed_documents_present(
        runner, completed_backfill_config, failed_document_stream):
    # The at-least-once stream may re-emit a document; presence is unaffected.
    _put_failure_records(failed_document_stream, [_failure_record("doc-1"), _failure_record("doc-1")])

    result = runner.invoke(cli, ["--config-file", completed_backfill_config,
                                 "--json", "backfill", "status", "--deep-check"],
                           catch_exceptions=False)

    assert result.exit_code == 0
    payload = json.loads(result.output)
    assert payload["status"] == "CompletedWithErrors"
    assert payload["failed_documents_present"] is True
    assert payload["failed_document_stream_location"] == SESSION_URI
    assert payload["percentage_completed"] == 100.0
    assert payload["shard_complete"] == payload["shard_total"]


@pytest.mark.slow
def test_json_deep_check_reports_completed_without_failures(
        runner, completed_backfill_config, failed_document_stream):
    result = runner.invoke(cli, ["--config-file", completed_backfill_config,
                                 "--json", "backfill", "status", "--deep-check"],
                           catch_exceptions=False)

    assert result.exit_code == 0
    payload = json.loads(result.output)
    assert payload["status"] == "Completed"
    assert payload["failed_documents_present"] is False
