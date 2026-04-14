#!/usr/bin/env python3
"""
Bulk loads a JSON dataset into both Solr and OpenSearch, then verifies document counts match.

Usage:
    python3 scripts/load_data.py \
        --dataset data/dataset.json \
        --solr-url http://localhost:18983 \
        --opensearch-url http://localhost:19200 \
        [--collection testharness] [--index testharness] [--batch-size 500]
"""

import argparse
import json
import sys

import requests

# Flush stdout immediately for real-time progress output
sys.stdout.reconfigure(line_buffering=True)


def load_into_solr(documents, solr_url, collection, batch_size):
    total = len(documents)
    loaded = 0
    print(f"\nLoading into Solr ({collection})...")

    for i in range(0, total, batch_size):
        batch = documents[i:i + batch_size]
        try:
            resp = requests.post(
                f"{solr_url}/solr/{collection}/update",
                json=batch,
                headers={"Content-Type": "application/json"},
                timeout=120
            )
            resp.raise_for_status()
            loaded += len(batch)
            if (i // batch_size) % 10 == 0:
                print(f"  Solr: {loaded}/{total} loaded...")
        except Exception as e:
            print(f"ERROR: Solr batch load failed at doc {i}: {e}", file=sys.stderr)
            sys.exit(1)

    requests.get(f"{solr_url}/solr/{collection}/update?commit=true", timeout=30)
    print(f"  Solr: {loaded} documents loaded and committed.")


def load_into_opensearch(documents, opensearch_url, index, batch_size):
    total = len(documents)
    loaded = 0
    print(f"\nLoading into OpenSearch ({index})...")

    for i in range(0, total, batch_size):
        batch = documents[i:i + batch_size]
        bulk_body = ""
        for doc in batch:
            action = json.dumps({"index": {"_index": index, "_id": doc["id"]}})
            source = json.dumps(doc)
            bulk_body += f"{action}\n{source}\n"

        try:
            resp = requests.post(
                f"{opensearch_url}/_bulk",
                data=bulk_body,
                headers={"Content-Type": "application/x-ndjson"},
                timeout=120
            )
            resp.raise_for_status()
            result = resp.json()
            if result.get("errors"):
                error_items = [item for item in result["items"]
                               if "error" in item.get("index", {})]
                if error_items:
                    print(f"WARNING: {len(error_items)} errors in batch at doc {i}",
                          file=sys.stderr)
            loaded += len(batch)
            if (i // batch_size) % 10 == 0:
                print(f"  OpenSearch: {loaded}/{total} loaded...")
        except Exception as e:
            print(f"ERROR: OpenSearch batch load failed at doc {i}: {e}", file=sys.stderr)
            sys.exit(1)

    requests.post(f"{opensearch_url}/{index}/_refresh", timeout=30)
    print(f"  OpenSearch: {loaded} documents loaded and refreshed.")


def verify_counts(solr_url, collection, opensearch_url, index):
    print("\nVerifying document counts...")
    solr_resp = requests.get(
        f"{solr_url}/solr/{collection}/select?q=*:*&rows=0&wt=json", timeout=30)
    solr_count = solr_resp.json()["response"]["numFound"]

    os_resp = requests.get(f"{opensearch_url}/{index}/_count", timeout=30)
    os_count = os_resp.json()["count"]

    print(f"  Solr:       {solr_count}")
    print(f"  OpenSearch: {os_count}")

    if solr_count != os_count:
        print(f"ERROR: Document count mismatch! Solr={solr_count}, OpenSearch={os_count}",
              file=sys.stderr)
        sys.exit(1)

    print(f"\n=== Load complete: {solr_count} documents in both clusters ===")


def main():
    parser = argparse.ArgumentParser(description="Bulk load dataset into Solr and OpenSearch")
    parser.add_argument("--dataset", required=True, help="Path to dataset JSON")
    parser.add_argument("--solr-url", required=True, help="Solr base URL")
    parser.add_argument("--opensearch-url", required=True, help="OpenSearch base URL")
    parser.add_argument("--collection", default="testharness", help="Solr collection name")
    parser.add_argument("--index", default="testharness", help="OpenSearch index name")
    parser.add_argument("--batch-size", type=int, default=500, help="Batch size")
    args = parser.parse_args()

    print(f"Loading dataset from {args.dataset} (this may take a moment)...")
    with open(args.dataset, "r") as f:
        documents = json.load(f)
    print(f"  {len(documents)} documents loaded into memory")

    load_into_solr(documents, args.solr_url, args.collection, args.batch_size)
    load_into_opensearch(documents, args.opensearch_url, args.index, args.batch_size)
    verify_counts(args.solr_url, args.collection, args.opensearch_url, args.index)


if __name__ == "__main__":
    main()
