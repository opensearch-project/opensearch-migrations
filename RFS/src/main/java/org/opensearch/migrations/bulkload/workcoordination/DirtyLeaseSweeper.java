package org.opensearch.migrations.bulkload.workcoordination;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

/**
 * Startup sweep run by each worker process before any acquisition: find leases left live by a dead
 * predecessor process in the same pod (hard kills — heap OOM, cgroup SIGKILL, native crash — are
 * the only exits that leave a live lease behind), decide from the sidecar's crash-verdict file
 * whether the death was an OOM, and either escalate the item to a higher memory tier or release
 * it for an immediate same-tier retry.
 *
 * <p>The verdict-file poll is bounded (~10 s) and incurred only when a dirty lease exists with no
 * covering verdict record — the normal cold start and clean restart pay nothing.  The miss case is
 * fail-safe in the cheap direction: no verdict → release-only, no tier bump; a real OOM recurs and
 * is attributed on a later round.
 */
@Slf4j
public class DirtyLeaseSweeper {
    /** Covering-verdict skew allowance between kubelet and coordinator clocks. */
    public static final long CLOCK_SKEW_ALLOWANCE_SECONDS = 15;
    /** Total budget for waiting on the sidecar to append a covering verdict record. */
    public static final Duration DEFAULT_VERDICT_POLL_BUDGET = Duration.ofSeconds(10);
    private static final Duration VERDICT_POLL_INTERVAL = Duration.ofMillis(500);

    private DirtyLeaseSweeper() {}

    public enum Disposition { ESCALATE, RELEASE }

    /**
     * The sweep's classification rule as a pure function: a covering OOM verdict escalates;
     * a covering non-OOM verdict, or no covering verdict at all, releases at the same tier.
     */
    public static Disposition classify(Optional<CrashVerdictReader.VerdictRecord> coveringVerdict) {
        return coveringVerdict.map(v -> v.isOom() ? Disposition.ESCALATE : Disposition.RELEASE)
            .orElse(Disposition.RELEASE);
    }

    /** The pod-name prefix (including the trailing separator) of a {@code <podName>_<uuid>} worker id. */
    public static String podNamePrefixOf(String workerId) {
        int separator = workerId.lastIndexOf('_');
        if (separator < 0) {
            throw new IllegalArgumentException(
                "workerId '" + workerId + "' does not follow the <podName>_<uuid> convention");
        }
        return workerId.substring(0, separator + 1);
    }

    public static void runStartupSweep(
        OpenSearchWorkCoordinator coordinator,
        String workerId,
        int workerMemoryTier,
        Path verdictFileOrNull,
        Clock clock
    ) throws IOException, InterruptedException {
        runStartupSweep(coordinator, workerId, workerMemoryTier, verdictFileOrNull, clock,
            DEFAULT_VERDICT_POLL_BUDGET);
    }

    public static void runStartupSweep(
        OpenSearchWorkCoordinator coordinator,
        String workerId,
        int workerMemoryTier,
        Path verdictFileOrNull,
        Clock clock,
        Duration verdictPollBudget
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
        // One shared poll deadline bounds total startup delay even with several dirty leases.
        var pollDeadline = clock.instant().plus(verdictPollBudget);
        for (var lease : dirtyLeases) {
            var verdict = awaitCoveringVerdict(verdictFileOrNull, lease.getLeaseAcquiredAtEpochSeconds(),
                pollDeadline, clock);
            switch (classify(verdict)) {
                case ESCALATE: {
                    int newTier = workerMemoryTier + 1;
                    boolean applied = coordinator.escalateWorkItem(
                        lease.getWorkItemId(), lease.getLeaseHolderId(), newTier);
                    log.atWarn().setMessage(
                            "OOM verdict {} for dirty lease {}; escalate to tier >= {} {}")
                        .addArgument(verdict.orElse(null))
                        .addArgument(lease.getWorkItemId())
                        .addArgument(newTier)
                        .addArgument(applied ? "applied" : "no-op (lease moved or item completed)")
                        .log();
                    break;
                }
                case RELEASE:
                default: {
                    boolean applied = coordinator.releaseDirtyLease(
                        lease.getWorkItemId(), lease.getLeaseHolderId());
                    log.atWarn().setMessage(
                            "No OOM evidence for dirty lease {} (verdict={}); released at same tier: {}")
                        .addArgument(lease.getWorkItemId())
                        .addArgument(verdict.orElse(null))
                        .addArgument(applied ? "applied" : "no-op (lease moved or item completed)")
                        .log();
                    break;
                }
            }
        }
    }

    /**
     * Return the newest covering verdict, polling the file until {@code pollDeadline} only while
     * no covering record exists.  A null verdict file (sidecar not configured) returns empty
     * immediately so every dirty lease is release-only.
     */
    static Optional<CrashVerdictReader.VerdictRecord> awaitCoveringVerdict(
        Path verdictFileOrNull, long leaseAcquiredAtEpochSeconds, Instant pollDeadline, Clock clock
    ) throws InterruptedException {
        if (verdictFileOrNull == null) {
            return Optional.empty();
        }
        while (true) {
            var covering = CrashVerdictReader.newestCoveringVerdict(
                CrashVerdictReader.readVerdicts(verdictFileOrNull),
                leaseAcquiredAtEpochSeconds,
                CLOCK_SKEW_ALLOWANCE_SECONDS);
            if (covering.isPresent() || !clock.instant().isBefore(pollDeadline)) {
                return covering;
            }
            Thread.sleep(VERDICT_POLL_INTERVAL.toMillis());
        }
    }
}
