package org.opensearch.migrations.replay.util;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.tracing.TestContext;
import org.slf4j.event.Level;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
class ActiveContextMonitorTest {

    @Test
    void test() throws Exception {
        var loggedEntries = new ArrayList<Map.Entry<Level,String>>();
        var globalContextTracker = new ActiveContextTracker();
        var perActivityContextTracker = new ActiveContextTrackerByActivityType();
        var orderedWorkerTracker = new OrderedWorkerTracker();
        var compositeTracker = new CompositeContextTracker(globalContextTracker, perActivityContextTracker);
        var acm = new ActiveContextMonitor(
                globalContextTracker, perActivityContextTracker, orderedWorkerTracker, 2,
                (level, msgSupplier) -> loggedEntries.add(Map.entry(level, msgSupplier.get())),
                Map.of(
                        Level.ERROR, Duration.ofMillis(10000),
                        Level.WARN, Duration.ofMillis(80),
                        Level.INFO, Duration.ofMillis(60),
                        Level.DEBUG, Duration.ofMillis(40),
                        Level.TRACE, Duration.ofMillis(20)));

        var patternWith1 = makePattern(2, 1, 3);
        var patternWith2 = makePattern(2, 2, 5);
        var patternWith3 = makePattern(2, 3, 7);

        try (var testContext = TestContext.noOtelTracking()) {
            var requestContext1 = testContext.getTestConnectionRequestContext(0);
            var requestContext2 = testContext.getTestConnectionRequestContext(0);
            var requestContext3 = testContext.getTestConnectionRequestContext(0);

            addContexts(compositeTracker, requestContext1);
            Thread.sleep(20);
            acm.run();
            checkAndClearLines(9, loggedEntries, patternWith1);

            addContexts(compositeTracker, requestContext2);
            Thread.sleep(20);
            acm.run();
            checkAndClearLines(11, loggedEntries, patternWith2);

            addContexts(compositeTracker, requestContext3);
            Thread.sleep(20);
            acm.run();
            checkAndClearLines(11, loggedEntries, patternWith3);

            Thread.sleep(50);
            acm.run();
            checkAndClearLines( 11, loggedEntries, patternWith3);

            compositeTracker.onContextClosed(requestContext1);
            compositeTracker.onContextClosed(requestContext1.getEnclosingScope());
            acm.run();
            checkAndClearLines(11, loggedEntries, patternWith2);

            compositeTracker.onContextClosed(requestContext2);
            compositeTracker.onContextClosed(requestContext2.getEnclosingScope());
            removeContexts(compositeTracker, requestContext3);
            acm.run();
            checkAndClearLines(0, loggedEntries, Pattern.compile("\\n", Pattern.MULTILINE));
        }
    }

    private static String indent(int i) {
        return IntStream.range(0, i).mapToObj(ignored->ActiveContextMonitor.INDENT).collect(Collectors.joining());
    }

    private Pattern makePattern(int maxItems, int requestScopeCount, int totalScopeCount) {
        var sb = new StringBuilder();
        sb.append("^");
        sb.append("Oldest of " + totalScopeCount + " GLOBAL scopes.*\\n");
        sb.append(indent(1) + "age=P.*S, start=.*Z channel: attribs=\\{.*\\}.*\\n");
        if (totalScopeCount > 1) {
            sb.append(indent(1) + "age=P.*S, start=.*Z trafficStreamLifetime: attribs=\\{.*\\}.*\\n");
            sb.append(indent(2) + "age=P.*S, start=.*Z channel: attribs=\\{.*\\}.*\n");
        }

        sb.append("Oldest of 1 scopes for 'channel'.*\\n");
        sb.append(indent(1) + "age=P.*S, start=.*Z channel: attribs=\\{.*\\}.*\\n");

        sb.append("Oldest of " + requestScopeCount + " scopes for 'trafficStreamLifetime'.*\\n");
        for (int i=0; i<Math.min(maxItems, requestScopeCount); ++i) {
            sb.append(indent(1) + "age=P.*S, start=.*Z trafficStreamLifetime: attribs=\\{.*\\}.*\\n");
            sb.append(indent(2) + "age=P.*S, start=.*Z channel: attribs=\\{.*\\}.*\\n");
        }
        sb.append("Oldest of " + requestScopeCount + " scopes for 'httpTransaction'.*\\n");
        for (int i=0; i<Math.min(maxItems, requestScopeCount); ++i) {
            sb.append(indent(1) + "age=P.*S, start=.*Z httpTransaction: attribs=\\{.*\\}.*\\n");
            sb.append(indent(2) + "age=P.*S, start=.*Z trafficStreamLifetime: attribs=\\{.*\\}.*\\n");
            sb.append(indent(3) + "age=P.*S, start=.*Z channel: attribs=\\{.*\\}.*\\n");
        }

        sb.append("$");

        return Pattern.compile(sb.toString(), Pattern.MULTILINE);
    }

    private void addContexts(CompositeContextTracker compositeTracker,
                             IScopedInstrumentationAttributes ctx) {
        while (ctx != null) {
            compositeTracker.onContextCreated(ctx);
            ctx = ctx.getEnclosingScope();
        }
    }

    private void removeContexts(CompositeContextTracker compositeTracker,
                             IScopedInstrumentationAttributes ctx) {
        while (ctx != null) {
            compositeTracker.onContextClosed(ctx);
            ctx = ctx.getEnclosingScope();
        }
    }

    private void checkAndClearLines(int i, ArrayList<Map.Entry<Level, String>> loggedLines, Pattern pattern) {
        loggedLines.forEach(kvp->System.out.println(kvp.getValue()+" (" + kvp.getKey() + ")"));
        System.out.println("----------------------------------------------------------------" +
                "----------------------------------------------------------------");
        Assertions.assertEquals(i, loggedLines.size());
        var combinedOutput = loggedLines.stream().map(Map.Entry::getValue).collect(Collectors.joining("\n")) +
                "\n"; // simplifies the construction of the regex
        Assertions.assertTrue(pattern.matcher(combinedOutput).matches(),
                "Could not match: " + combinedOutput + "\n\n with \n" + pattern);
        loggedLines.clear();
    }
}