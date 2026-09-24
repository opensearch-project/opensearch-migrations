#!/bin/bash
# AGENTS.md §4.1 falsification pass for G3 source assembly and record accounting. Breaks one property at a
# time and records which tests notice. Runs in a throwaway worktree created from a COMMIT, so a deliberate
# break can never be committed and so staged-but-uncommitted tests are not silently excluded.
#
# Usage: git worktree add /tmp/falsify HEAD && tools/falsify-g3.sh /tmp/falsify && git worktree remove /tmp/falsify
W="${1:?usage: falsify-g3.sh <throwaway-worktree-path>}"
cd "$W" || exit 2
S=TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/intake/SourceConnectionState.java
P=TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/intake/PartitionIntakeState.java
O=TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/intake/ReplayIntakeOwner.java
RESULTS="$W/TrafficCapture/trafficReplayer/build/test-results/test"

run() {
  label="$1"
  if git diff --quiet -- "$S" "$P" "$O"; then
    echo "NOT-APPLIED  $label  <-- the mutation did not match; this says nothing about the tests"
    return
  fi
  rm -rf "$RESULTS"
  ./gradlew :TrafficCapture:trafficReplayer:test --tests '*intake*' \
      -x spotlessJavaCheck -x spotlessJavaApply > /tmp/falsify-g3-run.log 2>&1
  status=$?
  failed=$(grep -B1 '<failure' "$RESULTS"/*.xml 2>/dev/null \
           | grep -o 'testcase name="[^"]*"' | sed 's/testcase name=//' | tr -d '"' | sort -u | tr '\n' ' ')
  if [ "$status" -eq 0 ]; then
    echo "SURVIVED     $label  <-- no test noticed"
  elif grep -q "error:" /tmp/falsify-g3-run.log; then
    echo "NO-COMPILE   $label  <-- the break does not compile, so it is not a usable mutation"
  else
    echo "CAUGHT       $label  by: ${failed:-(failed, names unavailable)}"
  fi
  git checkout -q -- "$S" "$P" "$O"
}

# 1. Parse the inherited tail as a new request instead of discarding it (kafkaLLD §9 continuity).
perl -pi -e 's/this\.phase = inheritedTail \? Phase\.DISCARDING_INHERITED_TAIL : Phase\.BETWEEN_REQUESTS;/this.phase = Phase.BETWEEN_REQUESTS;/' "$S"
run "inherited tail parsed as a new request"

# 2. Stop advancing the ordinal past the discarded tail, so the next request collides with the predecessor's.
perl -0pi -e 's/            firstStream\.getPriorRequestsReceived\(\) \+ \(inheritedTail \? 1 : 0\);/            firstStream.getPriorRequestsReceived();/' "$S"
run "ordinal does not advance past the discarded tail"

# 3. Skip observation-sequence contiguity (§9's baseline-then-contiguous rule).
perl -0pi -e 's/        if \(sequence != nextExpectedObservationSequence\) \{\n(?:.*?\n)*?        \}\n(        nextExpectedObservationSequence = sequence \+ 1;)/$1/' "$S"
run "sequence gaps accepted"

# 4. Let observations join a lifetime that already closed (§9.3 step 5).
perl -pi -e 's/        if \(lifetime != Lifetime\.OPEN\) \{/        if (false) {/' "$S"
run "observations join a closed lifetime"

# 5. Reuse one localSequence for every lifetime of a captured connection (§2).
perl -pi -e 's/new ConnectionProcessingId\(generation, capturedConnectionId, nextConnectionLocalSequence\+\+\)/new ConnectionProcessingId(generation, capturedConnectionId, 0)/' "$P"
run "a fresh lifetime reuses the expired one's identity"

# 6. Relabel by removing the old association before adding the new one, creating §8.2's forbidden gap.
perl -0pi -e 's/            if \(tracker\.adoptRelabelled\(newAssociation\)\) \{\n                addReverseAssociation\(newAssociation, recordId\);\n            \}\n            tracker\.removeAssociation\(oldAssociation\);\n            removeReverseAssociation\(oldAssociation, recordId\);/            tracker.removeAssociation(oldAssociation);\n            removeReverseAssociation(oldAssociation, recordId);\n            emitCompletionIfEligible(tracker);\n            if (tracker.adoptRelabelled(newAssociation)) {\n                addReverseAssociation(newAssociation, recordId);\n            }/' "$P"
run "relabel drops the old association before adding the new one"

# 7. Close the record to new associations before applying its payload, reversing §7 steps 7 and 8.
perl -0pi -e 's/        applyPayload\(state, record\);\n\n(?:        \/\/[^\n]*\n)*        state\.closeRecordToNewAssociations\(record\.recordId\(\)\);/        state.closeRecordToNewAssociations(record.recordId());\n        applyPayload(state, record);/' "$O"
run "record closed to associations before its payload is applied"
