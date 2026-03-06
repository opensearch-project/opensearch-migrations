package org.opensearch.migrations.replay.util;

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

    /**
     * Supply new level-age edge values and convert them into the internal data structure for this class to use.
     * @param levelShowsAgeOlderThanMap
     */
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

    /**
     * Emit a compact one-line summary to the main log. Long-lived types are gauge counts;
     * ephemeral types that exceed their expected max age are listed with parent chain.
     */
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
        desc.append(activityName).append(" age=").append(formatDuration(age));
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

    private static String formatDuration(Duration d) {
        long totalSeconds = d.getSeconds();
        if (totalSeconds < 0) return "-" + formatDuration(d.negated());
        if (totalSeconds < 60) return totalSeconds + "s";
        if (totalSeconds < 3600) return (totalSeconds / 60) + "m" + (totalSeconds % 60) + "s";
        return (totalSeconds / 3600) + "h" + ((totalSeconds % 3600) / 60) + "m";
    }

    Duration getAge(long recordedNanoTime) {
        return Duration.ofNanos(System.nanoTime() - recordedNanoTime);
    }

    /**
     * Try to print out the most valuable details at the end, assuming that a user is tailing a file that's
     * constantly being appended, and therefore could be harder to home in on the start of a block.
     */
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
