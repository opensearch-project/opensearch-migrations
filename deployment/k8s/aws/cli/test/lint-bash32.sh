#!/usr/bin/env bash
# lint-bash32.sh — fail the build if any tracked shell file uses a bash 4+
# construct.
#
# The CLI's bash 3.2 floor is intentional: macOS ships /bin/bash 3.2 and
# operators must not be required to `brew install bash` before running.
# A lone comment in state.sh:5 isn't enough enforcement; this script is.
#
# Forbidden constructs (with rationale):
#   declare -A         — associative arrays  (bash 4.0+)
#   ${var,,} ${var^^}  — case-conversion     (bash 4.0+)
#   mapfile / readarray — array-from-stdin   (bash 4.0+)
#   &>>                — append-redirect     (bash 4.0+)
#   coproc             — coprocess           (bash 4.0+)
#
# Run from the cli/ directory:
#   ./test/lint-bash32.sh
#
# Wired into Makefile's lint target and the migrate-cli GH workflow.

set -o errexit
set -o nounset
set -o pipefail

cd "$(dirname "$0")/.."

# Files to scan: every shell file under bin/, lib/, install.sh, plus the
# tests' own shell helpers (test/helpers/*.sh). bats files (test/*.bats)
# are deliberately excluded — bats syntax includes constructs that
# resemble grep targets but compile to bash internally.
files=(
  bin/migration-assistant
  install.sh
)
while IFS= read -r f; do
  files+=("$f")
done < <(find lib -type f -name '*.sh' | sort)
while IFS= read -r f; do
  files+=("$f")
done < <(find test/helpers -type f -name '*.sh' 2>/dev/null | sort || true)

# Forbidden patterns. Each is a bash extended-regex matching the
# bash-4-only construct in code (not in comments — those are stripped).
patterns=(
  'declare[[:space:]]+-A\b'
  'local[[:space:]]+-A\b'
  '\$\{[A-Za-z_][A-Za-z0-9_]*,,?\}'
  '\$\{[A-Za-z_][A-Za-z0-9_]*\^\^?\}'
  '\bmapfile\b'
  '\breadarray\b'
  '&>>[[:space:]]'
  '\bcoproc\b'
)

rc=0
for f in "${files[@]}"; do
  [[ -f "$f" ]] || continue
  # Strip line comments before scanning so prose mentions of the
  # forbidden constructs don't trip the lint. Naive — full quote-aware
  # tokenization isn't worth the build cost, and no current file embeds
  # these patterns inside string literals.
  body=$(sed -E 's/[[:space:]]*#.*$//' "$f")
  for pat in "${patterns[@]}"; do
    matches=$(printf '%s\n' "$body" | grep -nE "$pat" || true)
    if [[ -n "$matches" ]]; then
      printf '%s: forbidden bash 4+ construct (pattern: %s)\n' "$f" "$pat" >&2
      printf '%s\n' "$matches" | sed -E "s|^|  ${f}:|" >&2
      rc=1
    fi
  done
done

if (( rc == 0 )); then
  printf 'bash-3.2 lint: %d files clean\n' "${#files[@]}"
fi
exit "$rc"
