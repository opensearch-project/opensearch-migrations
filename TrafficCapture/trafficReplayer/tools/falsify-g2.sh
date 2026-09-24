#!/bin/bash
# AGENTS.md §4.1 falsification pass. Breaks one timing/ordering/interruption property at a time and records
# which tests notice. Runs in a throwaway worktree so a deliberate break can never be committed.
#
# Two things this must get right, both learned the hard way:
#   - Verify the mutation actually applied. A substitution that silently does not match leaves the code intact
#     and every test passes, which the harness would otherwise report as "the property is covered".
#   - Do not pass --quiet to Gradle. It suppresses the test-failure lines, so the harness sees no failures and
#     draws the same wrong conclusion. Exit status is the signal; names are read from the XML.
# Usage: tools/falsify-g2.sh <throwaway-worktree-path>
#
# Create the worktree from a COMMIT, not from a dirty tree: a staged-but-uncommitted test is not in the
# worktree, so the pass would measure the previous test set and report the new one as unevidenced.
#   git worktree add /tmp/falsify HEAD && tools/falsify-g2.sh /tmp/falsify && git worktree remove /tmp/falsify
W="${1:?usage: falsify-g2.sh <throwaway-worktree-path>}"
cd "$W" || exit 2
O=TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/kafkasource/KafkaSourceOwner.java
C=TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/kafkasource/WakeupController.java
# The adapter was the harness's blind spot, and that is exactly where the worst defect of the round lived: a
# wakeup reclassified as a structural failure, fatal in production, with every owner test green.
A=TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/kafkasource/KafkaConsumerSourcePort.java
RESULTS="$W/TrafficCapture/trafficReplayer/build/test-results/test"

run() {
  label="$1"
  if git diff --quiet -- "$O" "$C" "$A"; then
    echo "NOT-APPLIED  $label  <-- the mutation did not match; this says nothing about the tests"
    return
  fi
  rm -rf "$RESULTS"
  ./gradlew :TrafficCapture:trafficReplayer:test --tests '*kafkasource*' --tests '*ReplayerFixtureSelfTest*' \
      -x spotlessJavaCheck -x spotlessJavaApply > /tmp/falsify-run.log 2>&1
  status=$?
  failed=$(grep -ho 'testcase name="[^"]*"' "$RESULTS"/*.xml 2>/dev/null >/dev/null; \
           grep -l '<failure' "$RESULTS"/*.xml 2>/dev/null \
           | xargs -I{} sh -c 'grep -o "testcase name=\"[^\"]*\"" {} | head -0' 2>/dev/null; \
           grep -B1 '<failure' "$RESULTS"/*.xml 2>/dev/null \
           | grep -o 'testcase name="[^"]*"' | sed 's/testcase name=//' | tr -d '"' | sort -u | tr '\n' ' ')
  if [ "$status" -eq 0 ]; then
    echo "SURVIVED     $label  <-- no test noticed"
  elif grep -q "error:" /tmp/falsify-run.log; then
    echo "NO-COMPILE   $label  <-- the break does not compile, so it is not a usable mutation"
  else
    echo "CAUGHT       $label  by: ${failed:-(failed, names unavailable)}"
  fi
  git checkout -q -- "$O" "$C" "$A"
}

# 1. Pause after submitting the batch to intake, rather than before (kafkaLLD §5.3 ordering).
perl -0pi -e 's/(        if \(!state\.isKafkaPaused\(\)\) \{\n            port\.pause\(topicPartition\);\n            state\.setKafkaPaused\(true\);\n        \}\n)//' "$O"
perl -0pi -e 's/(submitRequired\(new ReplayIntakeInput\.PartitionRecordBatch\(requestId, stamped\)\);)/$1\n        if (!state.isKafkaPaused()) { port.pause(topicPartition); state.setKafkaPaused(true); }/' "$O"
run "pause moved after the batch reaches intake"

# 2. Never wake a poll for input that is already queued (the RUNNING-window gap).
perl -pi -e 's/if \(wakeupPending \|\| inputAlreadyQueued\) \{/if (wakeupPending) {/' "$C"
run "enterPoll ignores already-queued input"

# 3. Let WakeupException escape runOnce (the owner boundary).
perl -0pi -e 's/(catch \(WakeupException wokenToInspectTheQueue\) \{\n            log\.atTrace\(\)[^;]*;\n            )return;/${1}throw wokenToInspectTheQueue;/' "$O"
run "WakeupException escapes runOnce"

# 4. Drop the bound on the commit inside the revocation callback.
perl -pi -e 's/Duration\.ofNanos\(remainingNanos\)/Duration.ofDays(1)/' "$O"
run "revocation commit loses its bound"

# 5. Clear the cleanup gate without matching the generation that finished.
perl -pi -e 's/if \(outstanding == null \|\| !outstanding\.remove\(cleanup\.generation\(\)\)\) \{/if (outstanding == null) {/' "$O"
run "cleanup gate clears without matching the generation"

# 6. Resolve commit callbacks against the partition rather than the generation (the critical finding).
perl -pi -e 's/var currentGeneration = state != null && state\.generation\(\)\.equals\(detail\.generation\(\)\);/var currentGeneration = state != null;/' "$O"
run "commit callbacks matched by partition, not generation"

# 7. Register the cleanup obligation at retirement instead of before graceful cancellation. This is the exact
#    shape of the critical finding: a completion arriving during the grace interval matches nothing, is
#    discarded, and the obligation is then created for a cleanup that will never be reported again.
perl -0pi -e 's/            generations\.forEach\(generation -> cleanupOutstanding\n                \.computeIfAbsent\(generation\.topicPartition\(\), ignored -> new LinkedHashSet<>\(\)\)\n                \.add\(generation\)\);\n//' "$O"
perl -0pi -e 's/(        var unresolvedAtRetirement = inFlightCommitPositions\.remove\(topicPartition\) != null;\n        if \(state == null\) \{\n            return;\n        \}\n)/$1        cleanupOutstanding.computeIfAbsent(topicPartition, ignored -> new LinkedHashSet<>()).add(state.generation());\n/' "$O"
run "cleanup obligation registered at retirement, not before the grace wait"

# 8. Derive the one-commit-at-a-time rule from the per-partition map again. The map empties when a partition
#    retires, so an operation still in flight stops being visible and a second one can be issued beside it.
perl -pi -e 's/if \(stagedCommitPositions\.isEmpty\(\) \|\| commitOperationInFlight\) \{/if (stagedCommitPositions.isEmpty() || !inFlightCommitPositions.isEmpty()) {/' "$O"
perl -pi -e 's/^        if \(commitOperationInFlight\) \{/        if (!inFlightCommitPositions.isEmpty()) {/' "$O"
run "one-in-flight derived from the per-partition map"

# 9. Let a commit absorb the outstanding wakeup without telling the controller (kafkaLLD §5.4 on callback exit).
perl -pi -e 's/^            wakeupController\.onWakeupAbsorbedByProtectedOperation\(\);\n$//' "$O"
run "absorbed wakeup not recorded"

# 10. Start a revocation commit with no grace left, which §5.7 forbids.
perl -pi -e 's/if \(remainingNanos <= 0\) \{/if (false) {/' "$O"
run "revocation commit started with no grace remaining"

# 11. Skip force cancellation when every generation reported cleanup early (procCommit §9.2 step 7).
perl -pi -e 's/^            awaitGraceDeadlineProcessingInputs\(deadline, generations\);$/            var cleanEarly = awaitGraceDeadlineProcessingInputs(deadline, generations);/' "$O"
perl -0pi -e 's/(            generations\.forEach\(generation -> submitRequired\(\n                new ReplayIntakeInput\.ForceGenerationCancellation\(generation\)\n            \)\);)/            if (!cleanEarly) {\n$1\n            }/' "$O"
run "force cancellation skipped on the early-return path"

# 12. Let the adapter classify a wakeup instead of re-throwing it. WakeupException is a KafkaException and so a
#     RuntimeException, which means deleting the clause does not make it propagate -- it falls through to the
#     structural branch and kills the process on a routine queued input.
perl -0pi -e 's/        \} catch \(WakeupException absorbedByTheCommit\) \{\n(?:            \/\/[^\n]*\n)+            throw absorbedByTheCommit;\n//' "$A"
run "adapter no longer re-throws WakeupException"

# 13. Drop the resolution for an async submission the client refuses outright, which strands the one-in-flight slot.
perl -0pi -e 's/        \} catch \(RuntimeException refusedBeforeSubmission\) \{\n(?:            \/\/[^\n]*\n)+            onResolved\.accept\(classifyAsync\(refusedBeforeSubmission\)\);\n        \}/        } catch (RuntimeException refusedBeforeSubmission) {\n            \/\/ swallowed\n        }/' "$A"
run "async submission refused before registration never resolves"
