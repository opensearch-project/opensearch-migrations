package org.opensearch.migrations.replay.util;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.utils.TrackedFuture;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

@Slf4j
class ActiveContextMonitorTest {

    private Pattern makeSuppressDedupedPattern(
        int visibleRequestCount,
        int requestScopeCount,
        int visibleScopeCount,
        int totalScopeCount
    ) {
        var sb = new StringBuilder();
        sb.append("^");
        sb.append("Oldest of " + requestScopeCount + " outstanding requests that are past thresholds.*\\n");
        for (int i = 0; i < visibleRequestCount; ++i) {
            sb.append(indent(1) + "age=P.*S.*\\n");
        }

        sb.append("Oldest of " + totalScopeCount + " GLOBAL scopes that are past thresholds.*\\n");
        sb.append(indent(1) + "age=P.*S, start=.*Z id=<<.*>> httpTransaction: attribs=\\{.*\\}.*\\n");

        sb.append("Oldest of " + requestScopeCount + " scopes.*'trafficStreamLifetime'.*\\n");
        sb.append(indent(1) + "age=P.*S, start=.*Z id=<<.*>> trafficStreamLifetime: attribs=\\{.*\\}.*\\n");

        sb.append("Oldest of " + requestScopeCount + " scopes .* 'httpTransaction'.*\\n");
        for (int i = 0; i < visibleRequestCount; ++i) {
            sb.append(indent(1) + "age=P.*S, start=.*Z id=<<.*>> httpTransaction: attribs=\\{.*\\}.*\\n");
            sb.append(indent(2) + "age=P.*S, start=.*Z id=<<.*>> trafficStreamLifetime: attribs=\\{.*\\}.*\\n");
        }
        sb.append(indent(3) + "age=P.*S, start=.*Z id=<<.*>> channel: attribs=\\{.*\\}.*\\n");

        sb.append("$");

        return Pattern.compile(sb.toString(), Pattern.MULTILINE);
    }

    @Test
    void testThatCommonAncestorsAreShownJustEnough() throws Exception {
        var loggedEntries = new ArrayList<Map.Entry<Level, String>>();
        var globalContextTracker = new ActiveContextTracker();
        var perActivityContextTracker = new ActiveContextTrackerByActivityType();
        var orderedWorkerTracker = new OrderedWorkerTracker<Void>();
        var compositeTracker = new CompositeContextTracker(globalContextTracker, perActivityContextTracker);
        var durationLevelMap = new HashMap<>(
            Map.of(
                Level.ERROR,
                Duration.ofMillis(5),
                Level.WARN,
                Duration.ofMillis(4),
                Level.INFO,
                Duration.ofMillis(3),
                Level.DEBUG,
                Duration.ofMillis(2),
                Level.TRACE,
                Duration.ofMillis(1)
            )
        );
        var acm = new ActiveContextMonitor(
            globalContextTracker,
            perActivityContextTracker,
            orderedWorkerTracker,
            2,
            dtfc -> "",
            (Level level, Supplier<String> msgSupplier) -> loggedEntries.add(Map.entry(level, msgSupplier.get())),
            level -> level == Level.ERROR,
            durationLevelMap
        );
        try (var testContext = TestContext.noOtelTracking()) {
            for (int i = 0; i < 3; ++i) {
                var rc = testContext.getTestConnectionRequestContext("connection-0", i);
                addContexts(compositeTracker, rc);
                final var idx = i;
                orderedWorkerTracker.put(
                    rc.getReplayerRequestKey(),
                    new TrackedFuture<>(new CompletableFuture<>(), () -> "dummy #" + idx)
                );
            }
            Thread.sleep(10);
            acm.run();
            checkAllEntriesAreErrorLevel(loggedEntries);
            checkAndClearLines(loggedEntries, makeSuppressDedupedPattern(2, 3, 1, 7));
        }
    }

    @Test
    void testThatNewerItemsArentInspected() throws Exception {
        final var TRANCHE_SIZE = 10;
        var loggedEntries = new ArrayList<Map.Entry<Level, String>>();
        var globalContextTracker = new ActiveContextTracker();
        var perActivityContextTracker = new ActiveContextTrackerByActivityType();
        var orderedWorkerTracker = new OrderedWorkerTracker<Void>();
        var compositeTracker = new CompositeContextTracker(globalContextTracker, perActivityContextTracker);
        var durationLevelMap = new HashMap<>(
            Map.of(
                Level.ERROR,
                Duration.ofMillis(1000),
                Level.WARN,
                Duration.ofMillis(4),
                Level.INFO,
                Duration.ofMillis(3),
                Level.DEBUG,
                Duration.ofMillis(2),
                Level.TRACE,
                Duration.ofMillis(1)
            )
        );
        var acm = new ActiveContextMonitor(
            globalContextTracker,
            perActivityContextTracker,
            orderedWorkerTracker,
            2,
            dtfc -> "",
            (Level level, Supplier<String> msgSupplier) -> loggedEntries.add(Map.entry(level, msgSupplier.get())),
            level -> level == Level.ERROR,
            durationLevelMap
        );
        try (var testContext = TestContext.noOtelTracking()) {
            for (int i = 0; i < TRANCHE_SIZE; ++i) {
                var rc = testContext.getTestConnectionRequestContext(i);
                addContexts(compositeTracker, rc);
                final var idx = i;
                orderedWorkerTracker.put(
                    rc.getReplayerRequestKey(),
                    new TrackedFuture<>(new CompletableFuture<>(), () -> "dummy #" + idx)
                );
            }
            var startTime = System.nanoTime();
            acm.logTopOpenActivities(false);
            checkAllEntriesAreErrorLevel(loggedEntries);
            checkAndClearLines(loggedEntries, Pattern.compile("\\n"));

            Thread.sleep(10);
            durationLevelMap.put(Level.ERROR, Duration.ofNanos(System.nanoTime() - startTime));
            acm.setAgeToLevelMap(durationLevelMap);
            acm.logTopOpenActivities(false);
            checkAllEntriesAreErrorLevel(loggedEntries);
            checkAndClearLines(loggedEntries, makePattern(2, 10, 2, 21));

            for (int i = 0; i < TRANCHE_SIZE; ++i) {
                var rc = testContext.getTestConnectionRequestContext(i + TRANCHE_SIZE);
                addContexts(compositeTracker, rc);
                orderedWorkerTracker.put(
                    rc.getReplayerRequestKey(),
                    new TrackedFuture<>(new CompletableFuture<>(), () -> "dummy obj")
                );
            }

            acm.logTopOpenActivities(false);
            checkAllEntriesAreErrorLevel(loggedEntries);
            checkAndClearLines(loggedEntries, makePattern(2, 20, 2, 41));
        }
    }

    private static void checkAllEntriesAreErrorLevel(ArrayList<Map.Entry<Level, String>> loggedEntries) {
        Assertions.assertEquals(
            "",
            loggedEntries.stream()
                .filter(kvp -> !kvp.getValue().startsWith("Oldest of "))
                .filter(kvp -> kvp.getKey() != Level.ERROR)
                .map(kvp -> kvp.getKey().toString())
                .collect(Collectors.joining()),
            "expected all levels to be ERROR and for them to be filtered out in this check"
        );
    }

    @Test
    void test() throws Exception {
        var loggedEntries = new ArrayList<Map.Entry<Level, String>>();
        var globalContextTracker = new ActiveContextTracker();
        var perActivityContextTracker = new ActiveContextTrackerByActivityType();
        var orderedWorkerTracker = new OrderedWorkerTracker<Void>();
        var compositeTracker = new CompositeContextTracker(globalContextTracker, perActivityContextTracker);
        var acm = new ActiveContextMonitor(
            globalContextTracker,
            perActivityContextTracker,
            orderedWorkerTracker,
            2,
            dtfc -> "",
            (Level level, Supplier<String> msgSupplier) -> loggedEntries.add(Map.entry(level, msgSupplier.get())),
            level -> true,
            Map.of(
                Level.ERROR,
                Duration.ofMillis(10000),
                Level.WARN,
                Duration.ofMillis(80),
                Level.INFO,
                Duration.ofMillis(60),
                Level.DEBUG,
                Duration.ofMillis(40),
                Level.TRACE,
                Duration.ofMillis(20)
            )
        );

        var patternWith0 = makePattern(0, 0, 0, 0);
        var patternWith1 = makePattern(1, 1, 3, 3);
        var patternWith2 = makePattern(2, 2, 5, 5);
        var patternWith3 = makePattern(2, 3, 7, 7);

        try (var testContext = TestContext.noOtelTracking()) {
            var requestContext1 = testContext.getTestConnectionRequestContext(0);
            orderedWorkerTracker.put(
                requestContext1.getReplayerRequestKey(),
                new TrackedFuture<>(new CompletableFuture<>(), () -> "dummy 1")
            );

            addContexts(compositeTracker, requestContext1);
            Thread.sleep(20);
            acm.logTopOpenActivities(false);
            checkAndClearLines(loggedEntries, patternWith1);

            var requestContext2 = testContext.getTestConnectionRequestContext(0);
            orderedWorkerTracker.put(
                requestContext2.getReplayerRequestKey(),
                new TrackedFuture<>(new CompletableFuture<>(), () -> "dummy 2")
            );

            addContexts(compositeTracker, requestContext2);
            Thread.sleep(20);
            acm.logTopOpenActivities(false);
            checkAndClearLines(loggedEntries, patternWith2);

            var requestContext3 = testContext.getTestConnectionRequestContext(0);
            orderedWorkerTracker.put(
                requestContext3.getReplayerRequestKey(),
                new TrackedFuture<>(new CompletableFuture<>(), () -> "dummy 3")
            );

            addContexts(compositeTracker, requestContext3);
            Thread.sleep(20);
            acm.logTopOpenActivities(false);
            checkAndClearLines(loggedEntries, patternWith3);

            Thread.sleep(50);
            acm.logTopOpenActivities(false);
            checkAndClearLines(loggedEntries, patternWith3);

            compositeTracker.onContextClosed(requestContext1);
            compositeTracker.onContextClosed(requestContext1.getEnclosingScope());
            orderedWorkerTracker.remove(orderedWorkerTracker.getRemainingItems().findFirst().get().getKey());
            acm.logTopOpenActivities(false);
            checkAndClearLines(loggedEntries, patternWith2);

            compositeTracker.onContextClosed(requestContext2);
            compositeTracker.onContextClosed(requestContext2.getEnclosingScope());
            orderedWorkerTracker.remove(orderedWorkerTracker.getRemainingItems().findFirst().get().getKey());
            acm.logTopOpenActivities(false);
            checkAndClearLines(loggedEntries, patternWith1);

            removeContexts(compositeTracker, requestContext3);
            orderedWorkerTracker.remove(orderedWorkerTracker.getRemainingItems().findFirst().get().getKey());
            acm.logTopOpenActivities(false);
            checkAndClearLines(loggedEntries, Pattern.compile("\\n", Pattern.MULTILINE));

        }
    }

    private static String indent(int i) {
        return IntStream.range(0, i).mapToObj(ignored -> ActiveContextMonitor.INDENT).collect(Collectors.joining());
    }

    private Pattern makePattern(
        int visibleRequestCount,
        int totalRequestCount,
        int visibleScopeCount,
        int totalScopeCount
    ) {
        var sb = new StringBuilder();
        sb.append("^");
        sb.append("Oldest of " + totalRequestCount + " outstanding requests that are past thresholds.*\\n");
        for (int i = 0; i < visibleRequestCount; ++i) {
            sb.append(indent(1) + "age=P.*S.*\\n");
        }

        sb.append("Oldest of " + totalScopeCount + " GLOBAL scopes that are past thresholds.*\\n");
        sb.append(indent(1) + "age=P.*S, start=.*Z channel: attribs=\\{.*\\}.*\\n");
        if (visibleScopeCount > 1) {
            sb.append(indent(1) + "age=P.*S, start=.*Z trafficStreamLifetime: attribs=\\{.*\\}.*\\n");
            sb.append(indent(2) + "age=P.*S, start=.*Z channel: attribs=\\{.*\\}.*\\n");
        }

        sb.append("Oldest of 1 scopes .* 'channel'.*\\n");
        sb.append(indent(1) + "age=P.*S, start=.*Z channel: attribs=\\{.*\\}.*\\n");

        sb.append("Oldest of " + totalRequestCount + " scopes .* 'trafficStreamLifetime'.*\\n");
        for (int i = 0; i < visibleRequestCount; ++i) {
            sb.append(indent(1) + "age=P.*S, start=.*Z trafficStreamLifetime: attribs=\\{.*\\}.*\\n");
            sb.append(indent(2) + "age=P.*S, start=.*Z channel: attribs=\\{.*\\}.*\\n");
        }
        sb.append("Oldest of " + totalRequestCount + " scopes .* 'httpTransaction'.*\\n");
        for (int i = 0; i < visibleRequestCount; ++i) {
            sb.append(indent(1) + "age=P.*S, start=.*Z httpTransaction: attribs=\\{.*\\}.*\\n");
            sb.append(indent(2) + "age=P.*S, start=.*Z trafficStreamLifetime: attribs=\\{.*\\}.*\\n");
            sb.append(indent(3) + "age=P.*S, start=.*Z channel: attribs=\\{.*\\}.*\\n");
        }

        sb.append("$");

        return Pattern.compile(sb.toString(), Pattern.MULTILINE);
    }

    private void addContexts(CompositeContextTracker compositeTracker, IScopedInstrumentationAttributes ctx) {
        while (ctx != null) {
            compositeTracker.onContextCreated(ctx);
            ctx = ctx.getEnclosingScope();
        }
    }

    private void removeContexts(CompositeContextTracker compositeTracker, IScopedInstrumentationAttributes ctx) {
        while (ctx != null) {
            compositeTracker.onContextClosed(ctx);
            ctx = ctx.getEnclosingScope();
        }
    }

    private void checkAndClearLines(ArrayList<Map.Entry<Level, String>> loggedLines, Pattern pattern) {
        loggedLines.stream()
            .forEach(kvp -> System.out.println(kvp.getValue() + " (" + kvp.getKey().toString().toLowerCase() + ")"));
        var filteredLoggedLines = loggedLines.stream()
            .filter(kvp -> !kvp.getValue().equals("\n"))
            .collect(Collectors.toList());
        System.out.println(
            "----------------------------------------------------------------"
                + "----------------------------------------------------------------"
        );

        var combinedOutput = filteredLoggedLines.stream().map(Map.Entry::getValue).collect(Collectors.joining("\n"))
            + "\n"; // simplifies the construction of the regex
        Assertions.assertTrue(
            pattern.matcher(combinedOutput).matches(),
            "Could not match: " + combinedOutput + "\n\n with \n" + pattern
        );
        loggedLines.clear();
    }
}
