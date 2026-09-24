#!/bin/bash
#
# SPDX-License-Identifier: Apache-2.0
#
set -euo pipefail

cd "$(dirname "$0")/.." || exit 2

WRAPPER="tools/gradle-evidence.sh"
TEMP_DIR=$(mktemp -d /private/tmp/gradle-evidence-test.XXXXXX)
trap 'rm -rf "$TEMP_DIR"' EXIT

FAKE_GRADLE_ARGS_FILE="$TEMP_DIR/success.args" \
FAKE_GRADLE_EXIT_CODE=0 \
GRADLE_EVIDENCE_GRADLEW="$PWD/tools/testdata/gradle-evidence/fake-gradlew" \
    "$WRAPPER" :module:test --tests "Example test with spaces" "literal;not-a-command" -- \
    > "$TEMP_DIR/success.out" 2> "$TEMP_DIR/success.err"

cat > "$TEMP_DIR/success.expected.args" <<'EOF'
-x
spotlessJavaCheck
-x
spotlessJavaApply
:module:test
--tests
Example test with spaces
literal;not-a-command
--
EOF
diff -u "$TEMP_DIR/success.expected.args" "$TEMP_DIR/success.args"
grep -F "PASS: Gradle completed with exit code 0." "$TEMP_DIR/success.out" >/dev/null
success_log=$(sed -n 's/^Full log: //p' "$TEMP_DIR/success.out")
test -f "$success_log"
case "$success_log" in
    /private/tmp/*) ;;
    *)
        echo "FAIL: success log is not under /private/tmp: $success_log" >&2
        exit 1
        ;;
esac

set +e
FAKE_GRADLE_ARGS_FILE="$TEMP_DIR/failure.args" \
FAKE_GRADLE_EXIT_CODE=37 \
GRADLE_EVIDENCE_GRADLEW="$PWD/tools/testdata/gradle-evidence/fake-gradlew" \
    "$WRAPPER" :module:compileJava > "$TEMP_DIR/failure.out" 2> "$TEMP_DIR/failure.err"
failure_status=$?
set -e

if [ "$failure_status" -ne 37 ]; then
    echo "FAIL: wrapper returned $failure_status instead of fake Gradle's 37" >&2
    exit 1
fi
grep -F "FAIL: Gradle exited with code 37." "$TEMP_DIR/failure.err" >/dev/null
grep -F "> Task :fake FAILED" "$TEMP_DIR/failure.err" >/dev/null
failure_log=$(sed -n 's/^Full log: //p' "$TEMP_DIR/failure.err")
test -f "$failure_log"

rm -f "$success_log" "$failure_log"
echo "PASS: gradle-evidence forwards arguments, injects exclusions, logs output, and preserves status"
