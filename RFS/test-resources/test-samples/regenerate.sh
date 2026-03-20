#!/bin/bash
# Regenerate test sample files by deleting them and re-running the extraction test.
# The test detects missing files and writes current extraction output as the new baseline.
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

echo "Deleting existing sample files..."
find "$SCRIPT_DIR" -name '*.json' -delete

echo "Running ReferenceDocumentExtractionTest to regenerate..."
cd "$REPO_ROOT"
./gradlew :SnapshotReader:test --tests '*ReferenceDocumentExtractionTest' --info

echo "Done. Regenerated files:"
ls "$SCRIPT_DIR"/*.json
