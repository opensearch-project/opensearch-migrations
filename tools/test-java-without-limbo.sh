#!/bin/bash
#
# SPDX-License-Identifier: Apache-2.0
#
set -euo pipefail

cd "$(dirname "$0")/.." || exit 2

TOOL="tools/java-without-limbo.py"
FIXTURES="tools/testdata/java-without-limbo"
TEMP_DIR=$(mktemp -d /private/tmp/java-without-limbo-test.XXXXXX)
trap 'rm -rf "$TEMP_DIR"' EXIT

"$TOOL" print "$FIXTURES/valid.java" > "$TEMP_DIR/actual.java"
diff -u "$FIXTURES/valid.expected.java" "$TEMP_DIR/actual.java"

"$TOOL" search -F "live needle" "$FIXTURES/valid.java" > "$TEMP_DIR/search.txt"
grep -F "$FIXTURES/valid.java:10:    String live = \"live needle\";" "$TEMP_DIR/search.txt" >/dev/null
if "$TOOL" search -F "hidden needle" "$FIXTURES/valid.java" > "$TEMP_DIR/hidden.txt"; then
    echo "FAIL: search returned text from a limbo region" >&2
    exit 1
fi

for fixture in malformed-marker nested-start unmatched-start unmatched-end mismatched-end missing-open missing-close; do
    if "$TOOL" print "$FIXTURES/$fixture.java" > "$TEMP_DIR/$fixture.out" 2> "$TEMP_DIR/$fixture.err"; then
        echo "FAIL: $fixture.java was accepted" >&2
        exit 1
    fi
    grep -F "malformed REBUILD-LIMBO region" "$TEMP_DIR/$fixture.err" >/dev/null
done

echo "PASS: java-without-limbo print/search and malformed-region fixtures"
