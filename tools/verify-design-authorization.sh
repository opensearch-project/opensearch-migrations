#!/bin/bash
#
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#
# Enforces AGENTS.md red line 1: an implementation agent never changes the authoritative designs under
# docs/captureAndReplay/ on its own initiative.
#
# The rule already existed and was still broken twice, because it is a rule that requires noticing. Nothing
# stops an edit at the moment it is made, and a design change buried in a commit that also touches code is
# invisible at review. So it gets a check instead of a reminder.
#
# Every owner-authorized change already gets a dated row in the "Design changes" table of
# docs/replayerRebuildStatus.md -- that convention predates this script. The check is therefore simply: for
# each design document modified on this branch, is there a row naming it? A modified document with no row is
# either an unauthorized edit or an unrecorded one, and both are failures.
#
# This cannot verify that the owner actually approved a row; nothing mechanical can. What it removes is the
# failure mode that happened: an edit going in with no record of a decision at all.
#
# Usage: tools/verify-design-authorization.sh [base-ref]   (default: docs/captureAndReplay/APPROVED-AT)

set -u -o pipefail

cd "$(git rev-parse --show-toplevel)" || exit 2
# Defaults to the settled-design commit recorded in docs/captureAndReplay/APPROVED-AT, so the corpus own
# authoring history is not read as implementation-era edits.
BASE="${1:-$(head -1 docs/captureAndReplay/APPROVED-AT)}"
DESIGN_DIR="docs/captureAndReplay"
REGISTER="docs/replayerRebuildStatus.md"

if ! git rev-parse --verify --quiet "$BASE" >/dev/null; then
    echo "FAIL: cannot resolve base ref '$BASE'" >&2
    exit 2
fi

merge_base=$(git merge-base "$BASE" HEAD)
# Excludes the baseline marker, which lives beside the designs but is not one of them.
changed=$(git diff --name-only "$merge_base"...HEAD -- "$DESIGN_DIR" | grep -v '/APPROVED-AT$' | sort -u)

if [ -z "$changed" ]; then
    echo "No design documents changed since $BASE. Red line 1 cannot have been crossed."
    exit 0
fi

# The table's rows cite documents by their short name -- kafkaLLD, procCommit, connLLD, captureArch -- which is
# the convention the plans use in their Design refs lines, so the mapping lives here rather than being guessed.
short_name() {
    case "$(basename "$1")" in
        replayerKafkaSourceAndIntakeLowLevelDesign.md) echo "kafkaLLD" ;;
        replayerProcessingAndCommitArchitecture.md)    echo "procCommit" ;;
        replayerConnectionAndRequestLowLevelDesign.md) echo "connLLD" ;;
        replayerLowLevelDesign.md)                     echo "replayerLLD" ;;
        captureAndReplayArchitecture.md)               echo "captureArch" ;;
        asyncMessagePassingProgrammingGuide.md)        echo "async" ;;
        proxyCaptureProtocol.md)                       echo "proxyProtocol" ;;
        BringYourOwnCapturedTraffic.md)                echo "BringYourOwnCapturedTraffic" ;;
        managedFleetCaptureRecovery.md)                echo "managedFleetCaptureRecovery" ;;
        *) echo "" ;;
    esac
}

table=$(awk '/^## Design changes/{inside=1} inside && /^\| 20/{print}' "$REGISTER")
unrecorded=0
mixed=0

echo "Design documents changed since $BASE:"
for file in $changed; do
    name=$(short_name "$file")
    if [ -z "$name" ]; then
        echo "  UNKNOWN DOCUMENT: $file has no short name in this script; add one and re-run"
        unrecorded=$((unrecorded + 1))
        continue
    fi
    if printf '%s\n' "$table" | grep -q "$name"; then
        echo "  recorded: $file (as \`$name\`)"
    else
        echo "  UNRECORDED: $file (\`$name\`) has no row in the register's Design changes table"
        unrecorded=$((unrecorded + 1))
    fi
done

# A design edit sharing a commit with implementation is not itself a rule violation, but it is how one hides.
#
# Already-pushed commits cannot be un-mixed without rewriting shared history, so known ones are listed here with
# their reason. The list is deliberately explicit and deliberately short: a check that stays red gets ignored,
# and an exemption nobody can see is worse than no check. Adding to it for a *new* commit is not a remedy.
# Empty, and that is the point: the one entry this list ever held was split into a design commit and a code
# commit during the 2026-09-24 history consolidation, so the exemption has no subject. A stale exemption is
# worse than a visible violation, because it silently widens what the check permits.
ACKNOWLEDGED_MIXED=""
for commit in $(git rev-list "$merge_base"..HEAD -- "$DESIGN_DIR"); do
    short=$(git rev-parse --short "$commit")
    if printf '%s' "$ACKNOWLEDGED_MIXED" | grep -q "$short"; then
        echo "  acknowledged mixed commit: $(git log -1 --format='%h %s' "$commit")"
        continue
    fi
    if git show --name-only --format= "$commit" | grep -q '^TrafficCapture/.*\.java$'; then
        echo "  MIXED COMMIT: $(git log -1 --format='%h %s' "$commit")"
        echo "                changes a design document and Java in one commit, where a reviewer looking at"
        echo "                code will not expect a design change"
        mixed=$((mixed + 1))
    fi
done

echo
if [ "$unrecorded" -ne 0 ] || [ "$mixed" -ne 0 ]; then
    echo "FAIL: $unrecorded unrecorded design change(s), $mixed mixed commit(s)."
    echo "      Every design change needs the owner's explicit authorization and a dated row in $REGISTER."
    exit 1
fi
echo "PASS: every changed design document has a row in the register's Design changes table."
