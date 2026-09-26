#!/bin/bash
#
# SPDX-License-Identifier: Apache-2.0
#
set -euo pipefail

cd "$(dirname "$0")/.." || exit 2

TOOL="tools/java-structural-equalizer.py"
FIXTURES="tools/testdata/java-structural-equalizer"
TEMP_DIR=$(mktemp -d /private/tmp/java-structural-equalizer-test.XXXXXX)
trap 'rm -rf "$TEMP_DIR"' EXIT

"$TOOL" compare-files "$FIXTURES/original.java" "$FIXTURES/equivalent.java"
"$TOOL" compare-files \
    "tools/testdata/java-without-limbo/missing-close.java" \
    "tools/testdata/java-without-limbo/missing-close.java"

if "$TOOL" compare-files \
    "$FIXTURES/original.java" \
    "$FIXTURES/changed-body.java" \
    --report-dir "$TEMP_DIR/changed-body"; then
    echo "FAIL: a changed method body was accepted" >&2
    exit 1
fi

if "$TOOL" compare-files \
    "$FIXTURES/original.java" \
    "$FIXTURES/reordered-initialization.java" \
    --report-dir "$TEMP_DIR/reordered-initialization"; then
    echo "FAIL: reordered initialization was accepted" >&2
    exit 1
fi

test -s "$TEMP_DIR/changed-body/original.canonical"
test -s "$TEMP_DIR/changed-body/candidate.canonical"
test -s "$TEMP_DIR/reordered-initialization/original.canonical"
test -s "$TEMP_DIR/reordered-initialization/candidate.canonical"

REPO="$TEMP_DIR/rename-repo"
git init -q "$REPO"
git -C "$REPO" config user.name "Equalizer Test"
git -C "$REPO" config user.email "equalizer@example.com"
mkdir -p "$REPO/src"
cp "$FIXTURES/original.java" "$REPO/src/Original.java"
mkdir -p "$REPO/proxy"
cp "$FIXTURES/proxy-original.java" "$REPO/proxy/Proxy.java"
git -C "$REPO" add src/Original.java proxy/Proxy.java
git -C "$REPO" commit -q -m "base"
BASE=$(git -C "$REPO" rev-parse HEAD)

git -C "$REPO" switch -q -c original
git -C "$REPO" mv src/Original.java src/Renamed.java
cp "$FIXTURES/proxy-equivalent.java" "$REPO/proxy/Proxy.java"
git -C "$REPO" add proxy/Proxy.java
git -C "$REPO" commit -q -m "rename"
ORIGINAL=$(git -C "$REPO" rev-parse HEAD)

git -C "$REPO" switch -q -c candidate "$BASE"
git -C "$REPO" rm -q src/Original.java
mkdir -p "$REPO/src"
cp "$FIXTURES/equivalent.java" "$REPO/src/Candidate.java"
cp "$FIXTURES/proxy-equivalent.java" "$REPO/proxy/Proxy.java"
git -C "$REPO" add src/Candidate.java proxy/Proxy.java
git -C "$REPO" commit -q -m "reconstruct"
CANDIDATE=$(git -C "$REPO" rev-parse HEAD)

"$TOOL" compare-commit-pair \
    --repo "$REPO" \
    --original-parent "$BASE" \
    --original "$ORIGINAL" \
    --candidate-parent "$BASE" \
    --candidate "$CANDIDATE"

cp "$FIXTURES/proxy-changed.java" "$REPO/proxy/Proxy.java"
git -C "$REPO" add proxy/Proxy.java
git -C "$REPO" commit -q -m "change proxy behavior"
CHANGED_PROXY=$(git -C "$REPO" rev-parse HEAD)

if "$TOOL" compare-commit-pair \
    --repo "$REPO" \
    --original-parent "$BASE" \
    --original "$ORIGINAL" \
    --candidate-parent "$BASE" \
    --candidate "$CHANGED_PROXY" \
    --report-dir "$TEMP_DIR/proxy-change"; then
    echo "FAIL: repository-wide comparison omitted a proxy Java behavior change" >&2
    exit 1
fi

"$TOOL" compare-commit-pair \
    --repo "$REPO" \
    --root src \
    --original-parent "$BASE" \
    --original "$ORIGINAL" \
    --candidate-parent "$BASE" \
    --candidate "$CHANGED_PROXY"

test -s "$TEMP_DIR/proxy-change/original.canonical"
test -s "$TEMP_DIR/proxy-change/candidate.canonical"

echo "PASS: Java structural equalizer covers repository-wide Java changes by default"
