package org.opensearch.migrations.replay.util;

import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.TrafficReplayer;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
public class ActiveContextMonitor implements Runnable {

    private final BiConsumer<Level, Supplier<String>> logger;
    private final ActiveContextTracker globalContextTracker;
    private final ActiveContextTrackerByActivityType perActivityContextTracker;
    private final OrderedWorkerTracker orderedRequestTracker;
    private final int totalItemsToOutputLimit;
    private final long startTimeNanos;
    private final TreeMap<Duration,Level> ageToLevelEdgeMap;

    public ActiveContextMonitor(ActiveContextTracker globalContextTracker,
                                ActiveContextTrackerByActivityType perActivityContextTracker,
                                OrderedWorkerTracker orderedRequestTracker,
                                int totalItemsToOutputLimit,
                                Logger logger) {
        this(globalContextTracker, perActivityContextTracker, orderedRequestTracker, totalItemsToOutputLimit,
                (level,supplier)->logger.atLevel(level).setMessage(supplier).log());
    }

    public ActiveContextMonitor(ActiveContextTracker globalContextTracker,
                                ActiveContextTrackerByActivityType perActivityContextTracker,
                                OrderedWorkerTracker orderedRequestTracker,
                                int totalItemsToOutputLimit,
                                BiConsumer<Level, Supplier<String>> logger) {
        this(globalContextTracker, perActivityContextTracker, orderedRequestTracker, totalItemsToOutputLimit, logger,
                Map.of(
                        Level.ERROR, Duration.ofSeconds(600),
                        Level.WARN, Duration.ofSeconds(60),
                        Level.INFO, Duration.ofSeconds(30),
                        Level.DEBUG, Duration.ofSeconds(5),
                        Level.TRACE, Duration.ofSeconds(2)));
    }

    public ActiveContextMonitor(ActiveContextTracker globalContextTracker,
                                ActiveContextTrackerByActivityType perActivityContextTracker,
                                OrderedWorkerTracker orderedRequestTracker,
                                int totalItemsToOutputLimit,
                                BiConsumer<Level, Supplier<String>> logger,
                                Map<Level,Duration> levelShowsAgeOlderThanMap) {

        this.globalContextTracker = globalContextTracker;
        this.perActivityContextTracker = perActivityContextTracker;
        this.orderedRequestTracker = orderedRequestTracker;
        this.totalItemsToOutputLimit = totalItemsToOutputLimit;
        startTimeNanos = System.nanoTime();
        this.logger = logger;
        ageToLevelEdgeMap = levelShowsAgeOlderThanMap.entrySet().stream()
                .collect(Collectors.toMap(kvp->kvp.getValue(), kvp->kvp.getKey(),
                        (x,y) -> { throw Lombok.sneakyThrow(new IllegalStateException("Shouldn't have any merges")); },
                        TreeMap::new));
    }

    Duration getAge(long recordedNanoTime) {
        return Duration.ofNanos(System.nanoTime()-recordedNanoTime);
    }

    private void logContexts() {
        logActiveItems(globalContextTracker.getActiveScopesByAge().iterator(),
                this::getLogLevelForActiveContext,
                Object::toString);
        perActivityContextTracker.getActiveScopeTypes().forEach(c->
                logActiveItems(perActivityContextTracker.getOldestActiveScopes(c).iterator(),
                        this::getLogLevelForActiveContext,
                        Object::toString));
    }

    private Optional<Level> getLogLevelForActiveContext(IScopedInstrumentationAttributes activeContext) {
        var age = getAge(activeContext.getStartNanoTime());
        var floorElement = ageToLevelEdgeMap.floorEntry(age);
        log.info("floorElement="+floorElement+" for "+age);
        return Optional.ofNullable(floorElement).map(Map.Entry::getValue);
    }

    private <T> void logActiveItems(Iterator<T> activeScopeIterator,
                                    Function<T, Optional<Level>> getLevel,
                                    Function<T, String> getActiveLoggingMessage) {
        int numOutput = 0;
        try {
            while ((++numOutput <= totalItemsToOutputLimit) && activeScopeIterator.hasNext()) {
                var activeScope = activeScopeIterator.next();
                final var activeScopeCopy = activeScope;
                Optional<Level> levelForElementOp = getLevel.apply(activeScopeCopy);
                if (levelForElementOp.isEmpty()) {
                    break;
                }
                logger.accept(levelForElementOp.get(),()->getActiveLoggingMessage.apply(activeScopeCopy));
            }
        } catch (NoSuchElementException e) {
            if (numOutput == 0) {
                // work is asynchronously added/removed, so don't presume that other sets of work are also empty
                log.trace("No active work found, not outputting them to the active context logger");
            } // else, we're done
        }
    }

    @Override
    public void run() {
        logContexts();
    }
}
