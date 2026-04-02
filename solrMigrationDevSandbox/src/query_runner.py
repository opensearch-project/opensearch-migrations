"""
Query runner: executes Solr queries against Solr-direct, the Translation Shim,
and optionally a dual-target shim. Supports single queries and sequential cursor walks.
"""

import http.client
import re
import time
from dataclasses import dataclass, field
from typing import Optional
from urllib.parse import urljoin, quote

import requests

HEADERS = {"Accept-Encoding": "identity"}

# Dual-mode shim can return large validation headers; raise Python's default 64KB limit
http.client._MAXLINE = 1048576


@dataclass
class QueryResult:
    """Result of executing a single query against endpoints."""
    query_id: str
    category: str
    solr_response: Optional[dict] = None
    shim_response: Optional[dict] = None
    solr_latency_ms: float = 0.0
    shim_latency_ms: float = 0.0
    solr_error: Optional[str] = None
    shim_error: Optional[str] = None
    solr_pages: list[dict] = field(default_factory=list)
    shim_pages: list[dict] = field(default_factory=list)


@dataclass
class DualResult:
    """Result of executing a query against the dual-target shim."""
    query_id: str
    category: str
    response: Optional[dict] = None
    latency_ms: float = 0.0
    error: Optional[str] = None
    validation_header: Optional[str] = None
    pages: list[dict] = field(default_factory=list)
    page_validations: list[Optional[str]] = field(default_factory=list)


def _build_url(base_url, query_path):
    url = urljoin(base_url, query_path)
    if "wt=json" not in url:
        sep = "&" if "?" in url else "?"
        url += sep + "wt=json"
    return url


def _timed_get(url, timeout):
    """Execute GET, return (response, elapsed_ms). Raises on failure."""
    start = time.monotonic()
    resp = requests.get(url, timeout=timeout, headers=HEADERS)
    elapsed_ms = (time.monotonic() - start) * 1000
    resp.raise_for_status()
    return resp, elapsed_ms


def _classify_error(exc):
    if isinstance(exc, requests.exceptions.Timeout):
        return f"Timeout: {exc}"
    if isinstance(exc, requests.exceptions.ConnectionError):
        return f"Connection error: {exc}"
    if isinstance(exc, requests.exceptions.HTTPError):
        return f"HTTP error: {exc}"
    return f"Unexpected error: {exc}"


def _execute_query(base_url, query_path, timeout, return_headers=False):
    """Execute a single query. Returns (response_dict, latency_ms, error, [headers])."""
    url = _build_url(base_url, query_path)
    try:
        resp, elapsed_ms = _timed_get(url, timeout)
        if return_headers:
            return resp.json(), elapsed_ms, None, dict(resp.headers)
        return resp.json(), elapsed_ms, None
    except Exception as exc:
        err = _classify_error(exc)
        return (None, 0.0, err, None) if return_headers else (None, 0.0, err)


def _replace_cursor_in_path(path, cursor_value):
    return re.sub(r'cursorMark=[^&]*', 'cursorMark=' + quote(cursor_value, safe=''), path)


def _walk_page(base_url, path, timeout, capture_headers):
    """Fetch one page. Returns (resp, latency, err, validation_or_none)."""
    if capture_headers:
        resp, lat, err, hdrs = _execute_query(base_url, path, timeout, return_headers=True)
        val = hdrs.get("X-Validation") if hdrs else None
        return resp, lat, err, val
    resp, lat, err = _execute_query(base_url, path, timeout)
    return resp, lat, err, None


def _cursor_walk(base_url, initial_path, max_pages, timeout, capture_headers=False):
    """Walk cursor pagination against one endpoint."""
    pages, validations, total_ms = [], [], 0.0
    path, prev_cursor = initial_path, None

    for page_num in range(max_pages):
        resp, latency, err, val = _walk_page(base_url, path, timeout, capture_headers)
        if capture_headers:
            validations.append(val)
        total_ms += latency

        if err:
            return pages, total_ms, f"Page {page_num + 1}: {err}", validations or None
        if resp is None:
            return pages, total_ms, f"Page {page_num + 1}: empty response", validations or None

        pages.append(resp)
        next_cursor = resp.get("nextCursorMark")
        if not next_cursor or next_cursor == prev_cursor:
            break
        prev_cursor = next_cursor
        path = _replace_cursor_in_path(initial_path, next_cursor)

    return pages, total_ms, None, validations or None


def _run_cursor_walk(url, path, max_pages, timeout):
    """Run cursor walk, return (pages, latency, error)."""
    pages, lat, err, _ = _cursor_walk(url, path, max_pages, timeout)
    return pages, lat, err


def run_query(query, solr_url, shim_url, timeout=30):
    """Execute a query against Solr-direct and single-target shim."""
    result = QueryResult(query_id=query.get("id", "unknown"), category=query.get("category", "unknown"))
    path = query.get("path", "")
    sequence = query.get("sequence")

    if sequence:
        max_pages = sequence.get("maxPages", 5)
        result.solr_pages, result.solr_latency_ms, result.solr_error = (
            _run_cursor_walk(solr_url, path, max_pages, timeout))
        result.solr_response = result.solr_pages[0] if result.solr_pages else None
        result.shim_pages, result.shim_latency_ms, result.shim_error = (
            _run_cursor_walk(shim_url, path, max_pages, timeout))
        result.shim_response = result.shim_pages[0] if result.shim_pages else None
    else:
        result.solr_response, result.solr_latency_ms, result.solr_error = _execute_query(solr_url, path, timeout)
        result.shim_response, result.shim_latency_ms, result.shim_error = _execute_query(shim_url, path, timeout)
    return result


def run_dual_query(query, dual_url, timeout=30):
    """Execute a query against the dual-target shim, capturing validation headers."""
    result = DualResult(query_id=query.get("id", "unknown"), category=query.get("category", "unknown"))
    path = query.get("path", "")
    sequence = query.get("sequence")

    if sequence:
        max_pages = sequence.get("maxPages", 5)
        pages, lat, err, validations = _cursor_walk(dual_url, path, max_pages, timeout, capture_headers=True)
        result.pages, result.latency_ms, result.error = pages, lat, err
        result.response = pages[0] if pages else None
        result.page_validations = validations or []
    else:
        resp, lat, err, hdrs = _execute_query(dual_url, path, timeout, return_headers=True)
        result.response, result.latency_ms, result.error = resp, lat, err
        result.validation_header = hdrs.get("X-Validation") if hdrs else None
    return result


def _print_query_status(i, total, qid, is_seq, result):
    label = "[SEQ]" if is_seq else ""
    if is_seq:
        s_pg, h_pg = len(result.solr_pages), len(result.shim_pages)
        status = "ERR" if result.solr_error or result.shim_error else "EXEC"
        print(f"  [{i}/{total}] {qid}{label}... {status} "
              f"(solr: {s_pg}pg/{result.solr_latency_ms:.0f}ms, "
              f"shim: {h_pg}pg/{result.shim_latency_ms:.0f}ms)")
    else:
        status = "ERR" if result.solr_error or result.shim_error else "EXEC"
        print(f"  [{i}/{total}] {qid}{label}... {status} "
              f"(solr: {result.solr_latency_ms:.0f}ms, "
              f"shim: {result.shim_latency_ms:.0f}ms)")


def run_all_queries(queries, solr_url, shim_url, timeout=30):
    """Execute all queries against Solr-direct and single-target shim."""
    results = []
    total = len(queries)
    for i, query in enumerate(queries, 1):
        result = run_query(query, solr_url, shim_url, timeout)
        _print_query_status(i, total, query.get("id", "?"), "sequence" in query, result)
        results.append(result)
    return results


def _print_dual_status(i, total, qid, is_seq, result):
    label = "[SEQ]" if is_seq else ""
    if result.error:
        print(f"  [{i}/{total}] {qid}{label}... ERR ({result.latency_ms:.0f}ms)")
    elif is_seq:
        print(f"  [{i}/{total}] {qid}{label}... EXEC ({len(result.pages)}pg/{result.latency_ms:.0f}ms)")
    else:
        val = result.validation_header or "no-header"
        print(f"  [{i}/{total}] {qid}{label}... EXEC ({result.latency_ms:.0f}ms) [{val}]")


def run_all_dual_queries(queries, dual_url, timeout=30):
    """Execute all queries against the dual-target shim."""
    results = []
    total = len(queries)
    for i, query in enumerate(queries, 1):
        result = run_dual_query(query, dual_url, timeout)
        _print_dual_status(i, total, query.get("id", "?"), "sequence" in query, result)
        results.append(result)
    return results
