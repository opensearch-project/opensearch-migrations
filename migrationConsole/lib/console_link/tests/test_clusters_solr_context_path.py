"""Every Solr URL the console builds must honour the cluster's configured context path, not just the
ones on the snapshot path. These cover the user-facing middleware entry points.
"""
import pytest

from console_link.middleware.clusters import cat_indices, connection_check
from console_link.models.cluster import AuthMethod

from tests.utils import create_valid_cluster


class FakeResponse:
    def __init__(self, payload, status_code=200):
        self._payload = payload
        self.status_code = status_code

    def json(self):
        return self._payload


def solr_cluster(solr_context_path=None):
    return create_valid_cluster(auth_type=AuthMethod.NO_AUTH, version="SOLR 9.4",
                                solr_context_path=solr_context_path)


def record_calls(cluster, responder):
    requested = []

    def fake_call_api(path, *args, **kwargs):
        requested.append(path)
        return responder(path)

    cluster.call_api = fake_call_api
    return requested


def cloud_responder(path):
    if "action=LIST" in path:
        return FakeResponse({"collections": ["products"]})
    if "/select" in path:
        return FakeResponse({"response": {"numFound": 7}})
    if "admin/info/system" in path:
        return FakeResponse({"lucene": {"solr-spec-version": "9.4.0"}})
    return FakeResponse({})


@pytest.mark.parametrize("context_path,prefix", [
    (None, "/solr"),
    ("/tenant-a/solr", "/tenant-a/solr"),
    ("", ""),
])
def test_connection_check_uses_context_path(context_path, prefix):
    cluster = solr_cluster(context_path)
    requested = record_calls(cluster, cloud_responder)

    result = connection_check(cluster)

    assert result.connection_established
    assert result.cluster_version == "9.4.0"
    assert requested == [f"{prefix}/admin/info/system"]


@pytest.mark.parametrize("context_path,prefix", [
    (None, "/solr"),
    ("/tenant-a/solr", "/tenant-a/solr"),
    ("", ""),
])
def test_cat_indices_collection_discovery_and_doc_counts_use_context_path(context_path, prefix):
    cluster = solr_cluster(context_path)
    requested = record_calls(cluster, cloud_responder)

    result = cat_indices(cluster, as_json=True)

    assert result == [{"index": "products", "docs.count": "7"}]
    assert requested == [
        f"{prefix}/admin/collections?action=LIST&wt=json",
        f"{prefix}/products/select?q=*:*&rows=0&wt=json",
    ]


def test_cat_indices_standalone_core_fallback_uses_context_path():
    cluster = solr_cluster("/tenant-a/solr")

    def standalone_responder(path):
        if "action=LIST" in path:
            return FakeResponse({}, status_code=404)
        if "action=STATUS" in path:
            return FakeResponse({"status": {"core1": {}}})
        return FakeResponse({"response": {"numFound": 3}})

    requested = record_calls(cluster, standalone_responder)

    result = cat_indices(cluster, as_json=True)

    assert result == [{"index": "core1", "docs.count": "3"}]
    assert requested == [
        "/tenant-a/solr/admin/collections?action=LIST&wt=json",
        "/tenant-a/solr/admin/cores?action=STATUS&wt=json",
        "/tenant-a/solr/core1/select?q=*:*&rows=0&wt=json",
    ]


@pytest.mark.parametrize("bad_value", [
    "https://solr.example.com:8983/solr",
    "/solr?wt=json",
    "/solr#fragment",
])
def test_cluster_rejects_url_or_query_context_paths(bad_value):
    """Parity with SolrContextPath.normalize on the Java side — reject rather than build a bad URL."""
    with pytest.raises(ValueError, match="not a URL or query string"):
        solr_cluster(bad_value)
