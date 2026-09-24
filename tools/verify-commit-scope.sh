#!/bin/bash
# Fails on a commit whose subject names one milestone while the commit ADDS a file under a package another
# milestone owns. AGENTS.md §5 forbids burying unrelated work inside a change, and this is the shape that rule
# was broken in: `3c3472d06` was subject-lined G2 and added SourceConnectionState and SourceAssemblySink under
# replay/intake/, six hundred lines of G3 nobody reading the message would know were there.
#
# Deliberately narrow. It checks ADDED files only, because a modification is often a mechanical consequence of
# the commit's own work -- repointing an import after a package move is a one-line edit in another milestone's
# file and is not buried work. A new file under another milestone's package never is.
#
# Usage: tools/verify-commit-scope.sh [<range>]   (default: origin/main..HEAD, else everything on this branch)
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2

RANGE="${1:-}"
if [ -z "$RANGE" ]; then
  if git rev-parse --verify -q origin/main >/dev/null; then
    RANGE="origin/main..HEAD"
  else
    RANGE="HEAD"
  fi
fi

# Package ownership, only where it is unambiguous. A package with no row here is not checked.
owner_of() {
  case "$1" in
    */org/opensearch/migrations/replay/intake/*)      echo G3 ;;
    */org/opensearch/migrations/replay/kafkasource/*) echo G2 ;;
    *)                                                echo "" ;;
  esac
}

failures=0
while read -r sha; do
  [ -n "$sha" ] || continue
  subject=$(git log -1 --format=%s "$sha")
  milestone=$(printf '%s' "$subject" | sed -n 's/^\(G[0-9][0-9]*\)[:.].*/\1/p')
  [ -n "$milestone" ] || continue
  # G0 is exempt by definition: it is the milestone that puts every file in the module at the path it will
  # ship from, marked, so it necessarily adds files under packages later milestones own. Anything else adding
  # a file outside its own package is the buried-work shape this checks for.
  [ "$milestone" = "G0" ] && continue
  while read -r status path; do
    [ "$status" = "A" ] || continue
    owner=$(owner_of "$path")
    [ -n "$owner" ] || continue
    if [ "$owner" != "$milestone" ]; then
      echo "MIXED  $(git log -1 --format=%h "$sha")  subject says $milestone but adds $owner file: $path"
      failures=$((failures + 1))
    fi
  done < <(git show --name-status --format= "$sha")
done < <(git rev-list "$RANGE")

if [ "$failures" -gt 0 ]; then
  echo
  echo "FAIL: $failures commit/file pair(s) add a file outside the milestone the subject names."
  echo "      Split the commit; see AGENTS.md section 5 and the register's mixed-commit entry."
  exit 1
fi
echo "PASS: no commit adds a file under a package another milestone owns."
