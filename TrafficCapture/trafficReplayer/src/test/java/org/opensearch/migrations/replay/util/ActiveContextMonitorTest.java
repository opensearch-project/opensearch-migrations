package org.opensearch.migrations.replay.util;


import java.time.Duration;
import java.util.ArrayList;
import java.util.Properties;

import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.OperationType;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.WaitReason;
import org.opensearch.migrations.replay.testing.FakeClock;
import org.opensearch.migrations.replay.testing.TestEventLoop;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ActiveContextMonitorTest {
    @Test
    void reportsOnlyLongRunningRegistrationsWithTheirCompleteIdentity() {
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
            fatalFailures::add,
            OutstandingOperationRegistry.CountHook.NOOP
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
        var reports = new ArrayList<ActiveContextMonitor.Report>();
        var statuses = new ArrayList<ActiveContextMonitor.Status>();
        var monitor = new ActiveContextMonitor(
            clock,
            Duration.ofSeconds(30),
            registry::snapshots,
            reports::add,
            statuses::add
        );

        clock.advance(Duration.ofSeconds(29));
        monitor.run();
        Assertions.assertTrue(reports.isEmpty());
        Assertions.assertEquals(
            new ActiveContextMonitor.Status(clock.instant(), 1, 0),
            statuses.get(0)
        );

        clock.advance(Duration.ofSeconds(1));
        monitor.run();

        Assertions.assertEquals(
            new ActiveContextMonitor.Status(clock.instant(), 1, 1),
            statuses.get(1)
        );
        var report = Assertions.assertDoesNotThrow(() -> reports.get(0));
        Assertions.assertEquals(Duration.ofSeconds(30), report.elapsed());
        Assertions.assertEquals("request " + request, report.snapshot().ownerIdentity());
        Assertions.assertEquals(record, report.snapshot().kafkaRecordId());
        Assertions.assertEquals(generation, report.snapshot().partitionGenerationId());
        Assertions.assertEquals(connection, report.snapshot().connectionProcessingId());
        Assertions.assertEquals(request, report.snapshot().replayRequestId());
        Assertions.assertEquals(OperationType.RETRY_TIMER, report.snapshot().operationType());
        Assertions.assertEquals(WaitReason.WAITING_FOR_RETRY_TIME, report.snapshot().waitReason());
        Assertions.assertEquals(1, registry.activeCount(), "reporting cannot complete owner work");
        Assertions.assertTrue(fatalFailures.isEmpty());
    }

    @Test
    void dedicatedActivityLoggerContractStillTargetsTheLongRunningActivityFile()
        throws Exception {
        var mainRuntimeClasspath = Assertions.assertDoesNotThrow(
            () -> System.getProperty("trafficReplayerMainRuntimeClasspath")
        );
        Assertions.assertNotNull(mainRuntimeClasspath);
        var mainLogConfiguration = java.util.Arrays.stream(
            mainRuntimeClasspath.split(
                java.util.regex.Pattern.quote(java.io.File.pathSeparator)
            )
        )
            .map(java.nio.file.Path::of)
            .filter(path -> path.toString().contains(
                "trafficReplayer/build/resources/main"
            ))
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
            "${tempDir}/longRunningActivity/longRunningActivity.log",
            properties.getProperty(
                "appender.ALL_ACTIVE_WORK_MONITOR_LOGFILE.fileName"
            )
        );
    }

    @Test
    @org.junit.jupiter.api.parallel.ResourceLock("AllActiveWorkMonitor")
    void systemReporterEmitsStatusAndLongRunningIdentityThroughDedicatedLogger() {
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
            org.apache.logging.log4j.LogManager.getLogger(
                ActiveContextMonitor.ACTIVE_WORK_LOGGER_NAME
            );
        appender.start();
        activityLogger.addAppender(appender);
        try {
            var clock = new FakeClock();
            var eventLoop = new TestEventLoop(clock);
            var generation = new PartitionGenerationId(
                new TopicPartition("traffic", 2),
                7
            );
            var connection = new ConnectionProcessingId(
                generation,
                new CapturedConnectionId("writer", "captured-connection"),
                3
            );
            var request = new ReplayRequestId(connection, 11);
            var record = new KafkaRecordId(generation, 41);
            var registry = new OutstandingOperationRegistry(
                "request " + request,
                record,
                eventLoop,
                clock,
                failure -> Assertions.fail(failure),
                OutstandingOperationRegistry.CountHook.NOOP
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
            clock.advance(Duration.ofSeconds(30));

            try (var monitor = ActiveContextMonitor.system(
                clock,
                registry::snapshots
            )) {
                monitor.run();
            }

            Assertions.assertTrue(messages.stream().anyMatch(message ->
                message.contains("Active replay work:")
                    && message.contains("active=1")
                    && message.contains("longRunning=1")
            ));
            Assertions.assertTrue(messages.stream().anyMatch(message ->
                message.contains("Long-running replay activity:")
                    && message.contains("partition=traffic-2")
                    && message.contains("generation=7")
                    && message.contains("record=" + record)
                    && message.contains("request=" + request)
                    && message.contains("owner=request " + request)
                    && message.contains("operation=RETRY_TIMER")
                    && message.contains("reason=WAITING_FOR_RETRY_TIME")
            ));
        } finally {
            activityLogger.removeAppender(appender);
            appender.stop();
        }
    }
}
