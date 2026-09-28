package org.opensearch.migrations.replay.util;

// REBUILD-LIMBO(G5) -- legacy members in marked regions remain inert; the live G9.5 replacement
// follows the retained predecessor. Javadoc outside marked regions documents only its adjacent member.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: OrderedWorkerTracker . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G5)
/*

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.opensearch.migrations.Utils;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.utils.TrackedFuture;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.event.Level;

@Slf4j
public class ActiveContextMonitor implements Runnable {

    static final String INDENT = "  ";

    private final BiConsumer<Level, Supplier<String>> logger;
    private final ActiveContextTracker globalContextTracker;
    private final ActiveContextTrackerByActivityType perActivityContextTracker;
    private final OrderedWorkerTracker<Void> orderedRequestTracker;
    private final int totalItemsToOutputLimit;
    private final Function<TrackedFuture<String, Void>, String> formatWorkItem;

    private final Predicate<Level> logLevelIsEnabled;
    private final AtomicReference<TreeMap<Duration, Level>> ageToLevelEdgeMapRef;

    public ActiveContextMonitor(
        ActiveContextTracker globalContextTracker,
        ActiveContextTrackerByActivityType perActivityContextTracker,
        OrderedWorkerTracker<Void> orderedRequestTracker,
        int totalItemsToOutputLimit,
        Function<TrackedFuture<String, Void>, String> formatWorkItem,
        Logger logger
    ) {
        this(
            globalContextTracker,
            perActivityContextTracker,
            orderedRequestTracker,
            totalItemsToOutputLimit,
            formatWorkItem,
            (level, supplier) -> logger.atLevel(level).setMessage("{}").addArgument(supplier).log(),
            logger::isEnabledForLevel
        );
    }

    public ActiveContextMonitor(
        ActiveContextTracker globalContextTracker,
        ActiveContextTrackerByActivityType perActivityContextTracker,
        OrderedWorkerTracker<Void> orderedRequestTracker,
        int totalItemsToOutputLimit,
        Function<TrackedFuture<String, Void>, String> formatWorkItem,
        BiConsumer<Level, Supplier<String>> logger,
        Predicate<Level> logLevelIsEnabled
    ) {
        this(
            globalContextTracker,
            perActivityContextTracker,
            orderedRequestTracker,
            totalItemsToOutputLimit,
            formatWorkItem,
            logger,
            logLevelIsEnabled,
            Map.of(
                Level.ERROR,
                Duration.ofSeconds(600),
                Level.WARN,
                Duration.ofSeconds(60),
                Level.INFO,
                Duration.ofSeconds(30),
                Level.DEBUG,
                Duration.ofSeconds(5),
                Level.TRACE,
                Duration.ofSeconds(2)
            )
        );
    }

    public ActiveContextMonitor(
        ActiveContextTracker globalContextTracker,
        ActiveContextTrackerByActivityType perActivityContextTracker,
        OrderedWorkerTracker<Void> orderedRequestTracker,
        int totalItemsToOutputLimit,
        Function<TrackedFuture<String, Void>, String> formatWorkItem,
        BiConsumer<Level, Supplier<String>> logger,
        Predicate<Level> logLevelIsEnabled,
        Map<Level, Duration> levelShowsAgeOlderThanMap
    ) {

        this.globalContextTracker = globalContextTracker;
        this.perActivityContextTracker = perActivityContextTracker;
        this.orderedRequestTracker = orderedRequestTracker;
        this.totalItemsToOutputLimit = totalItemsToOutputLimit;
        this.logger = logger;
        this.formatWorkItem = formatWorkItem;
        this.logLevelIsEnabled = logLevelIsEnabled;
        ageToLevelEdgeMapRef = new AtomicReference<>();
        setAgeToLevelMap(levelShowsAgeOlderThanMap);
    }

*/
// REBUILD-LIMBO-END(G5)

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically reports correctness-relevant asynchronous work that remains registered past the configured
 * threshold. It reads immutable owner-published snapshots and cannot complete or mutate the operations.
 */
@Slf4j
public final class ActiveContextMonitor implements Runnable, AutoCloseable {
    public static final Duration DEFAULT_LONG_RUNNING_THRESHOLD = Duration.ofSeconds(30);
    public static final Duration DEFAULT_REPORT_INTERVAL = Duration.ofSeconds(30);
    static final String ACTIVE_WORK_LOGGER_NAME = "AllActiveWorkMonitor";

    private static final Logger ACTIVE_WORK_LOGGER =
        LoggerFactory.getLogger(ACTIVE_WORK_LOGGER_NAME);

    public record Report(
        OutstandingOperationRegistry.Snapshot snapshot,
        Duration elapsed
    ) {
        public Report {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(elapsed, "elapsed");
        }
    }

    record Status(
        Instant observedAt,
        int activeCount,
        int longRunningCount
    ) {
        public Status {
            Objects.requireNonNull(observedAt, "observedAt");
            if (activeCount < 0 || longRunningCount < 0
                || longRunningCount > activeCount) {
                throw new IllegalArgumentException(
                    "activity counts must satisfy 0 <= longRunningCount <= activeCount"
                );
            }
        }
    }

    private final Clock clock;
    private final Duration longRunningThreshold;
    private final Duration reportInterval;
    private final Supplier<List<OutstandingOperationRegistry.Snapshot>> snapshotSupplier;
    private final Consumer<Report> reporter;
    private final Consumer<Status> statusReporter;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ActiveContextMonitor(
        Clock clock,
        Duration longRunningThreshold,
        Supplier<List<OutstandingOperationRegistry.Snapshot>> snapshotSupplier,
        Consumer<Report> reporter
    ) {
        this(
            clock,
            longRunningThreshold,
            DEFAULT_REPORT_INTERVAL,
            snapshotSupplier,
            reporter,
            ignored -> {},
            null
        );
    }

    ActiveContextMonitor(
        Clock clock,
        Duration longRunningThreshold,
        Supplier<List<OutstandingOperationRegistry.Snapshot>> snapshotSupplier,
        Consumer<Report> reporter,
        Consumer<Status> statusReporter
    ) {
        this(
            clock,
            longRunningThreshold,
            DEFAULT_REPORT_INTERVAL,
            snapshotSupplier,
            reporter,
            statusReporter,
            null
        );
    }

    private ActiveContextMonitor(
        Clock clock,
        Duration longRunningThreshold,
        Duration reportInterval,
        Supplier<List<OutstandingOperationRegistry.Snapshot>> snapshotSupplier,
        Consumer<Report> reporter,
        Consumer<Status> statusReporter,
        ScheduledExecutorService scheduler
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.longRunningThreshold = requirePositive(longRunningThreshold, "longRunningThreshold");
        this.reportInterval = requirePositive(reportInterval, "reportInterval");
        this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.statusReporter = Objects.requireNonNull(statusReporter, "statusReporter");
        this.scheduler = scheduler;
    }

    public static ActiveContextMonitor system(
        Clock clock,
        Supplier<List<OutstandingOperationRegistry.Snapshot>> snapshotSupplier
    ) {
        var scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "replay-long-running-activity-monitor");
            thread.setDaemon(true);
            return thread;
        });
        return new ActiveContextMonitor(
            clock,
            DEFAULT_LONG_RUNNING_THRESHOLD,
            DEFAULT_REPORT_INTERVAL,
            snapshotSupplier,
            ActiveContextMonitor::logReport,
            ActiveContextMonitor::logStatus,
            scheduler
        );
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("activity monitor already started");
        }
        if (scheduler != null) {
            scheduler.scheduleAtFixedRate(
                this,
                longRunningThreshold.toNanos(),
                reportInterval.toNanos(),
                TimeUnit.NANOSECONDS
            );
        }
    }

    @Override
    public void run() {
        if (closed.get()) {
            return;
        }
        try {
            var now = clock.instant();
            var snapshots = List.copyOf(snapshotSupplier.get());
            var longRunningReports = snapshots.stream()
                .map(snapshot -> new Report(snapshot, Duration.between(snapshot.startTime(), now)))
                .filter(report -> !report.elapsed().isNegative())
                .filter(report -> report.elapsed().compareTo(longRunningThreshold) >= 0)
                .sorted(
                    Comparator.comparing(Report::elapsed).reversed()
                        .thenComparing(report -> report.snapshot().ownerIdentity())
                        .thenComparingLong(report -> report.snapshot().operationId())
                )
                .toList();
            statusReporter.accept(new Status(now, snapshots.size(), longRunningReports.size()));
            longRunningReports.forEach(reporter);
        } catch (Throwable failure) {
            log.error("Long-running replay activity reporting failed", failure);
        }
    }

    private static void logStatus(Status status) {
        ACTIVE_WORK_LOGGER.info(
            "Active replay work: observedAt={} active={} longRunning={}",
            status.observedAt(),
            status.activeCount(),
            status.longRunningCount()
        );
    }

    private static void logReport(Report report) {
        var snapshot = report.snapshot();
        var generation = snapshot.partitionGenerationId();
        ACTIVE_WORK_LOGGER.warn(
            "Long-running replay activity: partition={} generation={} record={} capturedConnection={} "
                + "connectionProcessing={} request={} owner={} operation={} elapsed={} reason={} "
                + "scheduledTargetTime={}",
            generation.topicPartition(),
            generation.localSequence(),
            snapshot.kafkaRecordId(),
            snapshot.connectionProcessingId().capturedConnectionId(),
            snapshot.connectionProcessingId(),
            snapshot.replayRequestId(),
            snapshot.ownerIdentity(),
            snapshot.operationType(),
            report.elapsed(),
            snapshot.waitReason(),
            snapshot.scheduledTargetTime()
        );
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
    /**
     * Supply new level-age edge values and convert them into the internal data structure for this class to use.
     * @param levelShowsAgeOlderThanMap
     */
// REBUILD-LIMBO-START(G5)
/*
    public void setAgeToLevelMap(Map<Level, Duration> levelShowsAgeOlderThanMap) {
        ageToLevelEdgeMapRef.set(
            new TreeMap<>(
                levelShowsAgeOlderThanMap.entrySet()
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey))
            )
        );
    }

    private static final org.slf4j.Logger compactSummaryLogger =
        org.slf4j.LoggerFactory.getLogger("ActiveContextSummary");

    // Activity types considered long-lived (reported as counts only, never flagged as breached)
    private static final java.util.Set<String> LONG_LIVED_ACTIVITIES = java.util.Set.of(
        "channel", "tcpConnection", "backPressureBlock"
    );

    // Expected max age for ephemeral activities (breached if exceeded)
    private static final java.util.Map<String, Duration> EXPECTED_MAX_AGE = java.util.Map.ofEntries(
        java.util.Map.entry("recordLifetime", Duration.ofMinutes(5)),
        java.util.Map.entry("trafficStreamLifetime", Duration.ofMinutes(5)),
        java.util.Map.entry("httpTransaction", Duration.ofMinutes(2)),
        java.util.Map.entry("accumulatingRequest", Duration.ofSeconds(30)),
        java.util.Map.entry("accumulatingResponse", Duration.ofSeconds(60)),
        java.util.Map.entry("transformation", Duration.ofSeconds(10)),
        java.util.Map.entry("scheduled", Duration.ofMinutes(5)),
        java.util.Map.entry("targetTransaction", Duration.ofSeconds(30)),
        java.util.Map.entry("requestConnecting", Duration.ofSeconds(10)),
        java.util.Map.entry("requestSending", Duration.ofSeconds(10)),
        java.util.Map.entry("waitingForResponse", Duration.ofSeconds(30)),
        java.util.Map.entry("receivingResponse", Duration.ofSeconds(30)),
        java.util.Map.entry("tupleComparison", Duration.ofSeconds(5)),
        java.util.Map.entry("touch", Duration.ofSeconds(10)),
        java.util.Map.entry("kafkaPoll", Duration.ofSeconds(10)),
        java.util.Map.entry("commit", Duration.ofSeconds(10)),
        java.util.Map.entry("kafkaCommit", Duration.ofSeconds(10)),
        java.util.Map.entry("readNextTrafficChunk", Duration.ofSeconds(10)),
        java.util.Map.entry("waitForNextBackPressureCheck", Duration.ofSeconds(30))
    );

*/
// REBUILD-LIMBO-END(G5)
    /**
     * Emit a compact one-line summary to the main log. Long-lived types are gauge counts;
     * ephemeral types that exceed their expected max age are listed with parent chain.
     */
// REBUILD-LIMBO-START(G5)
/*
    @SuppressWarnings("unchecked")
    public void logCompactSummary() {
        var sb = new StringBuilder();
        sb.append("scopes=").append(globalContextTracker.size());

        var longLived = new java.util.TreeMap<String, Long>();
        var ephemeral = new java.util.TreeMap<String, Long>();
        var breached = new java.util.ArrayList<String>();

        perActivityContextTracker.getActiveScopeTypes().forEach(type -> {
            var count = perActivityContextTracker.numScopesFor(type);
            var sample = perActivityContextTracker.getOldestActiveScopes(type).findFirst().orElse(null);
            if (sample == null) return;
            var activityName = sample.getActivityName();

            if (LONG_LIVED_ACTIVITIES.contains(activityName)) {
                longLived.put(activityName, count);
            } else {
                ephemeral.put(activityName, count);
                collectBreachedScopes(type, activityName, breached);
            }
        });

        sb.append(" longLived=").append(longLived);
        sb.append(" ephemeral=").append(ephemeral);
        sb.append(" breached=").append(breached.size());
        if (!breached.isEmpty()) {
            sb.append(": ");
            sb.append(String.join("; ", breached));
        }

        var level = breached.isEmpty() ? Level.INFO : Level.WARN;
        compactSummaryLogger.atLevel(level).setMessage("{}").addArgument(sb).log();
    }

    @SuppressWarnings("unchecked")
    private void collectBreachedScopes(Object type, String activityName, java.util.List<String> breached) {
        var expectedMax = EXPECTED_MAX_AGE.getOrDefault(activityName, Duration.ofMinutes(5));
        var typedType = (Class<IScopedInstrumentationAttributes>) type;
        perActivityContextTracker.getOldestActiveScopes(typedType)
            .limit(3)
            .forEach(scope -> {
                var age = getAge(scope.getStartTimeNano());
                if (age.compareTo(expectedMax) > 0) {
                    breached.add(describeBreach(scope, activityName, age));
                }
            });
    }

    private String describeBreach(IScopedInstrumentationAttributes scope, String activityName, Duration age) {
        var desc = new StringBuilder();
        desc.append(activityName).append(" age=").append(Utils.formatDurationInSeconds(age));
        var parent = scope.getEnclosingScope();
        int hops = 0;
        while (parent != null && hops < 3) {
            var parentName = parent.getActivityName();
            desc.append(" → ").append(parentName);
            parent.getPopulatedSpanAttributes().asMap().entrySet().stream()
                .filter(e -> e.getKey().getKey().contains("connectionId")
                    || e.getKey().getKey().contains("channelKey"))
                .findFirst()
                .ifPresent(e -> desc.append(" ").append(e.getKey().getKey())
                    .append("=").append(e.getValue()));
            if (LONG_LIVED_ACTIVITIES.contains(parentName)) break;
            parent = parent.getEnclosingScope();
            hops++;
        }
        return desc.toString();
    }

    Duration getAge(long recordedNanoTime) {
        return Duration.ofNanos(System.nanoTime() - recordedNanoTime);
    }

*/
// REBUILD-LIMBO-END(G5)
    /**
     * Try to print out the most valuable details at the end, assuming that a user is tailing a file that's
     * constantly being appended, and therefore could be harder to home in on the start of a block.
     */
// REBUILD-LIMBO-START(G5)
/*
    public void logTopOpenActivities(boolean dedupCommonTraces) {
        logRequests().ifPresent(ll -> logger.accept(ll, () -> "\n"));
        logScopes(dedupCommonTraces);
    }

    public void logScopes(boolean dedupCommonTraces) {
        var scopesSeen = dedupCommonTraces ? new HashSet<IScopedInstrumentationAttributes>() : null;
        var activitiesDeferral = getTopActivities(scopesSeen);
        logTopActiveScopes(scopesSeen).ifPresent(ll -> logger.accept(ll, () -> "\n"));
        logTopActiveScopesByType(activitiesDeferral).ifPresent(ll -> logger.accept(ll, () -> "\n"));
    }

    private Optional<Level> logTopActiveScopesByType(Stream<ActivitiesAndDepthsForLogging> stream) {
        return stream.map(cad -> {
            if (cad.items.isEmpty()) {
                return Optional.<Level>empty();
            }
            final var sample = cad.items.get(0);
            logger.accept(
                getHigherLevel(Optional.of(sample.getLevel()), Optional.of(Level.INFO)).get(),
                () -> "Oldest of "
                    + cad.totalScopes
                    + " scopes for '"
                    + sample.getScope().getActivityName()
                    + "'"
                    + " that are past thresholds that are not otherwise reported below "
            );
            final var numItems = cad.items.size();
            IntStream.range(0, numItems)
                .mapToObj(i -> cad.items.get(numItems - i - 1))
                .forEach(
                    kvp -> logger.accept(
                        kvp.getLevel(),
                        () -> activityToString(kvp.getScope(), kvp.ancestorDepthBeforeRedundancy)
                    )
                );
            return (Optional<Level>) Optional.of(cad.items.get(0).getLevel());
        }).collect(Utils.foldLeft(Optional.<Level>empty(), ActiveContextMonitor::getHigherLevel));
    }

    private Stream<ActivitiesAndDepthsForLogging> getTopActivities(
        Set<IScopedInstrumentationAttributes> scopesSeenSoFar
    ) {
        var reverseOrderedList = perActivityContextTracker.getActiveScopeTypes()
            .map(
                c -> Map.<
                    Class<IScopedInstrumentationAttributes>,
                    Supplier<Stream<IScopedInstrumentationAttributes>>>entry(
                        c,
                        () -> perActivityContextTracker.getOldestActiveScopes(c)
                    )
            )
            .sorted(
                Comparator.comparingInt(
                    kvp -> -1 * kvp.getValue().get().findAny().map(ActiveContextMonitor::contextDepth).orElse(0)
                )
            )
            .map(
                kvp -> gatherActivities(
                    scopesSeenSoFar,
                    kvp.getValue().get(),
                    perActivityContextTracker.numScopesFor(kvp.getKey()),
                    this::getLogLevelForActiveContext
                )
            )
            .collect(Collectors.toCollection(ArrayList::new));
        Collections.reverse(reverseOrderedList);
        return reverseOrderedList.stream();
    }

    private static Optional<Level> getHigherLevel(Optional<Level> aOuter, Optional<Level> bOuter) {
        return aOuter.map(a -> bOuter.filter(b -> a.toInt() <= b.toInt()).orElse(a)).or(() -> bOuter);
    }

    public Optional<Level> logRequests() {
        var orderedItems = orderedRequestTracker.orderedSet;
        return logActiveItems(
            null,
            orderedItems.stream(),
            orderedItems.size(),
            " outstanding requests that are past thresholds",
            tkaf -> getLogLevelForActiveContext(tkaf.nanoTimeKey),
            this::activityToString
        );
    }

    private Optional<Level> logTopActiveScopes(Set<IScopedInstrumentationAttributes> scopesSeen) {
        return logActiveItems(
            scopesSeen,
            globalContextTracker.getActiveScopesByAge(),
            globalContextTracker.size(),
            " GLOBAL scopes that are past thresholds that are not otherwise reported below",
            this::getLogLevelForActiveContext,
            ctx -> activityToString(ctx, scanUntilAncestorSeen(scopesSeen, ctx, 0))
        );
    }

    @AllArgsConstructor
    @Getter
    private static class ScopePath {
        private final IScopedInstrumentationAttributes scope;
        private final int ancestorDepthBeforeRedundancy;
        private final Level level;
    }

    @AllArgsConstructor
    @Getter
    private static class ActivitiesAndDepthsForLogging {
        ArrayList<ScopePath> items;
        double averageContextDepth;
        long totalScopes;
    }

    private ActivitiesAndDepthsForLogging gatherActivities(
        Set<IScopedInstrumentationAttributes> scopesSeenSoFar,
        Stream<IScopedInstrumentationAttributes> oldestActiveScopes,
        long numScopes,
        Function<IScopedInstrumentationAttributes, Optional<Level>> getLevel
    ) {
        int depthSum = 0;
        var outList = new ArrayList<ScopePath>();
        try {
            var activeScopeIterator = oldestActiveScopes.iterator();
            while ((outList.size() < totalItemsToOutputLimit) && activeScopeIterator.hasNext()) {
                final var activeScope = activeScopeIterator.next();
                Optional<Level> levelForElementOp = getLevel.apply(activeScope);
                if (levelForElementOp.isEmpty()) {
                    break;
                }
                var ancestorDepth = scanUntilAncestorSeen(scopesSeenSoFar, activeScope, 0);
                if (ancestorDepth != 0) {
                    outList.add(new ScopePath(activeScope, ancestorDepth, levelForElementOp.get()));
                    depthSum += contextDepth(activeScope);
                }
            }
        } catch (NoSuchElementException e) {
            if (outList.isEmpty()) {
                // work is asynchronously added/removed, so don't presume that other sets of work are also empty
                log.trace("No active work found, not outputting them to the active context logger");
            } // else, we're done
        }
        return new ActivitiesAndDepthsForLogging(outList, depthSum / (double) outList.size(), numScopes);
    }

    private static int scanUntilAncestorSeen(
        Set<IScopedInstrumentationAttributes> ctxSeenSoFar,
        IScopedInstrumentationAttributes ctx,
        int depth
    ) {
        // if we added an item, then recurse if the parent was non-null; otherwise return depth
        if (ctxSeenSoFar == null) {
            return -1;
        } else if (!ctxSeenSoFar.add(ctx)) {
            return depth;
        }
        ++depth;
        var p = ctx.getEnclosingScope();
        return p == null ? depth : scanUntilAncestorSeen(ctxSeenSoFar, p, depth);
    }

    private static int contextDepth(IScopedInstrumentationAttributes activeScope) {
        return contextDepth(activeScope, 0);
    }

    private static int contextDepth(IScopedInstrumentationAttributes activeScope, int count) {
        return activeScope == null ? count : contextDepth(activeScope.getEnclosingScope(), count + 1);
    }

    private String activityToString(OrderedWorkerTracker.TimeKeyAndFuture<Void> tkaf) {
        return INDENT + "age=" + getAge(tkaf.nanoTimeKey) + " " + formatWorkItem.apply(tkaf.future);
    }

    private String activityToString(IScopedInstrumentationAttributes context, int depthToInclude) {
        return activityToString(context, depthToInclude, INDENT);
    }

    private String activityToString(IScopedInstrumentationAttributes ctx, int depthToInclude, String indent) {
        if (ctx == null) {
            return "";
        }
        var idStr = depthToInclude < 0 ? null : "<<" + System.identityHashCode(ctx) + ">>";
        if (depthToInclude == 0) {
            return " parentRef=" + idStr + "...";
        }
        var timeStr = "age=" + getAge(ctx.getStartTimeNano()) + ", start=" + ctx.getStartTimeInstant();
        var attributesStr = ctx.getPopulatedSpanAttributes()
            .asMap()
            .entrySet()
            .stream()
            .map(kvp -> kvp.getKey() + ": " + kvp.getValue())
            .collect(Collectors.joining(", "));
        var parentStr = activityToString(ctx.getEnclosingScope(), depthToInclude - 1, indent + INDENT);
        return indent
            + timeStr
            + Optional.ofNullable(idStr).map(s -> " id=" + s).orElse("")
            + " "
            + ctx.getActivityName()
            + ": attribs={"
            + attributesStr
            + "}"
            + (!parentStr.isEmpty() && depthToInclude != 1 ? "\n" : "")
            + parentStr;
    }

    private Optional<Level> getLogLevelForActiveContext(IScopedInstrumentationAttributes activeContext) {
        return getLogLevelForActiveContext(activeContext.getStartTimeNano());
    }

    private Optional<Level> getLogLevelForActiveContext(long nanoTime) {
        var age = getAge(nanoTime);
        var ageToLevelEdgeMap = ageToLevelEdgeMapRef.get();
        var floorElement = ageToLevelEdgeMap.floorEntry(age);
        return Optional.ofNullable(floorElement).map(Map.Entry::getValue).filter(logLevelIsEnabled);
    }

    private <T> Optional<Level> logActiveItems(
        Set<T> itemsSeenSoFar,
        Stream<T> activeItemStream,
        long totalItems,
        String trailingGroupLabel,
        Function<T, Optional<Level>> getLevel,
        Function<T, String> getActiveLoggingMessage
    ) {
        int numOutput = 0;
        Optional<Level> firstLevel = Optional.empty();
        try {
            var activeItemIterator = activeItemStream.iterator();
            while (activeItemIterator.hasNext() && (numOutput < totalItemsToOutputLimit)) {
                final var activeItem = activeItemIterator.next();
                var levelForElementOp = getLevel.apply(activeItem);
                if (levelForElementOp.isEmpty()) {
                    break;
                }
                if (Optional.ofNullable(itemsSeenSoFar).map(s -> s.contains(activeItem)).orElse(false)) {
                    continue;
                }
                if (firstLevel.isEmpty()) {
                    firstLevel = levelForElementOp;
                }
                if (numOutput++ == 0) {
                    logger.accept(
                        getHigherLevel(levelForElementOp, Optional.of(Level.INFO))
                            .orElseThrow(IllegalStateException::new),
                        () -> "Oldest of " + totalItems + trailingGroupLabel
                    );
                }
                logger.accept(levelForElementOp.get(), () -> getActiveLoggingMessage.apply(activeItem));
            }
        } catch (NoSuchElementException e) {
            if (numOutput == 0) {
                // work is asynchronously added/removed, so don't presume that other sets of work are also empty
                log.trace("No active work found, not outputting them to the active context logger");
            } // else, we're done
        }
        return firstLevel;
    }

    @Override
    public void run() {
        logTopOpenActivities(true);
    }
}

*/
// REBUILD-LIMBO-END(G5)
