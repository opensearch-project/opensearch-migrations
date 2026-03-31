#!/usr/bin/env python3
"""
Query executor — runs queries against Solr-direct and the Translation Shim,
prints execution summary with success counts and latencies.

Usage:
    python3 -m src.harness.run_queries \
        --solr-url http://localhost:18983 \
        --shim-url http://localhost:18080 \
        --queries queries/queries.json
"""

import argparse
import json
import sys

from .query_runner import run_all_queries


def main():
    parser = argparse.ArgumentParser(description="Solr Shim Query Executor")
    parser.add_argument("--solr-url", default="http://localhost:18983")
    parser.add_argument("--shim-url", default="http://localhost:18080")
    parser.add_argument("--queries", required=True, help="Path to queries.json")
    parser.add_argument("--timeout", type=int, default=30)
    args = parser.parse_args()

    print(f"Loading queries from {args.queries}...")
    with open(args.queries, "r") as f:
        queries = json.load(f)
    print(f"  {len(queries)} queries loaded")

    print(f"\nExecuting queries (solr={args.solr_url}, shim={args.shim_url})...")
    results = run_all_queries(queries, args.solr_url, args.shim_url, args.timeout)

    solr_ok = sum(1 for r in results if r.solr_error is None)
    shim_ok = sum(1 for r in results if r.shim_error is None)
    total = len(results)

    print(f"\n{'=' * 50}")
    print("  QUERY EXECUTION SUMMARY")
    print(f"{'=' * 50}")
    print(f"  Total queries:    {total}")
    print(f"  Solr succeeded:   {solr_ok}/{total}")
    print(f"  Shim succeeded:   {shim_ok}/{total}")

    solr_errors = [r for r in results if r.solr_error]
    shim_errors = [r for r in results if r.shim_error]

    if solr_errors:
        print(f"\n  Solr Errors ({len(solr_errors)}):")
        for r in solr_errors:
            print(f"    {r.query_id}: {r.solr_error}")

    if shim_errors:
        print(f"\n  Shim Errors ({len(shim_errors)}):")
        for r in shim_errors:
            print(f"    {r.query_id}: {r.shim_error}")

    solr_lats = [r.solr_latency_ms for r in results if r.solr_error is None]
    shim_lats = [r.shim_latency_ms for r in results if r.shim_error is None]

    if solr_lats:
        print(f"\n  Avg Solr latency:  {sum(solr_lats)/len(solr_lats):.0f}ms")
    if shim_lats:
        print(f"  Avg Shim latency:  {sum(shim_lats)/len(shim_lats):.0f}ms")

    print(f"{'=' * 50}")

    sys.exit(1 if shim_errors else 0)


if __name__ == "__main__":
    main()
