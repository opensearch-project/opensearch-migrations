#!/usr/bin/env bash
# run-bats.sh — wrap bats with explicit fail-on-mismatch detection.
#
# WHY THIS EXISTS:
# bats prints a warning ("Executed N instead of expected M tests") when
# a test fails to run AT FUNCTION-RESOLUTION time — typically because:
#
#   1. A `[[ … ]] && fn` chain inside the test body or a sourced lib
#      returns rc=1 from the test pattern, and `set -e` (which bats
#      enforces per test) aborts the function before bats can record
#      the test result.
#   2. A stale test references a function deleted from the lib
#      surface, and bash returns 127 (command not found) — bats sees
#      the test process exited but never received an `ok`/`not ok`
#      line, so it counts the test as "not run."
#
# Either way the bats process can exit 0 OR 1 depending on harness,
# and the warning is printed to stderr — easy for a CI script to miss.
#
# This wrapper:
#   * Runs bats with --print-output-on-failure (so any actual failures
#     dump test stdout/stderr to the build log).
#   * Captures bats's stderr.
#   * After the run, greps for "Executed N instead of expected M" and
#     fails with rc=2 + a loud, framed error if found — even when bats
#     itself exited 0.
#
# Local + CI both call this script instead of `bats test/` directly.

set -o errexit
set -o nounset
set -o pipefail

cd "$(dirname "$0")/.."

bats_bin="${BATS:-bats}"
if ! command -v "$bats_bin" >/dev/null 2>&1; then
  echo "run-bats: '$bats_bin' not on PATH; install bats-core (brew install bats-core)" >&2
  exit 127
fi

# Capture stderr so we can grep for the warning AFTER the run.
stderr_log=$(mktemp)
trap 'rm -f "$stderr_log"' EXIT

# `bats` to stdout (the GHA log captures both anyway), stderr to a tmp
# file we can scan. Don't use --tap; the default colorized output is
# fine for humans + grep.
set +e
"$bats_bin" --print-output-on-failure test/ 2> >(tee "$stderr_log" >&2)
rc=$?
set -e

if grep -qE 'bats warning: Executed [0-9]+ instead of expected [0-9]+ tests' "$stderr_log"; then
  cat >&2 <<EOF

╔══════════════════════════════════════════════════════════════════════╗
║  bats: TEST COUNT MISMATCH — silently-skipped tests detected         ║
╚══════════════════════════════════════════════════════════════════════╝

bats reported one or more tests that didn't run. This usually means:

  * A test calls a function that no longer exists (recently renamed
    or deleted in a lib refactor) — bash returns 127, bats records
    "not run" instead of a failure.

  * A sourced lib / setup() runs a \`[[ … ]] && fn\` chain whose test
    side fails — under \`set -e + errtrace\` (which bats enables per
    test) this aborts the enclosing function before bats can record
    a result.

To find the culprit:

  1. Check stderr above for "Executed N instead of expected M".
  2. Run each .bats file individually:
        for f in test/*.bats; do
          c=\$(bats --count "\$f")
          a=\$(bats --tap "\$f" | grep -cE '^(ok|not ok)')
          [ "\$c" != "\$a" ] && echo "MISMATCH: \$f (\$c declared, \$a ran)"
        done
  3. Inside the offending file, narrow to a single test:
        bats --filter '<test-name-substring>' test/<file>.bats
  4. Add an ERR trap to the test to find the failing line:
        trap 'echo "ERR @ \$BASH_SOURCE:\$LINENO [\$BASH_COMMAND]" >&3' ERR
        set -E

EOF
  exit 2
fi

exit "$rc"
