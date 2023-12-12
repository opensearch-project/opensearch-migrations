package org.opensearch.migrations.replay;

import com.google.common.base.Strings;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.slf4j.event.Level;
import org.testcontainers.shaded.org.apache.commons.io.output.NullOutputStream;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class TrafficReplayerRunner {

    @AllArgsConstructor
    static class FabricatedErrorToKillTheReplayer extends Error {
        public final boolean doneWithTest;
    }

    private TrafficReplayerRunner() {}

    static void runReplayerUntilSourceWasExhausted(int numExpectedRequests, URI endpoint,
                                                   Supplier<Consumer<SourceTargetCaptureTuple>> tupleListenerSupplier,
                                                   Supplier<ISimpleTrafficCaptureSource> trafficSourceSupplier)
            throws Throwable {
        AtomicInteger runNumberRef = new AtomicInteger();
        var totalUniqueEverReceived = new AtomicInteger();

        var receivedPerRun = new ArrayList<Integer>();
        var totalUniqueEverReceivedSizeAfterEachRun = new ArrayList<Integer>();
        var completelyHandledItems = new ConcurrentHashMap<String, SourceTargetCaptureTuple>();

        for (; true; runNumberRef.incrementAndGet()) {
            int runNumber = runNumberRef.get();
            var counter = new AtomicInteger();
            var tupleReceiver = tupleListenerSupplier.get();
            try {
                runTrafficReplayer(trafficSourceSupplier, endpoint, (t) -> {
                    if (runNumber != runNumberRef.get()) {
                        // for an old replayer.  I'm not sure why shutdown isn't blocking until all threads are dead,
                        // but that behavior only impacts this test as far as I can tell.
                        return;
                    }
                    Assertions.assertEquals(runNumber, runNumberRef.get());
                    var key = t.uniqueRequestKey;
                    ISourceTrafficChannelKey tsk = key.getTrafficStreamKey();
                    var keyString = tsk.getConnectionId() + "_" + key.getSourceRequestIndex();
                    var prevKeyString = tsk.getConnectionId() + "_" + (key.getSourceRequestIndex()-1);
                    tupleReceiver.accept(t);
                    Assertions.assertFalse(Optional.ofNullable(completelyHandledItems.get(prevKeyString))
                            .map(prevT->t.sourcePair.equals(prevT.sourcePair)).orElse(false));
                    var totalUnique = null != completelyHandledItems.put(keyString, t) ?
                            totalUniqueEverReceived.get() :
                            totalUniqueEverReceived.incrementAndGet();

                    var c = counter.incrementAndGet();
                    log.info("counter="+c+" totalUnique="+totalUnique+" runNum="+runNumber+" key="+key);
                });
                // if this finished running without an exception, we need to stop the loop
                break;
            } catch (TrafficReplayer.TerminationException e) {
                log.atLevel(e.originalCause instanceof FabricatedErrorToKillTheReplayer ? Level.INFO : Level.ERROR)
                        .setCause(e.originalCause)
                        .setMessage(()->"broke out of the replayer, with this shutdown reason")
                        .log();
                log.atLevel(e.immediateCause == null ? Level.INFO : Level.ERROR)
                        .setCause(e.immediateCause)
                        .setMessage(()->"broke out of the replayer, with the shutdown cause=" + e.originalCause +
                                " and this immediate reason")
                        .log();
                FabricatedErrorToKillTheReplayer killSignalError =
                        e.originalCause instanceof FabricatedErrorToKillTheReplayer
                                ? (FabricatedErrorToKillTheReplayer) e.originalCause
                                : (e.immediateCause instanceof FabricatedErrorToKillTheReplayer
                                ? (FabricatedErrorToKillTheReplayer) e.immediateCause
                                : null);
                if (killSignalError == null) {
                    throw e.immediateCause;
                } else if (killSignalError.doneWithTest) {
                    log.info("Kill signal has indicated that the test is complete, so breaking out of the loop");
                    break;
                } else {
                    log.info("Kill signal has indicated that the test loop should restart. Continuing.");
                }
            } finally {
                waitForWorkerThreadsToStop();
                log.info("Upon appending.... counter="+counter.get()+" totalUnique="+totalUniqueEverReceived.get()+
                        " runNumber="+runNumber + "\n" +
                        completelyHandledItems.keySet().stream().sorted().collect(Collectors.joining("\n")));
                log.info(Strings.repeat("\n", 20));
                receivedPerRun.add(counter.get());
                totalUniqueEverReceivedSizeAfterEachRun.add(totalUniqueEverReceived.get());
            }
        }
        log.atInfo().setMessage(()->"completely received request keys=\n{}")
                .addArgument(completelyHandledItems.keySet().stream().sorted().collect(Collectors.joining("\n")))
                .log();
        var skippedPerRun = IntStream.range(0, receivedPerRun.size())
                .map(i->totalUniqueEverReceivedSizeAfterEachRun.get(i)-receivedPerRun.get(i)).toArray();
        log.atInfo().setMessage(()->"Summary: (run #, uniqueSoFar, receivedThisRun, skipped)\n" +
                        IntStream.range(0, receivedPerRun.size()).mapToObj(i->
                                        new StringJoiner(", ")
                                                .add(""+i)
                                                .add(""+totalUniqueEverReceivedSizeAfterEachRun.get(i))
                                                .add(""+receivedPerRun.get(i))
                                                .add(""+skippedPerRun[i]).toString())
                                .collect(Collectors.joining("\n")))
                .log();
        var skippedPerRunDiffs = IntStream.range(0, receivedPerRun.size()-1)
                .map(i->(skippedPerRun[i]<=skippedPerRun[i+1]) ? 1 : 0)
                .toArray();
        var expectedSkipArray = new int[skippedPerRunDiffs.length];
        Arrays.fill(expectedSkipArray, 1);
        // this isn't the best way to make sure that commits are increasing.
        // They are for specific patterns, but not all of them.
        // TODO - export the whole table of results & let callers determine how to check that commits are moving along
        //Assertions.assertArrayEquals(expectedSkipArray, skippedPerRunDiffs);
        Assertions.assertEquals(numExpectedRequests, totalUniqueEverReceived.get());
    }

    private static void runTrafficReplayer(Supplier<ISimpleTrafficCaptureSource> captureSourceSupplier,
                                           URI endpoint,
                                           Consumer<SourceTargetCaptureTuple> tupleReceiver) throws Exception {
        log.info("Starting a new replayer and running it");
        var tr = new TrafficReplayer(endpoint, null,
                new StaticAuthTransformerFactory("TEST"), null,
                true, 10, 10*1024);

        try (var os = new NullOutputStream();
             var trafficSource = captureSourceSupplier.get();
             var blockingTrafficSource = new BlockingTrafficSource(trafficSource, Duration.ofMinutes(2))) {
            tr.setupRunAndWaitForReplayWithShutdownChecks(Duration.ofSeconds(70), blockingTrafficSource,
                    new TimeShifter(10 * 1000), tupleReceiver);
        }
    }

    private static void waitForWorkerThreadsToStop() throws InterruptedException {
        var sleepMs = 2;
        final var MAX_SLEEP_MS = 100;
        while (true) {
            var rootThreadGroup = getRootThreadGroup();
            if (!foundClientPoolThread(rootThreadGroup)) {
                log.info("No client connection pool threads, done polling.");
                return;
            } else {
                log.trace("Found a client connection pool - waiting briefly and retrying.");
                Thread.sleep(sleepMs);
                sleepMs = Math.max(MAX_SLEEP_MS, sleepMs*2);
            }
        }
    }

    private static boolean foundClientPoolThread(ThreadGroup group) {
        Thread[] threads = new Thread[group.activeCount()*2];
        var numThreads = group.enumerate(threads);
        for (int i=0; i<numThreads; ++i) {
            if (threads[i].getName().startsWith(ClientConnectionPool.TARGET_CONNECTION_POOL_NAME)) {
                return true;
            }
        }

        int numGroups = group.activeGroupCount();
        ThreadGroup[] groups = new ThreadGroup[numGroups * 2];
        numGroups = group.enumerate(groups, false);
        for (int i=0; i<numGroups; ++i) {
            if (foundClientPoolThread(groups[i])) {
                return true;
            }
        }
        return false;
    }

    private static ThreadGroup getRootThreadGroup() {
        var rootThreadGroup = Thread.currentThread().getThreadGroup();
        while (true) {
            var tmp = rootThreadGroup.getParent();
            if (tmp != null) { rootThreadGroup = tmp; }
            else { return rootThreadGroup; }
        }
    }

}
