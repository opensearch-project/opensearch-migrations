package org.opensearch.migrations.replay.util;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceOwner;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.OperationType;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.WaitReason;
import org.opensearch.migrations.replay.testing.FakeClock;
import org.opensearch.migrations.replay.testing.TestEventLoop;
import org.opensearch.migrations.replay.tracing.IKafkaConsumerContexts;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.ReplayActivityMetrics;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import io.opentelemetry.sdk.metrics.data.MetricData;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ActiveContextMonitorTest {
    @Test
    void tracesDisabledStillIndexesContextsFindsDescendantsAndEmitsPopulationMetrics() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var root = new RootReplayerContext(telemetry.openTelemetrySdk);
            var generation = new PartitionGenerationId(new TopicPartition("traffic", 2), 7);
            var recordId = new KafkaRecordId(generation, 41);
            var connection = new ConnectionProcessingId(
                generation,
                new CapturedConnectionId("writer", "captured-connection"),
                3
            );
            var requestId = new ReplayRequestId(connection, 11);
            var record = root.createKafkaRecordContext(recordId, 100);
            var stream = record.createTrafficStreamContext(4);
            var request = stream.createRequestContext(requestId, Instant.EPOCH);
            var target = request.createTargetRequestContext();
            var waiting = target.createWaitingForResponseContext();
            var tuple = request.createTupleContext();
            var poll = root.createPollContext();
            var commit = root.createCommitContext();
            var kafkaCommit = commit.createNewKafkaCommitContext();
            var rebalance = root.createRebalanceCallbackContext();
            var clock = new FakeClock(Instant.now().plusSeconds(31));
            var collector = new ContextActivityCollector(
                clock,
                root.getActiveContextTracker(),
                root.getActiveContextTrackerByActivityType()
            );

            var snapshot = collector.collect();

            Assertions.assertEquals(root.getActiveContextTracker().size(), snapshot.totalActiveContexts());
            Assertions.assertEquals(
                1,
                snapshot.activities().get(IReplayContexts.ActivityNames.RECORD_LIFETIME).activeCount()
            );
            Assertions.assertEquals(
                1,
                snapshot.activities().get(IKafkaConsumerContexts.ActivityNames.KAFKA_POLL).activeCount()
            );
            Assertions.assertEquals(
                1,
                snapshot.activities().get(IKafkaConsumerContexts.ActivityNames.COMMIT).activeCount()
            );
            Assertions.assertEquals(
                1,
                snapshot.activities().get(IKafkaConsumerContexts.ActivityNames.KAFKA_COMMIT).activeCount()
            );
            Assertions.assertEquals(
                1,
                snapshot.activities().get(IKafkaConsumerContexts.ActivityNames.REBALANCE_CALLBACK).activeCount()
            );
            var oldestRecord = snapshot.oldestKafkaRecord().orElseThrow();
            Assertions.assertEquals(recordId, oldestRecord.recordId());
            Assertions.assertEquals(5, oldestRecord.descendantCount());
            Assertions.assertTrue(oldestRecord.descendants().stream().anyMatch(detail ->
                detail.activity().equals(IReplayContexts.ActivityNames.WAITING_FOR_RESPONSE)
                    && detail.identity().replayRequestId().orElseThrow().equals(requestId)
                    && detail.ancestorChain().contains(IReplayContexts.ActivityNames.RECORD_LIFETIME)
            ));

            var reports = new ArrayList<ActiveContextMonitor.Report>();
            var monitor = new ActiveContextMonitor(
                collector,
                new OwnerOperationCollector(clock, List::of),
                new KafkaDiagnosticCollector(() -> KafkaSourceOwner.DiagnosticSnapshot.EMPTY),
                root.getReplayActivityMetrics(),
                reports::add
            );
            monitor.run();
            Assertions.assertEquals(snapshot.totalActiveContexts(), reports.get(0).contexts().totalActiveContexts());
            assertLongGauge(
                telemetry.getFinishedMetrics(),
                ReplayActivityMetrics.MetricNames.ACTIVE_CONTEXTS,
                snapshot.totalActiveContexts()
            );
            assertActivityLongGaugeAtLeast(
                telemetry.getFinishedMetrics(),
                ReplayActivityMetrics.MetricNames.OLDEST_CONTEXT_AGE_BY_ACTIVITY,
                IReplayContexts.ActivityNames.TUPLE_COMPARISON,
                ContextActivityCollector.DEFAULT_AGE_THRESHOLD.toMillis()
            );
            assertOperationTypeGaugeHasExplicitZeros(
                telemetry.getFinishedMetrics(),
                ReplayActivityMetrics.MetricNames.ACTIVE_OWNER_OPERATIONS_BY_TYPE
            );
            assertOperationTypeGaugeHasExplicitZeros(
                telemetry.getFinishedMetrics(),
                ReplayActivityMetrics.MetricNames.LONG_RUNNING_OWNER_OPERATIONS_BY_TYPE
            );

            waiting.close();
            target.close();
            tuple.close();
            request.close();
            stream.close();
            record.complete(IReplayContexts.RecordDisposition.GENERATION_ENDED_UNCOMMITTED);
            kafkaCommit.close();
            commit.close();
            poll.close();
            rebalance.close();

            Assertions.assertEquals(0, root.getActiveContextTracker().size());
            snapshot.activities().keySet().forEach(activity -> Assertions.assertEquals(
                0,
                collector.collect().activities().get(activity).activeCount(),
                activity
            ));
        }
    }

    @Test
    void ownerOperationCollectorKeepsRegistryDetailSupplemental() {
        var clock = new FakeClock();
        var eventLoop = new TestEventLoop(clock);
        var generation = new PartitionGenerationId(new TopicPartition("traffic", 2), 7);
        var connection = new ConnectionProcessingId(
            generation,
            new CapturedConnectionId("writer", "captured-connection"),
            3
        );
        var request = new ReplayRequestId(connection, 11);
        var record = new KafkaRecordId(generation, 41);
        var fatalFailures = new ArrayList<Error>();
        var registry = new OutstandingOperationRegistry(
            "request " + request,
            record,
            eventLoop,
            clock,
            fatalFailures::add
        );
        eventLoop.execute(() -> registry.register(
            generation,
            connection,
            request,
            OperationType.RETRY_TIMER,
            clock.instant().plusSeconds(60),
            WaitReason.WAITING_FOR_RETRY_TIME
        ));
        eventLoop.runUntilIdle();
        var collector = new OwnerOperationCollector(clock, Duration.ofSeconds(30), registry::snapshots);

        clock.advance(Duration.ofSeconds(29));
        Assertions.assertEquals(0, collector.collect().longRunningCount());
        clock.advance(Duration.ofSeconds(1));
        var snapshot = collector.collect();

            Assertions.assertEquals(1, snapshot.activeCount());
            Assertions.assertEquals(1, snapshot.longRunningCount());
            Assertions.assertEquals(
                1,
                snapshot.activeByOperationType().get(OperationType.RETRY_TIMER)
            );
            Assertions.assertEquals(
                1,
                snapshot.longRunningByOperationType().get(OperationType.RETRY_TIMER)
            );
            Assertions.assertEquals(
                OperationType.values().length,
                snapshot.activeByOperationType().size()
            );
            var detail = snapshot.longRunningOperations().get(0);
        Assertions.assertEquals(Duration.ofSeconds(30), detail.elapsed());
        Assertions.assertEquals(record, detail.operation().kafkaRecordId());
        Assertions.assertEquals(OperationType.RETRY_TIMER, detail.operation().operationType());
        Assertions.assertEquals(WaitReason.WAITING_FOR_RETRY_TIME, detail.operation().waitReason());
        Assertions.assertEquals(1, registry.activeCount(), "reporting cannot complete owner work");
        Assertions.assertTrue(fatalFailures.isEmpty());
    }

    @Test
    void oldContextAndOwnerDetailsAreBounded() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, false)) {
            var root = new RootReplayerContext(telemetry.openTelemetrySdk);
            var generation = new PartitionGenerationId(new TopicPartition("traffic", 0), 0);
            var records = new ArrayList<IReplayContexts.IKafkaRecordContext>();
            for (int i = 0; i < ContextActivityCollector.MAX_OLD_CONTEXT_DETAILS + 5; i++) {
                records.add(root.createKafkaRecordContext(new KafkaRecordId(generation, i), 1));
            }
            var collector = new ContextActivityCollector(
                new FakeClock(Instant.now().plusSeconds(31)),
                root.getActiveContextTracker(),
                root.getActiveContextTrackerByActivityType()
            );

            var snapshot = collector.collect();

            Assertions.assertEquals(
                ContextActivityCollector.MAX_OLD_CONTEXT_DETAILS + 5,
                snapshot.oldContextCount()
            );
            Assertions.assertEquals(
                ContextActivityCollector.MAX_OLD_CONTEXT_DETAILS,
                snapshot.oldContexts().size()
            );
            records.forEach(record ->
                record.complete(IReplayContexts.RecordDisposition.GENERATION_ENDED_UNCOMMITTED)
            );
        }
    }

    @Test
    void dedicatedActivityLoggerRoutesToBothRequiredFiles() throws Exception {
        var mainRuntimeClasspath = System.getProperty("trafficReplayerMainRuntimeClasspath");
        Assertions.assertNotNull(mainRuntimeClasspath);
        var mainLogConfiguration = java.util.Arrays.stream(
            mainRuntimeClasspath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))
        )
            .map(java.nio.file.Path::of)
            .filter(path -> path.toString().contains("trafficReplayer/build/resources/main"))
            .map(path -> path.resolve("log4j2.properties"))
            .filter(java.nio.file.Files::isRegularFile)
            .findFirst()
            .orElseThrow();
        var properties = new Properties();
        try (var stream = java.nio.file.Files.newInputStream(mainLogConfiguration)) {
            properties.load(stream);
        }

        Assertions.assertEquals(
            ActiveContextMonitor.ACTIVE_WORK_LOGGER_NAME,
            properties.getProperty("logger.AllActiveWorkMonitor.name")
        );
        Assertions.assertEquals(
            "AllActiveWorkMonitorFile",
            properties.getProperty(
                "logger.AllActiveWorkMonitor.appenderRef.ALL_ACTIVE_WORK_MONITOR.ref"
            )
        );
        Assertions.assertEquals(
            "ReplayerLogFile",
            properties.getProperty("logger.AllActiveWorkMonitor.appenderRef.ReplayerLogFile.ref")
        );
    }

    @Test
    void productionSourceCannotReintroduceCountHookOrDomainMetricsNoopWiring() throws Exception {
        var workingDirectory = java.nio.file.Path.of(System.getProperty("user.dir"));
        var sourceRoot = workingDirectory.resolve("TrafficCapture/trafficReplayer/src/main/java");
        if (!java.nio.file.Files.isDirectory(sourceRoot)) {
            sourceRoot = workingDirectory.resolve("src/main/java");
        }
        Assertions.assertTrue(java.nio.file.Files.isDirectory(sourceRoot), sourceRoot.toString());
        var resolvedSourceRoot = sourceRoot;
        try (var files = java.nio.file.Files.walk(resolvedSourceRoot)) {
            var violations = files
                .filter(path -> path.toString().endsWith(".java"))
                .flatMap(path -> {
                    try {
                        var text = java.nio.file.Files.readString(path);
                        return java.util.stream.Stream.of("CountHook", "Metrics.NOOP")
                            .filter(text::contains)
                            .map(token -> resolvedSourceRoot.relativize(path) + ": " + token);
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                })
                .toList();
            Assertions.assertEquals(List.of(), violations);
        }
    }

    @Test
    @org.junit.jupiter.api.parallel.ResourceLock("AllActiveWorkMonitor")
    void systemReporterUsesBoundedEventNamesAndFields() {
        var messages = new ArrayList<String>();
        var appender = new org.apache.logging.log4j.core.appender.AbstractAppender(
            "active-work-monitor-test",
            null,
            org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(),
            false,
            org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY
        ) {
            @Override
            public void append(org.apache.logging.log4j.core.LogEvent event) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        };
        var activityLogger = (org.apache.logging.log4j.core.Logger)
            org.apache.logging.log4j.LogManager.getLogger(ActiveContextMonitor.ACTIVE_WORK_LOGGER_NAME);
        appender.start();
        activityLogger.addAppender(appender);
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var root = new RootReplayerContext(telemetry.openTelemetrySdk);
            var record = root.createKafkaRecordContext(
                new KafkaRecordId(
                    new PartitionGenerationId(new TopicPartition("traffic", 2), 7),
                    41
                ),
                1
            );
            var clock = new FakeClock(Instant.now().plusSeconds(31));
            try (var monitor = ActiveContextMonitor.system(
                clock,
                root.getActiveContextTracker(),
                root.getActiveContextTrackerByActivityType(),
                List::of,
                () -> KafkaSourceOwner.DiagnosticSnapshot.EMPTY,
                root.getReplayActivityMetrics()
            )) {
                monitor.run();
                record.complete(IReplayContexts.RecordDisposition.GENERATION_ENDED_UNCOMMITTED);
                monitor.closeAfterOrderlyDrain();
            }

            Assertions.assertTrue(messages.stream().anyMatch(message ->
                message.startsWith("event=replay_health ")
                    && message.contains("active_contexts=1")
                    && message.contains("owner_operations=0")
            ));
            Assertions.assertTrue(messages.stream().anyMatch(message ->
                message.startsWith("event=replay_oldest_record ")
                    && message.contains("topic=traffic")
                    && message.contains("partition=2")
                    && message.contains("generation=7")
                    && message.contains("offset=41")
            ));
            Assertions.assertTrue(messages.stream().anyMatch(message ->
                message.startsWith("event=replay_stall_detected kind=kafka_record")
            ));
            Assertions.assertTrue(messages.stream().anyMatch(message ->
                message.startsWith("event=replay_health ")
                    && message.contains("active_contexts=0")
                    && message.contains("final_sample=true")
            ));
        } finally {
            activityLogger.removeAppender(appender);
            appender.stop();
        }
    }

    @Test
    void tracesDisabledOrderlyCloseExportsRealZeroFinalSample() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var root = new RootReplayerContext(telemetry.openTelemetrySdk);
            var record = root.createKafkaRecordContext(
                new KafkaRecordId(
                    new PartitionGenerationId(new TopicPartition("traffic", 1), 3),
                    9
                ),
                1
            );
            var reports = new ArrayList<ActiveContextMonitor.Report>();
            var monitor = new ActiveContextMonitor(
                new ContextActivityCollector(
                    new FakeClock(Instant.now()),
                    root.getActiveContextTracker(),
                    root.getActiveContextTrackerByActivityType()
                ),
                new OwnerOperationCollector(new FakeClock(), List::of),
                new KafkaDiagnosticCollector(() -> KafkaSourceOwner.DiagnosticSnapshot.EMPTY),
                root.getReplayActivityMetrics(),
                reports::add
            );

            monitor.run();
            assertLongGauge(
                telemetry.getFinishedMetrics(),
                ReplayActivityMetrics.MetricNames.ACTIVE_CONTEXTS,
                1
            );

            record.complete(IReplayContexts.RecordDisposition.GENERATION_ENDED_UNCOMMITTED);
            monitor.closeAfterOrderlyDrain();

            var finalReport = reports.get(reports.size() - 1);
            Assertions.assertTrue(finalReport.finalSample());
            Assertions.assertEquals(0, finalReport.contexts().totalActiveContexts());
            Assertions.assertEquals(0, finalReport.ownerOperations().activeCount());
            assertLongGauge(
                telemetry.getFinishedMetrics(),
                ReplayActivityMetrics.MetricNames.ACTIVE_CONTEXTS,
                0
            );
            assertLongGauge(
                telemetry.getFinishedMetrics(),
                ReplayActivityMetrics.MetricNames.ACTIVE_OWNER_OPERATIONS,
                0
            );
        }
    }

    @Test
    void incompleteOrderlyCloseExportsTheRealNonzeroFinalStateAndFails() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var root = new RootReplayerContext(telemetry.openTelemetrySdk);
            var record = root.createKafkaRecordContext(
                new KafkaRecordId(
                    new PartitionGenerationId(new TopicPartition("traffic", 1), 3),
                    9
                ),
                1
            );
            var reports = new ArrayList<ActiveContextMonitor.Report>();
            var monitor = new ActiveContextMonitor(
                new ContextActivityCollector(
                    new FakeClock(Instant.now()),
                    root.getActiveContextTracker(),
                    root.getActiveContextTrackerByActivityType()
                ),
                new OwnerOperationCollector(new FakeClock(), List::of),
                new KafkaDiagnosticCollector(() -> KafkaSourceOwner.DiagnosticSnapshot.EMPTY),
                root.getReplayActivityMetrics(),
                reports::add
            );

            Assertions.assertThrows(
                IllegalStateException.class,
                monitor::closeAfterOrderlyDrain
            );

            var finalReport = reports.get(reports.size() - 1);
            Assertions.assertTrue(finalReport.finalSample());
            Assertions.assertEquals(1, finalReport.contexts().totalActiveContexts());
            assertLongGauge(
                telemetry.getFinishedMetrics(),
                ReplayActivityMetrics.MetricNames.ACTIVE_CONTEXTS,
                1
            );
            record.complete(IReplayContexts.RecordDisposition.GENERATION_ENDED_UNCOMMITTED);
        }
    }

    private static void assertLongGauge(
        Iterable<MetricData> metrics,
        String name,
        long expected
    ) {
        var metric = java.util.stream.StreamSupport.stream(metrics.spliterator(), false)
            .filter(data -> data.getName().equals(name))
            .findFirst()
            .orElseThrow();
        Assertions.assertEquals(
            expected,
            metric.getLongGaugeData().getPoints().iterator().next().getValue()
        );
    }

    private static void assertOperationTypeGaugeHasExplicitZeros(
        Iterable<MetricData> metrics,
        String name
    ) {
        var metric = java.util.stream.StreamSupport.stream(metrics.spliterator(), false)
            .filter(data -> data.getName().equals(name))
            .findFirst()
            .orElseThrow();
        var points = metric.getLongGaugeData().getPoints();
        Assertions.assertEquals(OperationType.values().length, points.size());
        for (var operationType : OperationType.values()) {
            var point = points.stream()
                .filter(candidate -> operationType.name().equals(
                    candidate.getAttributes().get(ReplayActivityMetrics.OPERATION_TYPE_ATTRIBUTE)
                ))
                .findFirst()
                .orElseThrow();
            Assertions.assertEquals(0, point.getValue(), operationType.name());
        }
    }

    private static void assertActivityLongGaugeAtLeast(
        Iterable<MetricData> metrics,
        String name,
        String activity,
        long minimum
    ) {
        var metric = java.util.stream.StreamSupport.stream(metrics.spliterator(), false)
            .filter(data -> data.getName().equals(name))
            .findFirst()
            .orElseThrow();
        var point = metric.getLongGaugeData().getPoints().stream()
            .filter(candidate -> activity.equals(
                candidate.getAttributes().get(ReplayActivityMetrics.ACTIVITY_ATTRIBUTE)
            ))
            .findFirst()
            .orElseThrow();
        Assertions.assertTrue(
            point.getValue() >= minimum,
            () -> activity + " oldest age was " + point.getValue() + "ms"
        );
    }
}
