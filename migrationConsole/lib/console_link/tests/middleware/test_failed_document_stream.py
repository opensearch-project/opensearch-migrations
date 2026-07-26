"""Unit tests for console_link.middleware.failed_document_stream.

Coverage targets:
  * FailedDocumentStreamConfig properties (session_prefix, location_uri)
  * load_config reads bucket/prefix/region/endpoint from the SnapshotMigration spec; session = its UID
  * Both FailedDocumentStreamNotConfigured branches (no bucket / no session)
  * _s3_client region/endpoint selection
  * list_records — sorting, limit, malformed gzip/JSON skipping
  * count — line counting, malformed gzip skipping
  * delete_session — 1000-key batching boundary
  * safe_count — swallowing ClientError / BotoCoreError
  * location helper

S3 calls are mocked via pytest-mock; no moto / live S3 / kubernetes API needed.
"""
from __future__ import annotations

import gzip
import io
import json
from typing import List
from unittest.mock import MagicMock

import pytest
from botocore.exceptions import BotoCoreError, ClientError

import console_link.middleware.failed_document_stream as failed_document_stream


# ---------- shared fixtures / helpers --------------------------------------

def _gz(text: str) -> bytes:
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb") as gz:
        gz.write(text.encode("utf-8"))
    return buf.getvalue()


def _ndjson(records: List[dict]) -> str:
    return "\n".join(json.dumps(r) for r in records) + "\n"


def _make_s3_mock(objects_with_bodies: List[tuple]) -> MagicMock:
    """Build a MagicMock S3 client whose paginator yields the given (Key, body)
    objects and whose get_object returns the matching body."""
    client = MagicMock()
    paginator = MagicMock()
    pages = [{"Contents": [{"Key": key} for (key, _body) in objects_with_bodies]}]
    paginator.paginate.return_value = iter(pages)
    client.get_paginator.return_value = paginator

    body_by_key = {key: body for (key, body) in objects_with_bodies}

    def fake_get_object(Bucket, Key):
        body = MagicMock()
        body.read.return_value = body_by_key[Key]
        return {"Body": body}

    client.get_object.side_effect = fake_get_object
    return client


def _config(**overrides) -> failed_document_stream.FailedDocumentStreamConfig:
    base = dict(bucket="b", prefix="rfs-failed-document-stream/", session_id="s1", region=None)
    base.update(overrides)
    return failed_document_stream.FailedDocumentStreamConfig(**base)


# ---------- FailedDocumentStreamConfig -------------------------------------------------------

class TestFailedDocumentStreamConfigProperties:
    def test_session_prefix_joins_prefix_and_session(self):
        cfg = _config(prefix="rfs-failed-document-stream/", session_id="sess-A")
        assert cfg.session_prefix == "rfs-failed-document-stream/session=sess-A/"

    def test_location_uri_builds_s3_url(self):
        cfg = _config(bucket="bk", prefix="dir/", session_id="x")
        assert cfg.location_uri == "s3://bk/dir/session=x/"

    def test_location_helper_returns_location_uri(self):
        cfg = _config()
        assert failed_document_stream.location(cfg) == cfg.location_uri


# ---------- _s3_client ------------------------------------------------------

class TestS3ClientFactory:
    def test_uses_region_when_set(self, mocker):
        boto_mock = mocker.patch("console_link.middleware.failed_document_stream.boto3.client")
        failed_document_stream._s3_client(_config(region="eu-central-1"))
        boto_mock.assert_called_once_with("s3", region_name="eu-central-1")

    def test_no_region_arg_when_unset(self, mocker):
        boto_mock = mocker.patch("console_link.middleware.failed_document_stream.boto3.client")
        failed_document_stream._s3_client(_config(region=None))
        boto_mock.assert_called_once_with("s3")

    def test_uses_endpoint_when_set(self, mocker):
        boto_mock = mocker.patch("console_link.middleware.failed_document_stream.boto3.client")
        failed_document_stream._s3_client(_config(region="us-east-1", endpoint="https://s3.local"))
        boto_mock.assert_called_once_with("s3", region_name="us-east-1", endpoint_url="https://s3.local")


# ---------- load_config -----------------------------------------------------

class TestLoadConfig:
    @staticmethod
    def _set_migrations(monkeypatch, items):
        monkeypatch.setattr(failed_document_stream, "_list_snapshot_migrations", lambda: items)

    @staticmethod
    def _sm(name="m1", uid="uid-1", bucket="b", prefix=None, region=None, endpoint=None):
        spec = {"documentBackfillFailedDocumentStreamS3Bucket": bucket}
        if prefix is not None:
            spec["documentBackfillFailedDocumentStreamS3Prefix"] = prefix
        if region is not None:
            spec["documentBackfillFailedDocumentStreamS3Region"] = region
        if endpoint is not None:
            spec["documentBackfillFailedDocumentStreamS3Endpoint"] = endpoint
        return {"metadata": {"name": name, "uid": uid}, "spec": spec}

    def test_reads_config_from_spec_with_uid_as_session(self, monkeypatch):
        self._set_migrations(monkeypatch, [self._sm(
            uid="uid-xyz", bucket="from-cr", prefix="p/", region="ap-south-1", endpoint="https://s3.local")])
        cfg = failed_document_stream.load_config()
        assert cfg.bucket == "from-cr"
        assert cfg.session_id == "uid-xyz"   # session is the SnapshotMigration's own UID
        assert cfg.prefix == "p/"
        assert cfg.region == "ap-south-1"
        assert cfg.endpoint == "https://s3.local"
        assert cfg.location_uri == "s3://from-cr/p/session=uid-xyz/"

    def test_raises_when_bucket_absent(self, monkeypatch):
        self._set_migrations(monkeypatch, [self._sm(bucket="")])
        with pytest.raises(failed_document_stream.FailedDocumentStreamNotConfigured, match="bucket"):
            failed_document_stream.load_config()

    def test_migration_override_selects_by_name(self, monkeypatch):
        self._set_migrations(monkeypatch, [self._sm(name="a", uid="ua"), self._sm(name="b", uid="ub")])
        cfg = failed_document_stream.load_config(migration_override="b")
        assert cfg.session_id == "ub"

    def test_prefix_trailing_slash_appended(self, monkeypatch):
        self._set_migrations(monkeypatch, [self._sm(prefix="custom-prefix")])
        # Trailing slash must be appended so session= joins cleanly.
        assert failed_document_stream.load_config().prefix == "custom-prefix/"

    def test_prefix_defaults_when_absent(self, monkeypatch):
        self._set_migrations(monkeypatch, [self._sm()])
        assert failed_document_stream.load_config().prefix == "rfs-failed-document-stream/"

    def test_region_and_endpoint_optional(self, monkeypatch):
        self._set_migrations(monkeypatch, [self._sm()])
        cfg = failed_document_stream.load_config()
        assert cfg.region is None
        assert cfg.endpoint is None


# ---------- list_records ----------------------------------------------------

class TestListRecords:
    def test_returns_records_sorted_by_timestamp_then_doc_id(self, mocker):
        # Two NDJSON objects, deliberately out of order so we can observe the sort.
        obj1 = _gz(_ndjson([
            {"timestamp": "2026-05-20T00:00:00Z", "documentId": "z"},
            {"timestamp": "2026-05-01T00:00:00Z", "documentId": "a"},
        ]))
        obj2 = _gz(_ndjson([
            {"timestamp": "2026-05-10T00:00:00Z", "documentId": "m"},
        ]))
        s3 = _make_s3_mock([("a.gz", obj1), ("b.gz", obj2)])
        mocker.patch.object(failed_document_stream, "_s3_client", return_value=s3)

        result = failed_document_stream.list_records(_config())
        assert [r["documentId"] for r in result] == ["a", "m", "z"]

    def test_records_without_timestamp_sort_to_end(self, mocker):
        obj = _gz(_ndjson([
            {"documentId": "no-ts"},                     # no timestamp
            {"timestamp": "2026-01-01T00:00:00Z", "documentId": "ts-1"},
        ]))
        s3 = _make_s3_mock([("k.gz", obj)])
        mocker.patch.object(failed_document_stream, "_s3_client", return_value=s3)

        result = failed_document_stream.list_records(_config())
        assert [r["documentId"] for r in result] == ["ts-1", "no-ts"]

    def test_limit_caps_returned_records(self, mocker):
        obj = _gz(_ndjson([
            {"timestamp": f"2026-0{i + 1}-01T00:00:00Z", "documentId": str(i)} for i in range(5)
        ]))
        s3 = _make_s3_mock([("k.gz", obj)])
        mocker.patch.object(failed_document_stream, "_s3_client", return_value=s3)

        result = failed_document_stream.list_records(_config(), limit=2)
        assert len(result) == 2

    def test_skips_object_with_corrupt_gzip(self, mocker):
        good = _gz(_ndjson([{"timestamp": "t", "documentId": "ok"}]))
        bad = b"not gzipped at all"
        s3 = _make_s3_mock([("good.gz", good), ("bad.gz", bad)])
        mocker.patch.object(failed_document_stream, "_s3_client", return_value=s3)

        result = failed_document_stream.list_records(_config())
        # Bad object is skipped; the good one is still returned.
        assert [r["documentId"] for r in result] == ["ok"]

    def test_skips_malformed_ndjson_lines(self, mocker):
        # Two lines, the first one isn't valid JSON.
        body = _gz("not-json\n" + json.dumps({"timestamp": "t", "documentId": "ok"}) + "\n")
        s3 = _make_s3_mock([("k.gz", body)])
        mocker.patch.object(failed_document_stream, "_s3_client", return_value=s3)

        result = failed_document_stream.list_records(_config())
        assert [r["documentId"] for r in result] == ["ok"]

    def test_blank_lines_are_skipped(self, mocker):
        body = _gz(
            json.dumps({"timestamp": "t1", "documentId": "a"}) +
            "\n\n   \n" +
            json.dumps({"timestamp": "t2", "documentId": "b"}) +
            "\n"
        )
        s3 = _make_s3_mock([("k.gz", body)])
        mocker.patch.object(failed_document_stream, "_s3_client", return_value=s3)

        result = failed_document_stream.list_records(_config())
        assert len(result) == 2

    def test_paginator_called_with_session_prefix(self, mocker):
        body = _gz(_ndjson([{"timestamp": "t", "documentId": "x"}]))
        s3 = _make_s3_mock([("k.gz", body)])
        mocker.patch.object(failed_document_stream, "_s3_client", return_value=s3)

        cfg = _config(bucket="bk", prefix="dir/", session_id="run-1")
        failed_document_stream.list_records(cfg)
        s3.get_paginator.return_value.paginate.assert_called_once_with(
            Bucket="bk", Prefix="dir/session=run-1/"
        )


# ---------- count -----------------------------------------------------------

class TestCount:
    def test_counts_ndjson_lines_across_objects(self, mocker):
        obj1 = _gz(_ndjson([{"x": 1}, {"x": 2}]))
        obj2 = _gz(_ndjson([{"x": 3}]))
        s3 = _make_s3_mock([("a.gz", obj1), ("b.gz", obj2)])
        mocker.patch.object(failed_document_stream, "_s3_client", return_value=s3)

        assert failed_document_stream.count(_config()) == 3

    def test_skips_objects_with_corrupt_gzip(self, mocker):
        good = _gz(_ndjson([{"x": 1}, {"x": 2}]))
        bad = b"not gzip"
        s3 = _make_s3_mock([("g.gz", good), ("b.gz", bad)])
        mocker.patch.object(failed_document_stream, "_s3_client", return_value=s3)

        # Bad object contributes 0; good contributes 2.
        assert failed_document_stream.count(_config()) == 2

    def test_blank_lines_dont_count(self, mocker):
        body = _gz("\n   \n" + json.dumps({"x": 1}) + "\n")
        s3 = _make_s3_mock([("a.gz", body)])
        mocker.patch.object(failed_document_stream, "_s3_client", return_value=s3)

        assert failed_document_stream.count(_config()) == 1


# ---------- deduplication (at-least-once → duplicates collapse) --------------

class TestDeduplication:
    def test_count_collapses_reemitted_duplicates(self, mocker):
        # Same (targetIndex, documentId) emitted twice — e.g. original worker + successor
        # re-emit after a crash. Three raw lines, but only two distinct documents.
        obj1 = _gz(_ndjson([
            {"targetIndex": "movies", "documentId": "d1", "timestamp": "2026-05-01T00:00:00Z"},
        ]))
        obj2 = _gz(_ndjson([
            {"targetIndex": "movies", "documentId": "d1", "timestamp": "2026-05-02T00:00:00Z"},
            {"targetIndex": "movies", "documentId": "d2", "timestamp": "2026-05-02T00:00:00Z"},
        ]))
        s3 = _make_s3_mock([("a.gz", obj1), ("b.gz", obj2)])
        mocker.patch.object(failed_document_stream, "_s3_client", return_value=s3)

        assert failed_document_stream.count(_config()) == 2

    def test_list_keeps_latest_record_per_document(self, mocker):
        obj = _gz(_ndjson([
            {"targetIndex": "movies", "documentId": "d1", "failureType": "old",
             "timestamp": "2026-05-01T00:00:00Z"},
            {"targetIndex": "movies", "documentId": "d1", "failureType": "new",
             "timestamp": "2026-05-02T00:00:00Z"},
        ]))
        s3 = _make_s3_mock([("a.gz", obj)])
        mocker.patch.object(failed_document_stream, "_s3_client", return_value=s3)

        result = failed_document_stream.list_records(_config())
        assert len(result) == 1
        assert result[0]["failureType"] == "new"   # latest timestamp wins

    def test_same_doc_id_different_index_not_collapsed(self, mocker):
        # documentId is only unique within an index, so the dedup key includes targetIndex.
        obj = _gz(_ndjson([
            {"targetIndex": "movies", "documentId": "d1", "timestamp": "t"},
            {"targetIndex": "books", "documentId": "d1", "timestamp": "t"},
        ]))
        s3 = _make_s3_mock([("a.gz", obj)])
        mocker.patch.object(failed_document_stream, "_s3_client", return_value=s3)

        assert failed_document_stream.count(_config()) == 2

    def test_records_without_document_id_are_not_collapsed(self, mocker):
        # Null/missing/empty documentId can't be correlated across re-emissions → all retained.
        obj = _gz(_ndjson([
            {"targetIndex": "movies", "timestamp": "t1"},
            {"targetIndex": "movies", "documentId": None, "timestamp": "t2"},
            {"targetIndex": "movies", "documentId": "", "timestamp": "t3"},
        ]))
        s3 = _make_s3_mock([("a.gz", obj)])
        mocker.patch.object(failed_document_stream, "_s3_client", return_value=s3)

        assert failed_document_stream.count(_config()) == 3


# ---------- delete_session --------------------------------------------------

class TestDeleteSession:
    def test_single_batch_under_1000(self, mocker):
        keys = [(f"k{i}.gz", _gz("{}")) for i in range(3)]
        s3 = _make_s3_mock(keys)
        mocker.patch.object(failed_document_stream, "_s3_client", return_value=s3)

        assert failed_document_stream.delete_session(_config()) == 3
        # All 3 keys flushed in the single trailing batch.
        s3.delete_objects.assert_called_once()
        call = s3.delete_objects.call_args
        assert call.kwargs["Bucket"] == "b"
        assert [obj["Key"] for obj in call.kwargs["Delete"]["Objects"]] == [
            "k0.gz", "k1.gz", "k2.gz",
        ]

    def test_zero_objects_calls_no_deletes(self, mocker):
        s3 = _make_s3_mock([])
        mocker.patch.object(failed_document_stream, "_s3_client", return_value=s3)

        assert failed_document_stream.delete_session(_config()) == 0
        s3.delete_objects.assert_not_called()

    def test_batches_at_1000_boundary(self, mocker):
        # Total 1500 — should produce one full 1000-key batch and one trailing
        # 500-key batch. This is the only branch in delete_session that isn't
        # covered by the under-1000 path.
        keys = [(f"k{i}.gz", b"") for i in range(1500)]
        s3 = _make_s3_mock(keys)
        mocker.patch.object(failed_document_stream, "_s3_client", return_value=s3)

        assert failed_document_stream.delete_session(_config()) == 1500
        assert s3.delete_objects.call_count == 2
        first_batch = s3.delete_objects.call_args_list[0].kwargs["Delete"]["Objects"]
        second_batch = s3.delete_objects.call_args_list[1].kwargs["Delete"]["Objects"]
        assert len(first_batch) == 1000
        assert len(second_batch) == 500


# ---------- safe_count ------------------------------------------------------

class TestSafeCount:
    def test_returns_count_on_success(self, mocker):
        mocker.patch.object(failed_document_stream, "count", return_value=42)
        assert failed_document_stream.safe_count(_config()) == 42

    def test_returns_none_on_client_error(self, mocker):
        err = ClientError({"Error": {"Code": "NoSuchBucket", "Message": "x"}}, "ListObjects")
        mocker.patch.object(failed_document_stream, "count", side_effect=err)
        assert failed_document_stream.safe_count(_config()) is None

    def test_returns_none_on_botocore_error(self, mocker):
        # BotoCoreError requires no args.
        mocker.patch.object(failed_document_stream, "count", side_effect=BotoCoreError())
        assert failed_document_stream.safe_count(_config()) is None

    def test_does_not_swallow_unrelated_exceptions(self, mocker):
        # safe_count is narrow on purpose — programming bugs should still surface.
        mocker.patch.object(failed_document_stream, "count", side_effect=ValueError("boom"))
        with pytest.raises(ValueError):
            failed_document_stream.safe_count(_config())


# ---------- _select_snapshot_migration --------------------------------------

class TestSelectSnapshotMigration:
    @staticmethod
    def _sm(name, uid="u"):
        return {"metadata": {"name": name, "uid": uid}, "spec": {}}

    def test_single_item_returned_without_override(self):
        sm = self._sm("only")
        assert failed_document_stream._select_snapshot_migration(None, [sm]) is sm

    def test_override_selects_by_name(self):
        a, b = self._sm("a"), self._sm("b")
        assert failed_document_stream._select_snapshot_migration("b", [a, b]) is b

    def test_no_items_raises(self):
        with pytest.raises(failed_document_stream.FailedDocumentStreamNotConfigured, match="No SnapshotMigration"):
            failed_document_stream._select_snapshot_migration(None, [])

    def test_multiple_without_override_raises(self):
        items = [self._sm("a"), self._sm("b")]
        with pytest.raises(failed_document_stream.FailedDocumentStreamNotConfigured,
                           match="Multiple SnapshotMigration"):
            failed_document_stream._select_snapshot_migration(None, items)

    def test_unknown_override_raises(self):
        with pytest.raises(failed_document_stream.FailedDocumentStreamNotConfigured,
                           match="No SnapshotMigration named"):
            failed_document_stream._select_snapshot_migration("nope", [self._sm("a")])


# ---------- _iter_objects ---------------------------------------------------

class TestIterObjects:
    def test_handles_page_with_no_contents_key(self, mocker):
        # Some paginator implementations omit "Contents" entirely on empty pages.
        client = MagicMock()
        paginator = MagicMock()
        paginator.paginate.return_value = iter([{}, {"Contents": [{"Key": "a"}]}])
        client.get_paginator.return_value = paginator

        result = list(failed_document_stream._iter_objects(_config(), client=client))
        assert [o["Key"] for o in result] == ["a"]

    def test_handles_page_with_none_contents(self, mocker):
        client = MagicMock()
        paginator = MagicMock()
        paginator.paginate.return_value = iter([{"Contents": None}, {"Contents": [{"Key": "x"}]}])
        client.get_paginator.return_value = paginator

        result = list(failed_document_stream._iter_objects(_config(), client=client))
        assert [o["Key"] for o in result] == ["x"]
