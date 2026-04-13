"""
Query executor — runs queries against Solr-direct and the Translation Shim,
optionally also against a dual-target shim for cross-validation.

Usage:
    python3 -m src.run_queries \
        --solr-url http://localhost:18983 \
        --shim-url http://localhost:18080 \
        --queries queries/queries.json

    # With dual-mode validation:
    python3 -m src.run_queries \
        --solr-url http://localhost:18983 \
        --shim-url http://localhost:18080 \
        --dual-url http://localhost:18083 \
        --queries queries/queries.json
"""

import argparse
import json
import sys

from .query_runner import run_all_queries, run_all_dual_queries

SEPARATOR = "=" * 50


def _print_dual_summary(results):
    total = len(results)
    ok = sum(1 for r in results if r.error is None)
    errors = [r for r in results if r.error]
    lats = [r.latency_ms for r in results if r.error is None]

    print(f"\n{SEPARATOR}")
    print("  DUAL-MODE SUMMARY")
    print(SEPARATOR)
    print(f"  Total queries:    {total}")
    print(f"  Succeeded:        {ok}/{total}")

    if errors:
        print(f"\n  Errors ({len(errors)}):")
        for r in errors:
            print(f"    {r.query_id}: {r.error}")
    if lats:
        print(f"\n  Avg latency:  {sum(lats) / len(lats):.0f}ms")
    print(SEPARATOR)
    return len(errors) > 0


def _print_single_summary(results):
    total = len(results)
    solr_ok = sum(1 for r in results if r.solr_error is None)
    shim_ok = sum(1 for r in results if r.shim_error is None)
    solr_errors = [r for r in results if r.solr_error]
    shim_errors = [r for r in results if r.shim_error]
    solr_lats = [r.solr_latency_ms for r in results if r.solr_error is None]
    shim_lats = [r.shim_latency_ms for r in results if r.shim_error is None]

    print(f"\n{SEPARATOR}")
    print("  QUERY EXECUTION SUMMARY")
    print(SEPARATOR)
    print(f"  Total queries:    {total}")
    print(f"  Solr succeeded:   {solr_ok}/{total}")
    print(f"  Shim succeeded:   {shim_ok}/{total}")

    if solr_errors:
        print(f"\n  Solr Errors ({len(solr_errors)}):")
        for r in solr_errors:
            print(f"    {r.query_id}: {r.solr_error}")
    if shim_errors:
        print(f"\n  Shim Errors ({len(shim_errors)}):")
        for r in shim_errors:
            print(f"    {r.query_id}: {r.shim_error}")
    if solr_lats:
        print(f"\n  Avg Solr latency:  {sum(solr_lats) / len(solr_lats):.0f}ms")
    if shim_lats:
        print(f"  Avg Shim latency:  {sum(shim_lats) / len(shim_lats):.0f}ms")
    print(SEPARATOR)
    return len(shim_errors) > 0


def main():
    parser = argparse.ArgumentParser(description="Solr Migration Developer Sandbox — Query Executor")
    parser.add_argument("--solr-url", default="http://localhost:18983")
    parser.add_argument("--shim-url", default="http://localhost:18080")
    parser.add_argument("--dual-url", default=None, help="Dual-target shim URL (e.g. http://localhost:18083)")
    parser.add_argument("--queries", required=True, help="Path to queries.json")
    parser.add_argument("--timeout", type=int, default=30)
    args = parser.parse_args()

    print(f"Loading queries from {args.queries}...")
    with open(args.queries, "r") as f:
        queries = json.load(f)
    print(f"  {len(queries)} queries loaded")

    print(f"\nExecuting queries (solr={args.solr_url}, shim={args.shim_url})...")
    results = run_all_queries(queries, args.solr_url, args.shim_url, args.timeout)
    has_errors = _print_single_summary(results)

    if args.dual_url:
        print(f"\nExecuting queries in dual mode (dual={args.dual_url})...")
        dual_results = run_all_dual_queries(queries, args.dual_url, args.timeout)
        dual_has_errors = _print_dual_summary(dual_results)
        has_errors = has_errors or dual_has_errors

    print(f"\n{SEPARATOR}")
    print("=== Done ===")
    sys.exit(1 if has_errors else 0)


if __name__ == "__main__":
    main()
