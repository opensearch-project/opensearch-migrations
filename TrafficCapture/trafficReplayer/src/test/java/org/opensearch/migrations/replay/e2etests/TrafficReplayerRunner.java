package org.opensearch.migrations.replay.e2etests;

import javax.net.ssl.SSLException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.opensearch.migrations.replay.RootReplayerConstructorExtensions;
import org.opensearch.migrations.replay.SourceTargetCaptureTuple;
import org.opensearch.migrations.replay.TimeShifter;
import org.opensearch.migrations.replay.TrafficReplayer;
import org.opensearch.migrations.replay.TrafficReplayerTopLevel;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.opensearch.migrations.transform.TransformationLoader;

import com.google.common.base.Strings;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.slf4j.event.Level;

@Slf4j
public class TrafficReplayerRunner {

    @AllArgsConstructor
    public static class FabricatedErrorToKillTheReplayer extends Error {
        public final boolean doneWithTest;
    }

    private TrafficReplayerRunner() {}

    public static void runReplayer(
        int numExpectedRequests,
        URI endpoint,
        Supplier<Consumer<SourceTargetCaptureTuple>> tupleListenerSupplier,
        Supplier<TestContext> rootContextSupplier,
        Function<TestContext, ISimpleTrafficCaptureSource> trafficSourceFactory
    ) throws Throwable {
        runReplayer(
            numExpectedRequests,
            endpoint,
            tupleListenerSupplier,
            rootContextSupplier,
            trafficSourceFactory,
            new TimeShifter(10 * 1000)
        );
    }

    public static void runReplayer(
        int numExpectedRequests,
        URI endpoint,
        Supplier<Consumer<SourceTargetCaptureTuple>> tupleListenerSupplier,
        Supplier<TestContext> rootContextSupplier,
        Function<TestContext, ISimpleTrafficCaptureSource> trafficSourceFactory,
        TimeShifter timeShifter
    ) throws Throwable {
        runReplayer(numExpectedRequests, (rootContext, targetConnectionPoolPrefix) -> {
            try {
                return new RootReplayerConstructorExtensions(
                    rootContext,
                    endpoint,
                    new StaticAuthTransformerFactory("TEST"),
                    new TransformationLoader().getTransformerFactoryLoaderWithNewHostName(endpoint.getHost()),
                    RootReplayerConstructorExtensions.makeNettyPacketConsumerConnectionPool(endpoint, targetConnectionPoolPrefix)
                );
            } catch (SSLException e) {
                throw new RuntimeException(e);
            }
        }, tupleListenerSupplier, rootContextSupplier, trafficSourceFactory, timeShifter);
    }

    public static void runReplayer(
        int numExpectedRequests,
        BiFunction<IRootReplayerContext, String, TrafficReplayerTopLevel> trafficReplayerFactory,
        Supplier<Consumer<SourceTargetCaptureTuple>> tupleListenerSupplier,
        Supplier<TestContext> rootContextSupplier,
        Function<TestContext, ISimpleTrafficCaptureSource> trafficSourceFactory,
        TimeShifter timeShifter
    ) throws Throwable {
        boolean skipFinally = false;
        AtomicInteger runNumberRef = new AtomicInteger();
        var totalUniqueEverReceived = new AtomicInteger();

        var receivedPerRun = new ArrayList<Integer>();
        var totalUniqueEverReceivedSizeAfterEachRun = new ArrayList<Integer>();
        var completelyHandledItems = new ConcurrentHashMap<String, SourceTargetCaptureTuple>();

        for (; true; runNumberRef.incrementAndGet()) {
            int runNumber = runNumberRef.get();
            var counter = new AtomicInteger();
            var tupleReceiver = tupleListenerSupplier.get();
            String targetConnectionPoolPrefix = TrafficReplayerTopLevel.TARGET_CONNECTION_POOL_NAME
                + " run: "
                + runNumber;
            try (
                var rootContext = rootContextSupplier.get();
                var trafficReplayer = trafficReplayerFactory.apply(rootContext, targetConnectionPoolPrefix)
            ) {
                runTrafficReplayer(trafficReplayer, () -> trafficSourceFactory.apply(rootContext), (t) -> {
                    if (runNumber != runNumberRef.get()) {
                        // for an old replayer. I'm not sure why shutdown isn't blocking until all threads are dead,
                        // but that behavior only impacts this test as far as I can tell.
                        return;
                    }
                    Assertions.assertEquals(runNumber, runNumberRef.get());
                    var key = t.getRequestKey();
                    ISourceTrafficChannelKey tsk = key.getTrafficStreamKey();
                    var keyString = tsk.getConnectionId() + "_" + key.getSourceRequestIndex();
                    var prevKeyString = tsk.getConnectionId() + "_" + (key.getSourceRequestIndex() - 1);
                    tupleReceiver.accept(t);
                    Assertions.assertFalse(
                        Optional.ofNullable(completelyHandledItems.get(prevKeyString))
                            .map(prevT -> t.sourcePair.equals(prevT.sourcePair))
                            .orElse(false)
                    );
                    var totalUnique = null != completelyHandledItems.put(keyString, t)
                        ? totalUniqueEverReceived.get()
                        : totalUniqueEverReceived.incrementAndGet();

                    var c = counter.incrementAndGet();
                    log.info("counter=" + c + " totalUnique=" + totalUnique + " runNum=" + runNumber + " key=" + key);
                }, timeShifter);
                // if this finished running without an exception, we need to stop the loop
                break;
            } catch (TrafficReplayer.TerminationException e) {
                log.atLevel(e.originalCause instanceof FabricatedErrorToKillTheReplayer ? Level.INFO : Level.ERROR)
                    .setCause(e.originalCause)
                    .setMessage("broke out of the replayer, with this shutdown reason")
                    .log();
                log.atLevel(e.immediateCause == null ? Level.INFO : Level.ERROR)
                    .setCause(e.immediateCause)
                    .setMessage("broke out of the replayer, with the shutdown cause={} and this immediate reason")
                    .addArgument(e.originalCause)
                    .log();
                FabricatedErrorToKillTheReplayer killSignalError =
                    e.originalCause instanceof FabricatedErrorToKillTheReplayer
                        ? (FabricatedErrorToKillTheReplayer) e.originalCause
                        : (e.immediateCause instanceof FabricatedErrorToKillTheReplayer
                            ? (FabricatedErrorToKillTheReplayer) e.immediateCause
                            : null);
                if (killSignalError == null) {
                    skipFinally = true;
                    throw e.immediateCause;
                } else if (killSignalError.doneWithTest) {
                    log.info("Kill signal has indicated that the test is complete, so breaking out of the loop");
                    break;
                } else {
                    log.info("Kill signal has indicated that the test loop should restart. Continuing.");
                }
            } finally {
                if (!skipFinally) {
                    waitForWorkerThreadsToStop(targetConnectionPoolPrefix);
                    log.info(
                        "Upon appending.... counter="
                            + counter.get()
                            + " totalUnique="
                            + totalUniqueEverReceived.get()
                            + " runNumber="
                            + runNumber
                            + "\n"
                            + completelyHandledItems.keySet().stream().sorted().collect(Collectors.joining("\n"))
                    );
                    log.info(Strings.repeat("\n", 20));
                    receivedPerRun.add(counter.get());
                    totalUniqueEverReceivedSizeAfterEachRun.add(totalUniqueEverReceived.get());
                }
            }
        }
        log.atInfo()
            .setMessage("completely received request keys=\n{}")
            .addArgument(() -> completelyHandledItems.keySet().stream().sorted().collect(Collectors.joining("\n")))
            .log();
        var skippedPerRun = IntStream.range(0, receivedPerRun.size())
            .map(i -> totalUniqueEverReceivedSizeAfterEachRun.get(i) - receivedPerRun.get(i))
            .toArray();
        log.atInfo().setMessage("Summary: (run #, uniqueSoFar, receivedThisRun, skipped)\n{}")
            .addArgument(() -> IntStream.range(0, receivedPerRun.size())
                .mapToObj(
                    i -> new StringJoiner(", ").add("" + i)
                        .add("" + totalUniqueEverReceivedSizeAfterEachRun.get(i))
                        .add("" + receivedPerRun.get(i))
                        .add("" + skippedPerRun[i])
                        .toString()
                )
                .collect(Collectors.joining("\n")))
            .log();
        var skippedPerRunDiffs = IntStream.range(0, receivedPerRun.size() - 1)
            .map(i -> (skippedPerRun[i] <= skippedPerRun[i + 1]) ? 1 : 0)
            .toArray();
        var expectedSkipArray = new int[skippedPerRunDiffs.length];
        Arrays.fill(expectedSkipArray, 1);
        // this isn't the best way to make sure that commits are increasing.
        // They are for specific patterns, but not all of them.
        // TODO - export the whole table of results & let callers determine how to check that commits are moving along
        // Assertions.assertArrayEquals(expectedSkipArray, skippedPerRunDiffs);
        Assertions.assertEquals(numExpectedRequests, totalUniqueEverReceived.get());
    }

    private static void runTrafficReplayer(
        TrafficReplayerTopLevel trafficReplayer,
        Supplier<ISimpleTrafficCaptureSource> captureSourceSupplier,
        Consumer<SourceTargetCaptureTuple> tupleReceiver,
        TimeShifter timeShifter
    ) throws Exception {
        log.info("Starting a new replayer and running it");
        try (var trafficSource = new BlockingTrafficSource(captureSourceSupplier.get(), Duration.ofMinutes(2))) {
            trafficReplayer.setupRunAndWaitForReplayWithShutdownChecks(
                Duration.ofSeconds(70),
                Duration.ofSeconds(30),
                trafficSource,
                timeShifter,
                tupleReceiver,
                Duration.ofSeconds(5)
            );
        }
    }

    private static void waitForWorkerThreadsToStop(String targetConnectionPoolName) throws InterruptedException {
        var sleepMs = 2;
        final var MAX_SLEEP_MS = 100;
        while (true) {
            var rootThreadGroup = getRootThreadGroup();
            if (!foundClientPoolThread(targetConnectionPoolName, rootThreadGroup)) {
                log.info("No client connection pool threads, done polling.");
                return;
            } else {
                log.trace("Found a client connection pool - waiting briefly and retrying.");
                Thread.sleep(sleepMs);
                sleepMs = Math.max(MAX_SLEEP_MS, sleepMs * 2);
            }
        }
    }

    private static boolean foundClientPoolThread(String targetConnectionPoolName, ThreadGroup group) {
        Thread[] threads = new Thread[group.activeCount() * 2];
        var numThreads = group.enumerate(threads);
        for (int i = 0; i < numThreads; ++i) {
            if (threads[i].getName().startsWith(targetConnectionPoolName)) {
                return true;
            }
        }

        int numGroups = group.activeGroupCount();
        ThreadGroup[] groups = new ThreadGroup[numGroups * 2];
        numGroups = group.enumerate(groups, false);
        for (int i = 0; i < numGroups; ++i) {
            if (foundClientPoolThread(targetConnectionPoolName, groups[i])) {
                return true;
            }
        }
        return false;
    }

    private static ThreadGroup getRootThreadGroup() {
        var rootThreadGroup = Thread.currentThread().getThreadGroup();
        while (true) {
            var tmp = rootThreadGroup.getParent();
            if (tmp != null) {
                rootThreadGroup = tmp;
            } else {
                return rootThreadGroup;
            }
        }
    }

}
