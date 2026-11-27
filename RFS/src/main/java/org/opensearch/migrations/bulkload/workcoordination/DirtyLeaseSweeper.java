package org.opensearch.migrations.bulkload.workcoordination;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntConsumer;

import lombok.extern.slf4j.Slf4j;

/**
 * Startup sweep run by each worker process before any acquisition: find leases left live by a dead
 * predecessor process in the same pod (hard kills — heap OOM, SIGKILL, native crash — are the only
 * exits that leave a live lease behind) and either escalate the item to a higher memory tier or
 * release it for an immediate same-tier retry.
 *
 * <p>Why each predecessor died comes from the in-container supervisor
 * (runJavaWithClasspathWithRepeat.sh), which launches every worker with an instance id and passes
 * the next worker the recent crashes as {@code <instanceId>=<exit code or OOMKilled>} entries. A
 * lease names its holder as {@code <podName>_<instanceId>}, so attribution is exact. A dirty lease
 * whose holder isn't in that list (for example one left by the previous incarnation of the
 * container) has no evidence and is released at the same tier: a real OOM recurs and is attributed
 * on a later round.
 */
@Slf4j
public class DirtyLeaseSweeper {
    private DirtyLeaseSweeper() {}

    public enum Disposition { ESCALATE, RELEASE }

    /**
     * The classification rule: only positive OOM evidence (a heap-OOM exit 3, or OOMKilled) climbs
     * the memory ladder. Any other crash, a bare 137 included, and the no-evidence case release at
     * the same tier, so a fleet-wide non-memory crasher can never multiply the run's cost.
     */
    public static Disposition classify(Optional<String> predecessorOutcome) {
        return predecessorOutcome
            .map(outcome -> "3".equals(outcome) || "OOMKilled".equals(outcome)
                ? Disposition.ESCALATE : Disposition.RELEASE)
            .orElse(Disposition.RELEASE);
    }

    /**
     * Parses the supervisor's {@code id=outcome[,id=outcome...]} list. Blank input means no known
     * crashes; malformed entries are skipped.
     */
    public static Map<String, String> parsePredecessorCrashes(String spec) {
        var crashes = new LinkedHashMap<String, String>();
        if (spec == null || spec.isBlank()) {
            return crashes;
        }
        for (var entry : spec.split(",")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                continue;
            }
            crashes.put(entry.substring(0, separator).trim(), entry.substring(separator + 1).trim());
        }
        return crashes;
    }

    /** The pod-name prefix (including the trailing separator) of a {@code <podName>_<instanceId>} worker id. */
    public static String podNamePrefixOf(String workerId) {
        int separator = workerId.lastIndexOf('_');
        if (separator < 0) {
            throw new IllegalArgumentException(
                "workerId '" + workerId + "' does not follow the <podName>_<instanceId> convention");
        }
        return workerId.substring(0, separator + 1);
    }

    /** The instance id (everything after the last separator) of a {@code <podName>_<instanceId>} worker id. */
    public static String instanceIdOf(String workerId) {
        return workerId.substring(podNamePrefixOf(workerId).length());
    }

    /**
     * @param predecessorCrashes how recent predecessors in this container died, keyed by instance id
     * @param onEscalation invoked with the destination tier for each escalation actually applied
     * @param onRelease invoked with the (unchanged) tier for each same-tier release actually applied
     */
    public static void runStartupSweep(
        OpenSearchWorkCoordinator coordinator,
        String workerId,
        int workerMemoryTier,
        Map<String, String> predecessorCrashes,
        IntConsumer onEscalation,
        IntConsumer onRelease
    ) throws IOException, InterruptedException {
        List<OpenSearchWorkCoordinator.DirtyLease> dirtyLeases;
        try {
            dirtyLeases = coordinator.findDirtyLeases(podNamePrefixOf(workerId), workerId);
        } catch (IOException e) {
            // First start: the coordination index may not exist yet.  Nothing can be dirty then.
            log.atInfo().setCause(e)
                .setMessage("Dirty-lease sweep query failed (coordination index may not exist yet); skipping sweep")
                .log();
            return;
        }
        if (dirtyLeases.isEmpty()) {
            log.atDebug().setMessage("Dirty-lease sweep found nothing to do").log();
            return;
        }
        log.atWarn().setMessage("Dirty-lease sweep found {} live lease(s) from dead predecessor(s) in this pod: {}")
            .addArgument(dirtyLeases.size()).addArgument(dirtyLeases).log();
        for (var lease : dirtyLeases) {
            recoverLease(coordinator, workerMemoryTier, predecessorCrashes, onEscalation, onRelease, lease);
        }
    }

    private static void recoverLease(
        OpenSearchWorkCoordinator coordinator,
        int workerMemoryTier,
        Map<String, String> predecessorCrashes,
        IntConsumer onEscalation,
        IntConsumer onRelease,
        OpenSearchWorkCoordinator.DirtyLease lease
    ) throws IOException, InterruptedException {
        var outcome = Optional.ofNullable(
            predecessorCrashes.get(instanceIdOf(lease.getLeaseHolderId())));
        if (classify(outcome) == Disposition.ESCALATE) {
            int newTier = workerMemoryTier + 1;
            boolean applied = coordinator.escalateWorkItem(
                lease.getWorkItemId(), lease.getLeaseHolderId(), newTier);
            if (applied) {
                onEscalation.accept(newTier);
            }
            log.atWarn().setMessage("Predecessor {} died of OOM ({}) holding {}; escalate to tier >= {} {}")
                .addArgument(lease.getLeaseHolderId())
                .addArgument(outcome.orElse(null))
                .addArgument(lease.getWorkItemId())
                .addArgument(newTier)
                .addArgument(applied ? "applied" : "no-op (lease moved or item completed)")
                .log();
        } else {
            boolean applied = coordinator.releaseDirtyLease(
                lease.getWorkItemId(), lease.getLeaseHolderId());
            if (applied) {
                onRelease.accept(workerMemoryTier);
            }
            log.atWarn().setMessage("No OOM evidence for {} held by {} (outcome={}); released at same tier: {}")
                .addArgument(lease.getWorkItemId())
                .addArgument(lease.getLeaseHolderId())
                .addArgument(outcome.orElse("unknown"))
                .addArgument(applied ? "applied" : "no-op (lease moved or item completed)")
                .log();
        }
    }
}
