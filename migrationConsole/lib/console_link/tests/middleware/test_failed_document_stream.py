"""Unit tests for console_link.middleware.failed_document_stream.

Coverage targets:
  * FailedDocumentStreamConfig properties (session_prefix, location_uri)
  * load_config resolution precedence (CLI/env > ConfigMap > deployment default)
  * Both FailedDocumentStreamNotConfigured branches (no bucket / no session)
  * _s3_client region selection
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

# Names of every env var failed_document_stream.load_config looks at — clearing them up-front
# keeps a developer's shell environment from leaking into assertions.
_ENV_VARS = [
    "RFS_FAILED_DOCUMENT_STREAM_S3_BUCKET",
    "RFS_FAILED_DOCUMENT_STREAM_S3_PREFIX",
    "RFS_FAILED_DOCUMENT_STREAM_SESSION_ID",
    "RFS_FAILED_DOCUMENT_STREAM_S3_REGION",
    "MIGRATIONS_DEFAULT_S3_BUCKET",
    "BUCKET_NAME",
]


@pytest.fixture(autouse=True)
def clean_env(monkeypatch):
    for name in _ENV_VARS:
        monkeypatch.delenv(name, raising=False)
    # Reset the module-level ConfigMap cache so each test starts fresh.
    failed_document_stream._configmap_cache = None


@pytest.fixture
def no_configmap(monkeypatch):
    """Make _read_configmap always return None — used by tests that aren't
    exercising the ConfigMap branch."""
    monkeypatch.setattr(failed_document_stream, "_read_configmap", lambda key: None)


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


# ---------- load_config -----------------------------------------------------

class TestLoadConfig:
    def test_raises_when_no_bucket_anywhere(self, monkeypatch, no_configmap):
        with pytest.raises(failed_document_stream.FailedDocumentStreamNotConfigured, match="bucket"):
            failed_document_stream.load_config(session_override="s")

    def test_raises_when_bucket_present_but_no_session(self, monkeypatch, no_configmap):
        monkeypatch.setenv("RFS_FAILED_DOCUMENT_STREAM_S3_BUCKET", "b")
        with pytest.raises(failed_document_stream.FailedDocumentStreamNotConfigured, match="session"):
            failed_document_stream.load_config()

    def test_explicit_env_bucket_wins_over_default_fallbacks(self, monkeypatch, no_configmap):
        monkeypatch.setenv("RFS_FAILED_DOCUMENT_STREAM_S3_BUCKET", "explicit-bucket")
        monkeypatch.setenv("MIGRATIONS_DEFAULT_S3_BUCKET", "deployment-default")
        monkeypatch.setenv("BUCKET_NAME", "old-style-bucket")
        cfg = failed_document_stream.load_config(session_override="s")
        assert cfg.bucket == "explicit-bucket"

    def test_falls_back_to_migrations_default_when_explicit_unset(self, monkeypatch, no_configmap):
        monkeypatch.setenv("MIGRATIONS_DEFAULT_S3_BUCKET", "deployment-default")
        cfg = failed_document_stream.load_config(session_override="s")
        assert cfg.bucket == "deployment-default"

    def test_falls_back_to_bucket_name_when_others_unset(self, monkeypatch, no_configmap):
        monkeypatch.setenv("BUCKET_NAME", "old-style-bucket")
        cfg = failed_document_stream.load_config(session_override="s")
        assert cfg.bucket == "old-style-bucket"

    def test_configmap_bucket_used_when_env_unset(self, monkeypatch):
        cm = {"bucket": "from-cm", "session_id": "cm-sess"}
        monkeypatch.setattr(failed_document_stream, "_read_configmap", lambda key: cm.get(key))
        cfg = failed_document_stream.load_config()
        assert cfg.bucket == "from-cm"
        assert cfg.session_id == "cm-sess"

    def test_session_override_arg_wins_over_env_and_configmap(self, monkeypatch):
        monkeypatch.setenv("RFS_FAILED_DOCUMENT_STREAM_S3_BUCKET", "b")
        monkeypatch.setenv("RFS_FAILED_DOCUMENT_STREAM_SESSION_ID", "from-env")
        monkeypatch.setattr(failed_document_stream, "_read_configmap",
                            lambda key: "from-cm" if key == "session_id" else None)
        cfg = failed_document_stream.load_config(session_override="from-arg")
        assert cfg.session_id == "from-arg"

    def test_session_env_wins_over_configmap(self, monkeypatch):
        monkeypatch.setenv("RFS_FAILED_DOCUMENT_STREAM_S3_BUCKET", "b")
        monkeypatch.setenv("RFS_FAILED_DOCUMENT_STREAM_SESSION_ID", "env-sess")
        monkeypatch.setattr(failed_document_stream, "_read_configmap",
                            lambda key: "cm-sess" if key == "session_id" else None)
        cfg = failed_document_stream.load_config()
        assert cfg.session_id == "env-sess"

    def test_prefix_env_overrides_default(self, monkeypatch, no_configmap):
        monkeypatch.setenv("RFS_FAILED_DOCUMENT_STREAM_S3_BUCKET", "b")
        monkeypatch.setenv("RFS_FAILED_DOCUMENT_STREAM_S3_PREFIX", "custom-prefix")  # no trailing slash
        cfg = failed_document_stream.load_config(session_override="s")
        # Trailing slash must be appended so session= joins cleanly.
        assert cfg.prefix == "custom-prefix/"

    def test_prefix_default_when_env_and_configmap_unset(self, monkeypatch, no_configmap):
        monkeypatch.setenv("RFS_FAILED_DOCUMENT_STREAM_S3_BUCKET", "b")
        cfg = failed_document_stream.load_config(session_override="s")
        assert cfg.prefix == "rfs-failed-document-stream/"

    def test_region_pulled_from_env(self, monkeypatch, no_configmap):
        monkeypatch.setenv("RFS_FAILED_DOCUMENT_STREAM_S3_BUCKET", "b")
        monkeypatch.setenv("RFS_FAILED_DOCUMENT_STREAM_S3_REGION", "us-west-2")
        cfg = failed_document_stream.load_config(session_override="s")
        assert cfg.region == "us-west-2"

    def test_region_pulled_from_configmap_when_env_unset(self, monkeypatch):
        monkeypatch.setenv("RFS_FAILED_DOCUMENT_STREAM_S3_BUCKET", "b")
        monkeypatch.setattr(failed_document_stream, "_read_configmap",
                            lambda key: {"session_id": "s", "region": "ap-south-1"}.get(key))
        cfg = failed_document_stream.load_config()
        assert cfg.region == "ap-south-1"


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


# ---------- _read_configmap / _fetch_configmap_data ------------------------

class TestReadConfigmap:
    def test_returns_stripped_value(self, mocker):
        mocker.patch.object(failed_document_stream, "_fetch_configmap_data",
                            return_value={"session_id": "  s1 \n"})
        assert failed_document_stream._read_configmap("session_id") == "s1"

    def test_returns_none_for_missing_key(self, mocker):
        mocker.patch.object(failed_document_stream, "_fetch_configmap_data", return_value={})
        assert failed_document_stream._read_configmap("absent") is None

    def test_returns_none_for_empty_value(self, mocker):
        mocker.patch.object(failed_document_stream, "_fetch_configmap_data", return_value={"session_id": ""})
        assert failed_document_stream._read_configmap("session_id") is None

    def test_caches_result_across_calls(self, mocker):
        fetch_mock = mocker.patch.object(failed_document_stream, "_fetch_configmap_data",
                                         return_value={"bucket": "b1"})
        assert failed_document_stream._read_configmap("bucket") == "b1"
        # Second call should NOT refetch — the cache is module-level and lives
        # until load_config explicitly clears it.
        assert failed_document_stream._read_configmap("bucket") == "b1"
        assert fetch_mock.call_count == 1


class TestFetchConfigmapData:
    """The Kubernetes client is an optional dep that may or may not be
    importable. These tests stub out the import machinery so we exercise
    the success / 404 / generic-API-error / ImportError branches without
    touching a real cluster."""

    def _install_fake_kubernetes(self, monkeypatch, *, read_returns=None, read_raises=None,
                                 incluster_raises=False, kubeconfig_raises=False):
        """Inject a fake `kubernetes` package into sys.modules so
        `from kubernetes import client, config` inside _fetch_configmap_data
        resolves to our stubs."""
        import sys
        import types

        # Build the fake config module with load_incluster_config / load_kube_config.
        class FakeConfigException(Exception):
            pass

        fake_config = types.SimpleNamespace(
            ConfigException=FakeConfigException,
            load_incluster_config=MagicMock(
                side_effect=FakeConfigException("not in cluster") if incluster_raises else None
            ),
            load_kube_config=MagicMock(
                side_effect=FakeConfigException("no kubeconfig") if kubeconfig_raises else None
            ),
        )

        # The CoreV1Api ConfigMap object — read_namespaced_config_map returns
        # an object whose .data is what _fetch_configmap_data ultimately
        # returns.
        cm_obj = MagicMock()
        cm_obj.data = read_returns

        v1 = MagicMock()
        if read_raises is not None:
            v1.read_namespaced_config_map.side_effect = read_raises
        else:
            v1.read_namespaced_config_map.return_value = cm_obj

        fake_client = types.SimpleNamespace(CoreV1Api=MagicMock(return_value=v1))

        # ApiException with a settable .status — mirror the real one.
        class FakeApiException(Exception):
            def __init__(self, status):
                super().__init__(f"api error {status}")
                self.status = status

        fake_rest = types.SimpleNamespace(ApiException=FakeApiException)

        # Wire the fake package tree.
        fake_pkg = types.ModuleType("kubernetes")
        fake_pkg.client = fake_client
        fake_pkg.config = fake_config
        fake_client_pkg = types.ModuleType("kubernetes.client")
        fake_client_pkg.rest = fake_rest

        monkeypatch.setitem(sys.modules, "kubernetes", fake_pkg)
        monkeypatch.setitem(sys.modules, "kubernetes.client", fake_client_pkg)
        monkeypatch.setitem(sys.modules, "kubernetes.client.rest", fake_rest)

        # Stub the namespace helper that _fetch_configmap_data imports.
        utils_mod = types.ModuleType("console_link.workflow.models.utils")
        utils_mod.get_current_namespace = MagicMock(return_value="ma")
        monkeypatch.setitem(sys.modules, "console_link.workflow.models.utils", utils_mod)
        # Ensure the parent packages exist so the import lookup succeeds.
        for parent in ("console_link.workflow", "console_link.workflow.models"):
            if parent not in sys.modules:
                monkeypatch.setitem(sys.modules, parent, types.ModuleType(parent))

        return FakeApiException

    def test_returns_data_on_success(self, monkeypatch):
        self._install_fake_kubernetes(monkeypatch,
                                      read_returns={"session_id": "s", "bucket": "b"})
        assert failed_document_stream._fetch_configmap_data() == {"session_id": "s", "bucket": "b"}

    def test_returns_empty_dict_when_data_is_none(self, monkeypatch):
        # ConfigMap exists but has no data — k8s returns None, we want {}.
        self._install_fake_kubernetes(monkeypatch, read_returns=None)
        assert failed_document_stream._fetch_configmap_data() == {}

    def test_returns_empty_dict_on_404(self, monkeypatch):
        FakeApiException = self._install_fake_kubernetes(monkeypatch)
        # Re-wire read_namespaced_config_map to raise the 404 form.
        import sys
        v1_factory = sys.modules["kubernetes"].client.CoreV1Api
        v1_instance = v1_factory.return_value
        v1_instance.read_namespaced_config_map.side_effect = FakeApiException(404)
        v1_instance.read_namespaced_config_map.return_value = None  # gone

        assert failed_document_stream._fetch_configmap_data() == {}

    def test_returns_empty_dict_on_other_api_error(self, monkeypatch):
        FakeApiException = self._install_fake_kubernetes(monkeypatch)
        import sys
        v1_instance = sys.modules["kubernetes"].client.CoreV1Api.return_value
        v1_instance.read_namespaced_config_map.side_effect = FakeApiException(500)

        assert failed_document_stream._fetch_configmap_data() == {}

    def test_falls_back_to_kube_config_when_not_in_cluster(self, monkeypatch):
        self._install_fake_kubernetes(
            monkeypatch,
            read_returns={"session_id": "out-of-cluster"},
            incluster_raises=True,   # load_incluster_config raises -> fall through
        )
        assert failed_document_stream._fetch_configmap_data() == {"session_id": "out-of-cluster"}

    def test_returns_empty_dict_on_generic_exception(self, monkeypatch):
        # Any non-ApiException error (e.g., the namespace helper exploding)
        # is swallowed too — a failed document stream inspection command shouldn't crash on
        # ConfigMap weirdness.
        self._install_fake_kubernetes(monkeypatch)
        import sys
        utils_mod = sys.modules["console_link.workflow.models.utils"]
        utils_mod.get_current_namespace.side_effect = RuntimeError("can't read /var/run/...")

        assert failed_document_stream._fetch_configmap_data() == {}

    def test_returns_empty_dict_when_kubernetes_module_missing(self, monkeypatch):
        # If the kubernetes client isn't installed at all, _fetch_configmap_data
        # must return {} rather than propagating ImportError.
        import sys
        # Force the import inside _fetch_configmap_data to fail.
        for mod_name in ("kubernetes", "kubernetes.client", "kubernetes.client.rest"):
            monkeypatch.setitem(sys.modules, mod_name, None)

        assert failed_document_stream._fetch_configmap_data() == {}


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
