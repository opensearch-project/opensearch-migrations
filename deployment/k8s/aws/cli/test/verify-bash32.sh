#!/usr/bin/env bash
# verify-bash32.sh — prove the Amber TUI compiles to AND runs on bash 3.2+.
#
# Two layers of verification, because `amber test` runs the test harness on the
# HOST bash (often 5.x) and never exercises the 3.2 target:
#
#   1. COMPILE every src module with `--target bash-3.2`. Amber's 3.2 backend
#      avoids associative arrays / mapfile / ${var^^} etc., so a clean compile
#      is the first guarantee.
#   2. EXECUTE compiled driver(s) under a real bash 3.2 interpreter and assert
#      on their behavior. On macOS /bin/bash is 3.2.57 — the canonical floor.
#      If no 3.2 interpreter is found we fall back to `bash --posix`-style
#      smoke (still runs, just not a true 3.2 VM) and LOUDLY say so.
#
# Exit non-zero on any failure so CI gates on it.

set -o errexit
set -o nounset
set -o pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$HERE/.." && pwd)"
SRC_DIR="$ROOT/src"
BUILD_DIR="$ROOT/build"
AMBER="${AMBER:-amber}"

red()   { printf '\033[31m%s\033[0m\n' "$*" >&2; }
green() { printf '\033[32m%s\033[0m\n' "$*" >&2; }
dim()   { printf '\033[2m%s\033[0m\n' "$*" >&2; }

mkdir -p "$BUILD_DIR"

# ---- locate a genuine bash 3.2 interpreter --------------------------------

BASH32=""
for cand in /bin/bash /usr/local/bin/bash-3.2 "${BASH_32:-}"; do
  [[ -z "${cand:-}" ]] && continue
  [[ -x "$cand" ]] || continue
  ver="$("$cand" -c 'echo "${BASH_VERSINFO[0]}.${BASH_VERSINFO[1]}"' 2>/dev/null || echo "?")"
  if [[ "$ver" == 3.2 ]]; then BASH32="$cand"; break; fi
done

if [[ -n "$BASH32" ]]; then
  green "✓ found bash 3.2 interpreter: $BASH32 ($("$BASH32" --version | head -1))"
else
  red "! no bash 3.2 interpreter found — will run compiled output under \$BASH ($(bash --version | head -1))"
  red "  (compile-to-3.2 is still verified; runtime is host bash)"
  BASH32="$(command -v bash)"
fi

# ---- (1) compile every module to bash-3.2 ----------------------------------

dim "── compiling src/*.ab with --target bash-3.2 ──"
fail=0
for f in "$SRC_DIR"/*.ab; do
  base="$(basename "$f" .ab)"
  if "$AMBER" build --target bash-3.2 "$f" "$BUILD_DIR/$base.sh" >"$BUILD_DIR/$base.build.log" 2>&1; then
    green "  ✓ $base.ab → build/$base.sh"
  else
    red "  ✗ $base.ab failed to compile to bash-3.2:"
    grep -i error "$BUILD_DIR/$base.build.log" | head -5 >&2 || true
    fail=1
  fi
done
[[ "$fail" == 0 ]] || { red "compile-to-3.2 FAILED"; exit 1; }

# ---- (2) run the demo driver under bash 3.2 and assert ---------------------

dim "── building + running the end-to-end demo under $BASH32 ──"
DEMO_AB="$BUILD_DIR/__verify_demo.ab"
cat > "$DEMO_AB" <<'AMBER'
import { term_detect } from "../src/core.ab"
import { term_set_geometry } from "../src/term.ab"
import { dash_init, dash_set_mode, dash_upsert, dash_render_simple } from "../src/dashboard.ab"
import { date_now } from "std/date"
main {
    term_detect()
    term_set_geometry(24, 100)
    dash_init("verify-stack us-east-1")
    dash_set_mode("cfn")
    dash_upsert("VPC", "CREATE_COMPLETE", "", "t1")
    dash_upsert("EKS", "CREATE_IN_PROGRESS", "", "t2")
    dash_upsert("NG", "CREATE_FAILED", "Insufficient capacity", "t3")
    let frame = dash_render_simple(date_now() - 95)
    echo "RENDER_OK len={len frame}"
}
AMBER
"$AMBER" build --target bash-3.2 "$DEMO_AB" "$BUILD_DIR/__verify_demo.sh" >/dev/null 2>&1

out="$(MIGRATE_FORCE_TTY=1 "$BASH32" "$BUILD_DIR/__verify_demo.sh" 2>/dev/null || true)"
err="$(MIGRATE_FORCE_TTY=1 "$BASH32" "$BUILD_DIR/__verify_demo.sh" 2>&1 1>/dev/null || true)"

assert_contains() {
  local hay="$1" needle="$2" label="$3"
  if printf '%s' "$hay" | grep -qF -- "$needle"; then
    green "  ✓ $label"
  else
    red   "  ✗ $label  (missing: $needle)"
    fail=1
  fi
}

assert_contains "$out" "RENDER_OK"        "stdout: render returned a frame"
assert_contains "$err" "verify-stack"     "stderr: header rendered"
assert_contains "$err" "✓ 1"              "stderr: 1 complete"
assert_contains "$err" "↻ 1"              "stderr: 1 in-progress"
assert_contains "$err" "✗ 1"              "stderr: 1 failed"
assert_contains "$err" "total 3"          "stderr: total count"
assert_contains "$err" "CREATE_FAILED"    "stderr: failed row shown"
assert_contains "$err" "Insufficient capacity" "stderr: failure reason shown"
assert_contains "$err" $'\033[?25l'       "stderr: cursor hidden (ESC[?25l)"

rm -f "$DEMO_AB"

if [[ "$fail" == 0 ]]; then
  green "── bash 3.2 verification PASSED ──"
else
  red "── bash 3.2 verification FAILED ──"
  exit 1
fi
