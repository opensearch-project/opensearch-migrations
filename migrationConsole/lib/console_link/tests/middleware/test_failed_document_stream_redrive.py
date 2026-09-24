"""Unit tests for sealing and redriving a failed document stream.

Covers the part the console owns: closing the session, working out what would be written, and
shaping the workflow config. Runs against moto, so real S3 conditional-write semantics apply.
"""
from __future__ import annotations

import gzip
import io
import json
import os

import boto3
import pytest
from moto import mock_aws

import console_link.middleware.failed_document_stream as fds

BUCKET = "failure-bucket"
PREFIX = "rfs-failed-document-stream/"
SESSION = "session-1"
ORDERS = "orders-2024"


@pytest.fixture(autouse=True)
def aws_credentials():
    os.environ.setdefault("AWS_ACCESS_KEY_ID", "testing")
    os.environ.setdefault("AWS_SECRET_ACCESS_KEY", "testing")
    os.environ.setdefault("AWS_DEFAULT_REGION", "us-east-1")


def _cfg() -> fds.FailedDocumentStreamConfig:
    return fds.FailedDocumentStreamConfig(
        bucket=BUCKET, prefix=PREFIX, session_id=SESSION, region="us-east-1")


def _record(index: str, document_id, failure_class="NON_RETRYABLE") -> dict:
    """Shaped the way S3FailedDocumentStreamSink writes it."""
    operation = {"_index": index}
    if document_id is not None:
        operation["_id"] = document_id
    record = {
        "sessionId": SESSION,
        "targetIndex": index,
        "failureClass": failure_class,
        "failureType": "mapper_parsing_exception",
        "timestamp": "2026-05-14T12:00:00Z",
        "requestItem": {
            "operation_type": "index",
            "include_document": True,
            "operation": operation,
            "document": {"field": f"value-for-{document_id}"},
        },
        "responseItem": {"status": 400},
    }
    if document_id is not None:
        record["documentId"] = document_id
    return record


def _gz(records) -> bytes:
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb") as gz:
        gz.write(("\n".join(json.dumps(r) for r in records) + "\n").encode("utf-8"))
    return buf.getvalue()


def _put_rotation(client, index, worker, seq, records):
    key = (f"{PREFIX}session={SESSION}/index={index}/worker={worker}"
           f"/failed-document-stream-20260514T120000Z-{seq}.ndjson.gz")
    client.put_object(Bucket=BUCKET, Key=key, Body=_gz(records))
    return key


@pytest.fixture
def s3():
    with mock_aws():
        client = boto3.client("s3", region_name="us-east-1")
        client.create_bucket(Bucket=BUCKET)
        yield client


class TestSealing:

    def test_groups_objects_by_index_and_worker(self, s3):
        _put_rotation(s3, ORDERS, "worker-a", 1, [_record(ORDERS, "doc-1")])
        _put_rotation(s3, ORDERS, "worker-b", 1, [_record(ORDERS, "doc-2")])
        _put_rotation(s3, "users", "worker-a", 1, [_record("users", "user-1")])

        manifest = fds.seal(_cfg(), client=s3)["manifest"]

        assert [c["name"] for c in manifest["collections"]] == [ORDERS, "users"]
        orders = manifest["collections"][0]
        assert [p["name"] for p in orders["partitions"]] == ["worker-a", "worker-b"]

    def test_a_second_sealer_agrees_rather_than_overwriting(self, s3):
        _put_rotation(s3, ORDERS, "worker-a", 1, [_record(ORDERS, "doc-1")])

        first = fds.seal(_cfg(), client=s3)
        second = fds.seal(_cfg(), client=s3)

        assert first["published"] is True
        assert second["published"] is False
        assert second["digest"] == first["digest"]

    def test_refuses_to_seal_a_session_that_kept_growing(self, s3):
        _put_rotation(s3, ORDERS, "worker-a", 1, [_record(ORDERS, "doc-1")])
        fds.seal(_cfg(), client=s3)
        _put_rotation(s3, ORDERS, "worker-late", 1, [_record(ORDERS, "doc-9")])

        with pytest.raises(fds.SessionSealMismatch, match="A seal is permanent"):
            fds.seal(_cfg(), client=s3)

    def test_the_manifest_is_not_counted_as_a_record_object(self, s3):
        _put_rotation(s3, ORDERS, "worker-a", 1, [_record(ORDERS, "doc-1")])
        fds.seal(_cfg(), client=s3)

        # Sealing again lists the manifest too.
        assert fds.count(_cfg()) == 1
        assert fds.seal(_cfg(), client=s3)["published"] is False

    def test_read_manifest_returns_nothing_for_an_unsealed_session(self, s3):
        _put_rotation(s3, ORDERS, "worker-a", 1, [_record(ORDERS, "doc-1")])

        assert fds.read_manifest(_cfg(), client=s3) is None
        assert fds.is_sealed(_cfg(), client=s3) is False

    def test_manifest_encoding_matches_the_java_sealer(self):
        """Pins the canonical encoding a worker and this command must agree on.

        Both can seal the same session and the loser compares digests, so a difference here would
        make a consistent session look like one still being written.
        SessionManifestCrossLanguageTest asserts the same literal.
        """
        manifest = {
            "schemaVersion": 1,
            "sessionId": "session-á",
            "collections": [
                {"name": "orders-2024", "partitions": [
                    {"name": "worker-1", "objectKeys": ["p/index=orders-2024/worker-1/a.ndjson.gz"]},
                    {"name": "worker-10", "objectKeys": [
                        'p/index=orders-2024/worker-10/a"quote.ndjson.gz',
                        "p/index=orders-2024/worker-10/z.ndjson.gz"]}]},
                {"name": "users", "partitions": [
                    {"name": "worker-2", "objectKeys": ["p/index=users/worker-2/b.ndjson.gz"]}]}]}
        expected = (
            '{"schemaVersion":1,"sessionId":"session-á","collections":['
            '{"name":"orders-2024","partitions":['
            '{"name":"worker-1","objectKeys":["p/index=orders-2024/worker-1/a.ndjson.gz"]},'
            '{"name":"worker-10","objectKeys":['
            '"p/index=orders-2024/worker-10/a\\"quote.ndjson.gz",'
            '"p/index=orders-2024/worker-10/z.ndjson.gz"]}]},'
            '{"name":"users","partitions":['
            '{"name":"worker-2","objectKeys":["p/index=users/worker-2/b.ndjson.gz"]}]}]}')

        canonical = fds.canonical_manifest_bytes(manifest)

        assert canonical.decode("utf-8") == expected
        assert fds.manifest_digest(canonical) == \
            "542cd39d37cb446b2fa43f3554a85fbe2571d16f682205f157f2d61609fcb714"

    def test_build_manifest_sorts_regardless_of_listing_order(self, s3):
        _put_rotation(s3, "zebra", "worker-9", 1, [_record("zebra", "z")])
        _put_rotation(s3, "alpha", "worker-1", 1, [_record("alpha", "a")])

        manifest = fds.build_manifest(_cfg(), client=s3)

        assert [c["name"] for c in manifest["collections"]] == ["alpha", "zebra"]


class TestPlanRedrive:

    def test_counts_what_would_be_written_per_index(self, s3):
        _put_rotation(s3, ORDERS, "worker-a", 1,
                      [_record(ORDERS, "doc-1"), _record(ORDERS, "doc-2")])
        _put_rotation(s3, "users", "worker-a", 1, [_record("users", "user-1")])
        manifest = fds.seal(_cfg(), client=s3)["manifest"]

        plan = fds.plan_redrive(_cfg(), manifest, client=s3)

        assert plan["total"] == 3
        assert plan["indices"] == {ORDERS: 2, "users": 1}

    def test_filters_by_index(self, s3):
        _put_rotation(s3, ORDERS, "worker-a", 1, [_record(ORDERS, "doc-1")])
        _put_rotation(s3, "users", "worker-a", 1, [_record("users", "user-1")])
        manifest = fds.seal(_cfg(), client=s3)["manifest"]

        plan = fds.plan_redrive(_cfg(), manifest, indices=[ORDERS], client=s3)

        assert plan["indices"] == {ORDERS: 1}

    def test_filters_by_failure_class(self, s3):
        _put_rotation(s3, ORDERS, "worker-a", 1, [
            _record(ORDERS, "doc-1", "NON_RETRYABLE"),
            _record(ORDERS, "doc-2", "RETRYABLE_EXHAUSTED")])
        manifest = fds.seal(_cfg(), client=s3)["manifest"]

        plan = fds.plan_redrive(_cfg(), manifest, failure_classes=["retryable_exhausted"], client=s3)

        assert plan["total"] == 1
        assert plan["documents"][0]["documentId"] == "doc-2"

    def test_limit_caps_the_sample_but_not_the_count(self, s3):
        _put_rotation(s3, ORDERS, "worker-a", 1,
                      [_record(ORDERS, f"doc-{i}") for i in range(5)])
        manifest = fds.seal(_cfg(), client=s3)["manifest"]

        plan = fds.plan_redrive(_cfg(), manifest, limit=2, client=s3)

        assert len(plan["documents"]) == 2
        assert plan["total"] == 5, "the count is what would be written, not what was listed"

    def test_reports_documents_that_have_no_id(self, s3):
        _put_rotation(s3, ORDERS, "worker-a", 1,
                      [_record(ORDERS, "doc-1"), _record(ORDERS, None)])
        manifest = fds.seal(_cfg(), client=s3)["manifest"]

        plan = fds.plan_redrive(_cfg(), manifest, client=s3)

        assert plan["skipped_without_id"] == 1

    def test_reads_only_what_the_manifest_names(self, s3):
        _put_rotation(s3, ORDERS, "worker-a", 1, [_record(ORDERS, "doc-1")])
        manifest = fds.seal(_cfg(), client=s3)["manifest"]
        # Appeared after the seal, so not part of the session.
        _put_rotation(s3, ORDERS, "worker-late", 1, [_record(ORDERS, "doc-late")])

        plan = fds.plan_redrive(_cfg(), manifest, client=s3)

        assert plan["total"] == 1

    def test_rejects_an_unknown_failure_class(self, s3):
        with pytest.raises(ValueError, match="Unknown failure class"):
            fds.plan_redrive(_cfg(), {"collections": []}, failure_classes=["NOPE"], client=s3)


class TestSourceConfig:

    def test_names_the_stream_root_not_the_session_prefix(self):
        # Composing the layout below the root is the source's job.
        assert fds.stream_uri(_cfg()) == f"s3://{BUCKET}/rfs-failed-document-stream"

    def test_handles_a_stream_at_the_bucket_root(self):
        cfg = fds.FailedDocumentStreamConfig(bucket=BUCKET, prefix="", session_id=SESSION)
        assert fds.stream_uri(cfg) == f"s3://{BUCKET}"

    def test_carries_the_filters_and_the_region(self):
        config = fds.build_source_config(_cfg(), indices=[ORDERS], failure_classes=["non_retryable"])

        assert config == {
            "streamUri": f"s3://{BUCKET}/rfs-failed-document-stream",
            "sessionId": SESSION,
            "indexAllowlist": [ORDERS],
            "failureClasses": ["NON_RETRYABLE"],
            "s3Region": "us-east-1",
        }


class TestBuildRedriveConfig:
    """Selecting the backfill that produced the session, and pointing only that one at the stream."""

    IDENTITY = fds.MigrationIdentity(
        name="sm-1", migration_label="docs", source_label="source", target_label="target",
        target_endpoint="http://target:9200")

    @staticmethod
    def _config(labels=("docs",), target_endpoint="http://target:9200", source="source",
                target="target"):
        return {
            "sourceClusters": {source: {"version": "ES 7.10.2", "endpoint": "http://source:9200"}},
            "targetClusters": {target: {"endpoint": target_endpoint}},
            "snapshotMigrationConfigs": [{
                "fromSource": source,
                "toTarget": target,
                "perSnapshotConfig": {
                    "snap1": [
                        {"label": label, "documentBackfillConfig": {"maxConnections": 5}}
                        for label in labels
                    ]
                },
            }],
        }

    @staticmethod
    def _backfill(config, position=0):
        return (config["snapshotMigrationConfigs"][0]["perSnapshotConfig"]["snap1"][position]
                ["documentBackfillConfig"])

    def test_points_the_backfill_at_the_failure_stream(self):
        source_config = fds.build_source_config(_cfg())

        redrive = fds.build_redrive_config(self._config(), source_config, self.IDENTITY)

        backfill = self._backfill(redrive)
        assert backfill["sourceKind"] == "failed-document-stream"
        assert json.loads(backfill["sourceConfig"]) == source_config

    def test_leaves_the_rest_of_the_configuration_alone(self):
        # Same target, transforms and tuning as the run that produced the failures.
        original = self._config()

        redrive = fds.build_redrive_config(original, fds.build_source_config(_cfg()), self.IDENTITY)

        assert self._backfill(redrive)["maxConnections"] == 5
        assert redrive["targetClusters"] == original["targetClusters"]

    def test_does_not_mutate_the_saved_configuration(self):
        original = self._config()

        fds.build_redrive_config(original, fds.build_source_config(_cfg()), self.IDENTITY)

        assert "sourceKind" not in self._backfill(original)

    def test_opts_into_sending_documents_with_no_id(self):
        redrive = fds.build_redrive_config(
            self._config(), fds.build_source_config(_cfg()), self.IDENTITY,
            allow_missing_document_ids=True)

        assert self._backfill(redrive)["allowMissingDocumentIds"] is True

    def test_leaves_the_flag_out_by_default(self):
        redrive = fds.build_redrive_config(
            self._config(), fds.build_source_config(_cfg()), self.IDENTITY)

        assert "allowMissingDocumentIds" not in self._backfill(redrive)

    def test_selects_the_backfill_that_produced_the_session(self):
        # Only the backfill the migration names is redriven.
        redrive = fds.build_redrive_config(
            self._config(labels=("other", "docs")), fds.build_source_config(_cfg()), self.IDENTITY)

        assert "sourceKind" not in self._backfill(redrive, 0)
        assert self._backfill(redrive, 1)["sourceKind"] == "failed-document-stream"

    def test_refuses_a_configuration_that_does_not_declare_this_backfill(self):
        # A different or edited configuration.
        with pytest.raises(fds.RedriveConfigError, match="--config-session"):
            fds.build_redrive_config(
                self._config(labels=("something-else",)), fds.build_source_config(_cfg()),
                self.IDENTITY)

    def test_refuses_a_configuration_for_a_different_source_or_target(self):
        with pytest.raises(fds.RedriveConfigError, match="no backfill matching"):
            fds.build_redrive_config(
                self._config(source="other-source"), fds.build_source_config(_cfg()), self.IDENTITY)

    def test_refuses_a_target_that_has_been_repointed_since_the_run(self):
        # The backfill still matches, but its target now addresses a different cluster.
        with pytest.raises(fds.RedriveConfigError, match="different cluster"):
            fds.build_redrive_config(
                self._config(target_endpoint="http://somewhere-else:9200"),
                fds.build_source_config(_cfg()), self.IDENTITY)

    def test_names_what_it_found_when_nothing_matches(self):
        with pytest.raises(fds.RedriveConfigError, match="source/target/something-else"):
            fds.build_redrive_config(
                self._config(labels=("something-else",)), fds.build_source_config(_cfg()),
                self.IDENTITY)

    def test_rejects_a_configuration_with_no_backfill(self):
        with pytest.raises(fds.RedriveConfigError, match="declares no document backfill"):
            fds.build_redrive_config({"targetClusters": {}}, fds.build_source_config(_cfg()),
                                     self.IDENTITY)

    def test_fills_in_an_empty_backfill_block(self):
        config = self._config()
        config["snapshotMigrationConfigs"][0]["perSnapshotConfig"]["snap1"][0]["documentBackfillConfig"] = None

        redrive = fds.build_redrive_config(config, fds.build_source_config(_cfg()), self.IDENTITY)

        assert self._backfill(redrive)["sourceKind"] == "failed-document-stream"
