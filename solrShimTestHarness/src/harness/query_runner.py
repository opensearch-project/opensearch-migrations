"""
Query runner: executes Solr queries against both Solr-direct and the Translation Shim,
capturing responses and latencies.
"""

import time
from dataclasses import dataclass
from typing import Optional
from urllib.parse import urljoin

import requests


@dataclass
class QueryResult:
    """Result of executing a single query against both endpoints."""
    query_id: str
    category: str
    solr_response: Optional[dict] = None
    shim_response: Optional[dict] = None
    solr_latency_ms: float = 0.0
    shim_latency_ms: float = 0.0
    solr_error: Optional[str] = None
    shim_error: Optional[str] = None


def _execute_query(base_url: str, query_path: str, timeout: int) -> tuple[Optional[dict], float, Optional[str]]:
    """Execute a single query. Returns (response_dict, latency_ms, error_message)."""
    url = urljoin(base_url, query_path)

    if "wt=json" not in url:
        separator = "&" if "?" in url else "?"
        url += f"{separator}wt=json"

    try:
        start = time.monotonic()
        resp = requests.get(url, timeout=timeout, headers={'Accept-Encoding': 'identity'})
        elapsed_ms = (time.monotonic() - start) * 1000
        resp.raise_for_status()
        return resp.json(), elapsed_ms, None
    except requests.exceptions.Timeout:
        return None, 0.0, f"Timeout after {timeout}s"
    except requests.exceptions.ConnectionError as e:
        return None, 0.0, f"Connection error: {e}"
    except requests.exceptions.HTTPError as e:
        return None, 0.0, f"HTTP error: {e}"
    except Exception as e:
        return None, 0.0, f"Unexpected error: {e}"


def run_query(query: dict, solr_url: str, shim_url: str, timeout: int = 30) -> QueryResult:
    """Execute a single query against both Solr-direct and the Translation Shim."""
    query_id = query.get("id", "unknown")
    category = query.get("category", "unknown")
    path = query.get("path", "")

    result = QueryResult(query_id=query_id, category=category)

    solr_resp, solr_lat, solr_err = _execute_query(solr_url, path, timeout)
    result.solr_response = solr_resp
    result.solr_latency_ms = solr_lat
    result.solr_error = solr_err

    shim_resp, shim_lat, shim_err = _execute_query(shim_url, path, timeout)
    result.shim_response = shim_resp
    result.shim_latency_ms = shim_lat
    result.shim_error = shim_err

    return result


def run_all_queries(
    queries: list[dict],
    solr_url: str,
    shim_url: str,
    timeout: int = 30,
) -> list[QueryResult]:
    """Execute all queries sequentially, returning results."""
    results = []
    total = len(queries)
    for i, query in enumerate(queries, 1):
        qid = query.get("id", "?")
        print(f"  [{i}/{total}] {qid}...", end=" ", flush=True)
        result = run_query(query, solr_url, shim_url, timeout)
        status = "ERR" if result.solr_error or result.shim_error else "EXEC"
        print(f"{status} (solr: {result.solr_latency_ms:.0f}ms, shim: {result.shim_latency_ms:.0f}ms)")
        results.append(result)
    return results
