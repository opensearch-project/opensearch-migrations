import sys
import os
from unittest.mock import patch, MagicMock

import requests

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from src.query_runner import (
    _build_url, _classify_error, _execute_query, run_query, run_dual_query
)


def test_build_url_adds_wt_json():
    url = _build_url("http://localhost:8983", "/solr/test/select?q=*:*")
    assert "wt=json" in url


def test_build_url_no_duplicate_wt_json():
    url = _build_url("http://localhost:8983", "/solr/test/select?q=*:*&wt=json")
    assert url.count("wt=json") == 1


def test_build_url_path_without_query_string():
    url = _build_url("http://localhost:8983", "/solr/test/select")
    assert url.endswith("?wt=json")


def test_build_url_bare_slash():
    url = _build_url("http://localhost:8983", "/")
    assert "wt=json" in url


def test_build_url_trailing_slash_on_base():
    url = _build_url("http://localhost:8983/", "/solr/test/select?q=*:*")
    assert "wt=json" in url
    assert "localhost:8983" in url


def test_classify_error_timeout():
    exc = requests.exceptions.Timeout()
    assert "Timeout" in _classify_error(exc)


def test_classify_error_connection():
    exc = requests.exceptions.ConnectionError()
    assert "Connection error" in _classify_error(exc)


def test_classify_error_http():
    exc = requests.exceptions.HTTPError()
    assert "HTTP error" in _classify_error(exc)


def test_execute_query_success():
    mock_resp = MagicMock()
    mock_resp.json.return_value = {"response": {"numFound": 10}}
    mock_resp.raise_for_status.return_value = None

    with patch("src.query_runner.requests.get", return_value=mock_resp):
        result, latency, error = _execute_query("http://localhost:8983", "/solr/test/select?q=*:*", timeout=5)

    assert result == {"response": {"numFound": 10}}
    assert latency >= 0
    assert error is None


def test_execute_query_timeout_returns_error():
    with patch("src.query_runner.requests.get", side_effect=requests.exceptions.Timeout()):
        result, latency, error = _execute_query("http://localhost:8983", "/solr/test/select?q=*:*", timeout=1)

    assert result is None
    assert latency == 0.0
    assert "Timeout" in error


def test_run_query_stores_responses_independently():
    solr_resp = MagicMock()
    solr_resp.json.return_value = {"response": {"numFound": 10}}
    solr_resp.raise_for_status.return_value = None

    shim_resp = MagicMock()
    shim_resp.json.return_value = {"response": {"numFound": 7}}
    shim_resp.raise_for_status.return_value = None

    query = {"id": "q003", "category": "test", "path": "/solr/test/select?q=*:*"}

    with patch("src.query_runner.requests.get", side_effect=[solr_resp, shim_resp]):
        result = run_query(query, "http://solr:8983", "http://shim:8080")

    assert result.solr_response["response"]["numFound"] == 10
    assert result.shim_response["response"]["numFound"] == 7


def test_run_query_records_error_on_failure():
    query = {"id": "q002", "category": "test", "path": "/solr/test/select?q=*:*"}

    with patch("src.query_runner.requests.get", side_effect=requests.exceptions.ConnectionError()):
        result = run_query(query, "http://solr:8983", "http://shim:8080")

    assert result.solr_error is not None
    assert result.shim_error is not None
    assert result.solr_response is None


def test_run_query_cursor_sequence_walks_pages():
    page1_resp = MagicMock()
    page1_resp.json.return_value = {"response": {"numFound": 20}, "nextCursorMark": "AoE="}
    page1_resp.raise_for_status.return_value = None

    page2_resp = MagicMock()
    page2_resp.json.return_value = {"response": {"numFound": 20}, "nextCursorMark": "AoE="}  # same = end
    page2_resp.raise_for_status.return_value = None

    query = {
        "id": "cursor1", "category": "cursor",
        "path": "/solr/test/select?q=*:*&sort=id+asc&cursorMark=*",
        "sequence": {"maxPages": 5}
    }

    with patch("src.query_runner.requests.get", side_effect=[
        page1_resp, page2_resp,  # solr walk
        page1_resp, page2_resp,  # shim walk
    ]):
        result = run_query(query, "http://solr:8983", "http://shim:8080")

    assert len(result.solr_pages) == 2
    assert len(result.shim_pages) == 2
    assert result.solr_error is None


def test_run_dual_query_captures_validation_header():
    mock_resp = MagicMock()
    mock_resp.json.return_value = {"response": {"numFound": 5}}
    mock_resp.raise_for_status.return_value = None
    mock_resp.headers = {"X-Validation": "doc-count:PASS"}

    query = {"id": "d001", "category": "test", "path": "/solr/test/select?q=*:*"}

    with patch("src.query_runner.requests.get", return_value=mock_resp):
        result = run_dual_query(query, "http://dual:8084")

    assert result.validation_header == "doc-count:PASS"
    assert result.response == {"response": {"numFound": 5}}
    assert result.error is None
