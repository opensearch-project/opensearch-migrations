package org.opensearch.migrations.replay.util;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.Utils;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class ActiveContextMonitor implements Runnable {

    static final String INDENT = "  ";
    public static final String OLDEST_ITEMS_FROM_GROUP_LABEL_PREAMBLE = "Oldest of ";

    private final BiConsumer<Level, Supplier<String>> logger;
    private final ActiveContextTracker globalContextTracker;
    private final ActiveContextTrackerByActivityType perActivityContextTracker;
    private final OrderedWorkerTracker<Void> orderedRequestTracker;
    private final int totalItemsToOutputLimit;
    private final Function<DiagnosticTrackableCompletableFuture<String,Void>,String> formatWorkItem;

    private final Predicate<Level> logLevelIsEnabled;
    private final TreeMap<Duration,Level> ageToLevelEdgeMap;

    public ActiveContextMonitor(ActiveContextTracker globalContextTracker,
                                ActiveContextTrackerByActivityType perActivityContextTracker,
                                OrderedWorkerTracker<Void> orderedRequestTracker,
                                int totalItemsToOutputLimit,
                                Function<DiagnosticTrackableCompletableFuture<String, Void>, String> formatWorkItem,
                                Logger logger) {
        this(globalContextTracker, perActivityContextTracker, orderedRequestTracker, totalItemsToOutputLimit,
                formatWorkItem, (level, supplier)->logger.atLevel(level).setMessage(supplier).log(),
                logger::isEnabledForLevel);
    }

    public ActiveContextMonitor(ActiveContextTracker globalContextTracker,
                                ActiveContextTrackerByActivityType perActivityContextTracker,
                                OrderedWorkerTracker<Void> orderedRequestTracker,
                                int totalItemsToOutputLimit,
                                Function<DiagnosticTrackableCompletableFuture<String, Void>, String> formatWorkItem,
                                BiConsumer<Level, Supplier<String>> logger,
                                Predicate<Level> logLevelIsEnabled) {
        this(globalContextTracker, perActivityContextTracker, orderedRequestTracker, totalItemsToOutputLimit,
                formatWorkItem, logger, logLevelIsEnabled, Map.of(
                        Level.ERROR, Duration.ofSeconds(600),
                        Level.WARN, Duration.ofSeconds(60),
                        Level.INFO, Duration.ofSeconds(30),
                        Level.DEBUG, Duration.ofSeconds(5),
                        Level.TRACE, Duration.ofSeconds(2)));
    }

    public ActiveContextMonitor(ActiveContextTracker globalContextTracker,
                                ActiveContextTrackerByActivityType perActivityContextTracker,
                                OrderedWorkerTracker<Void> orderedRequestTracker,
                                int totalItemsToOutputLimit,
                                Function<DiagnosticTrackableCompletableFuture<String, Void>, String> formatWorkItem,
                                BiConsumer<Level, Supplier<String>> logger,
                                Predicate<Level> logLevelIsEnabled,
                                Map<Level,Duration> levelShowsAgeOlderThanMap) {

        this.globalContextTracker = globalContextTracker;
        this.perActivityContextTracker = perActivityContextTracker;
        this.orderedRequestTracker = orderedRequestTracker;
        this.totalItemsToOutputLimit = totalItemsToOutputLimit;
        this.logger = logger;
        this.formatWorkItem = formatWorkItem;
        this.logLevelIsEnabled = logLevelIsEnabled;
        ageToLevelEdgeMap = levelShowsAgeOlderThanMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey,
                        (x,y) -> { throw Lombok.sneakyThrow(new IllegalStateException("Shouldn't have any merges")); },
                        TreeMap::new));
    }

    Duration getAge(long recordedNanoTime) {
        return Duration.ofNanos(System.nanoTime()-recordedNanoTime);
    }

    /**
     * Try to print out the most valuable details at the end, assuming that a user is tailing a file that's
     * constantly being appended, and therefore could be harder to home in on the start of a block.
     */
    public void logTopOpenActivities() {
        logRequests().ifPresent(ll->logger.accept(ll, ()->"\n"));
        logTopActiveScopes().ifPresent(ll->logger.accept(ll, ()->"\n"));
        logTopActiveScopesByType().ifPresent(ll->logger.accept(ll, ()->"\n"));
    }

    public Optional<Level> logTopActiveScopesByType() {
        return perActivityContextTracker.getActiveScopeTypes().map(c->
                        gatherActivities(perActivityContextTracker.getOldestActiveScopes(c),
                                perActivityContextTracker.numScopesFor(c),
                                this::getLogLevelForActiveContext))
                .sorted(Comparator.comparingDouble(ActivitiesAndDepthsForLogging::getAverageContextDepth))
                .map(cad-> {
                    if (cad.items.isEmpty()) { return Optional.<Level>empty(); }
                    final var sample = cad.items.get(0);
                    logger.accept(getHigherLevel(Optional.of(sample.getValue()), Optional.of(Level.INFO)).get(), () ->
                            OLDEST_ITEMS_FROM_GROUP_LABEL_PREAMBLE + cad.totalScopes + " scopes that are past " +
                                    "thresholds for '" + sample.getKey().getActivityName() + "'");
                    cad.items.forEach(kvp->logger.accept(kvp.getValue(), ()->activityToString(kvp.getKey())));
                    return (Optional<Level>) Optional.of(cad.items.get(0).getValue());
                })
                .collect(Utils.foldLeft(Optional.<Level>empty(), ActiveContextMonitor::getHigherLevel));
    }

    private static Optional<Level> getHigherLevel(Optional<Level> aOuter, Optional<Level> bOuter) {
        return aOuter.map(a -> bOuter.filter(b -> a.toInt() <= b.toInt()).orElse(a))
                .or(() -> bOuter);
    }

    public Optional<Level> logRequests() {
        var orderedItems = orderedRequestTracker.orderedSet;
        return logActiveItems(orderedItems.stream(), orderedItems.size(),
                " outstanding requests that are past thresholds",
                tkaf -> getLogLevelForActiveContext(tkaf.nanoTimeKey),
                tkaf -> activityToString(tkaf, INDENT));
    }

    public Optional<Level> logTopActiveScopes() {
        return logActiveItems(globalContextTracker.getActiveScopesByAge(), globalContextTracker.size(),
                " GLOBAL scopes that are past thresholds",
                this::getLogLevelForActiveContext,
                this::activityToString);
    }

    @AllArgsConstructor
    @Getter
    private static class ActivitiesAndDepthsForLogging {
        ArrayList<Map.Entry<IScopedInstrumentationAttributes, Level>> items;
        double averageContextDepth;
        long totalScopes;
    }

    private ActivitiesAndDepthsForLogging
    gatherActivities(Stream<IScopedInstrumentationAttributes> oldestActiveScopes, long numScopes,
                     Function<IScopedInstrumentationAttributes, Optional<Level>> getLevel) {
        int depthSum = 0;
        var outList = new ArrayList<Map.Entry<IScopedInstrumentationAttributes, Level>>();
        try {
            var activeScopeIterator = oldestActiveScopes.iterator();
            while ((outList.size() < totalItemsToOutputLimit) && activeScopeIterator.hasNext()) {
                final var activeScope = activeScopeIterator.next();
                Optional<Level> levelForElementOp = getLevel.apply(activeScope);
                if (levelForElementOp.isEmpty()) {
                    break;
                }
                outList.add(new AbstractMap.SimpleImmutableEntry<>(activeScope, levelForElementOp.get()));
                depthSum += contextDepth(activeScope);
            }
        } catch (NoSuchElementException e) {
            if (outList.isEmpty()) {
                // work is asynchronously added/removed, so don't presume that other sets of work are also empty
                log.trace("No active work found, not outputting them to the active context logger");
            } // else, we're done
        }
        return new ActivitiesAndDepthsForLogging(outList, depthSum/(double)outList.size(), numScopes);
    }

    private int contextDepth(IScopedInstrumentationAttributes activeScope) {
        return contextDepth(activeScope, 0);
    }

    private int contextDepth(IScopedInstrumentationAttributes activeScope, int count) {
        return activeScope == null ? count : contextDepth(activeScope.getEnclosingScope(), count+1);
    }

    private String activityToString(IScopedInstrumentationAttributes context) {
        return activityToString(context, INDENT);
    }

    private String activityToString(OrderedWorkerTracker.TimeKeyAndFuture<Void> tkaf, String indent) {
        var timeStr = "age=" + getAge(tkaf.nanoTimeKey);
        return indent + timeStr + " " + formatWorkItem.apply(tkaf.future);
    }

    private String activityToString(IScopedInstrumentationAttributes context, String indent) {
        if (context == null) {
            return null;
        }
        var timeStr = "age=" + getAge(context.getStartTimeNano()) + ", start=" + context.getStartTimeInstant();
        var attributesStr = context.getPopulatedSpanAttributes().asMap().entrySet().stream()
                .map(kvp->kvp.getKey() + ": " + kvp.getValue())
                .collect(Collectors.joining(", "));
        return indent + timeStr + " " + context.getActivityName() + ": attribs={" + attributesStr + "}" +
                Optional.ofNullable(activityToString(context.getEnclosingScope(), indent + INDENT))
                        .map(s->"\n"+s).orElse("");
    }

    private Optional<Level> getLogLevelForActiveContext(IScopedInstrumentationAttributes activeContext) {
        return getLogLevelForActiveContext(activeContext.getStartTimeNano());
    }

    private Optional<Level> getLogLevelForActiveContext(long nanoTime) {
        var age = getAge(nanoTime);
        var floorElement = ageToLevelEdgeMap.floorEntry(age);
        return Optional.ofNullable(floorElement).map(Map.Entry::getValue).filter(logLevelIsEnabled);
    }

    private <T> Optional<Level>
    logActiveItems(Stream<T> activeItemStream, long totalItems, String trailingGroupLabel,
                   Function<T, Optional<Level>> getLevel,
                   Function<T, String> getActiveLoggingMessage) {
        int numOutput = 0;
        Optional<Level> firstLevel = Optional.empty();
        try {
            var activeItemIterator = activeItemStream.iterator();
            while ((++numOutput <= totalItemsToOutputLimit) && activeItemIterator.hasNext()) {
                final var activeItem = activeItemIterator.next();
                var levelForElementOp = getLevel.apply(activeItem);
                if (levelForElementOp.isEmpty()) {
                    break;
                }
                if (firstLevel.isEmpty()) {
                    firstLevel = levelForElementOp;
                }
                if (numOutput == 1) {
                    logger.accept(getHigherLevel(levelForElementOp, Optional.of(Level.INFO)).get(), () ->
                            OLDEST_ITEMS_FROM_GROUP_LABEL_PREAMBLE + totalItems + trailingGroupLabel);

                }
                logger.accept(levelForElementOp.get(),()->getActiveLoggingMessage.apply(activeItem));
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
        logTopOpenActivities();
    }
}
