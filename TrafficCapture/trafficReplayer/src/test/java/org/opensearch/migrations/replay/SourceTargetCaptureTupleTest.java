package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.datatypes.UniqueSourceRequestKey;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class SourceTargetCaptureTupleTest {
    private static final String NODE_ID = "n";
    public static final String TEST_EXCEPTION_MESSAGE = "TEST_EXCEPTION";

    private static class CloseableLogSetup implements AutoCloseable {
        List<LogEvent> logEvents = new ArrayList<>();
        AbstractAppender testAppender;
        public CloseableLogSetup() {
            testAppender = new AbstractAppender(SourceTargetCaptureTuple.OUTPUT_TUPLE_JSON_LOGGER,
                    null, null, false, null) {
                @Override
                public void append(LogEvent event) {
                    logEvents.add(event);
                }
            };
            var tupleLogger = (Logger) LogManager.getLogger(SourceTargetCaptureTuple.OUTPUT_TUPLE_JSON_LOGGER);
            tupleLogger.setLevel(Level.ALL);
            testAppender.start();
            tupleLogger.setAdditive(false);
            tupleLogger.addAppender(testAppender);
            var loggerCtx = ((LoggerContext) LogManager.getContext(false));
        }

        @Override
        public void close() {
            var tupleLogger = (Logger) LogManager.getLogger(SourceTargetCaptureTuple.OUTPUT_TUPLE_JSON_LOGGER);
            tupleLogger.removeAppender(testAppender);
            testAppender.stop();
        }
    }

    @Test
    public void testTupleNewWithNullKeyThrows() {
        try (var closeableLogSetup = new CloseableLogSetup()) {
            Assertions.assertThrows(Exception.class,
                    () -> new SourceTargetCaptureTuple(null, null, null,
                            null, null, null, null));
            Assertions.assertEquals(0, closeableLogSetup.logEvents.size());
        }
    }

    @Test
    public void testOutputterWithNulls() throws IOException {
        var emptyTuple = new SourceTargetCaptureTuple(
                new UniqueReplayerRequestKey(new PojoTrafficStreamKey(NODE_ID,"c",0), 0, 0),
                null, null, null, null, null, null);
        try (var closeableLogSetup = new CloseableLogSetup()) {
            var consumer = new SourceTargetCaptureTuple.TupleToStreamConsumer();
            consumer.accept(emptyTuple);
            Assertions.assertEquals(1, closeableLogSetup.logEvents.size());
            var contents = closeableLogSetup.logEvents.get(0).getMessage().getFormattedMessage();
            log.info("Output="+contents);
            Assertions.assertTrue(contents.contains(NODE_ID));
        }
    }

    @Test
    public void testOutputterWithNullsShowsException() throws IOException {
        var exception = new Exception(TEST_EXCEPTION_MESSAGE);
        var emptyTuple = new SourceTargetCaptureTuple(
                new UniqueReplayerRequestKey(new PojoTrafficStreamKey(NODE_ID,"c",0), 0, 0),
                null, null, null, null, exception, null);
        try (var closeableLogSetup = new CloseableLogSetup()) {
            var consumer = new SourceTargetCaptureTuple.TupleToStreamConsumer();
            consumer.accept(emptyTuple);
            Assertions.assertEquals(1, closeableLogSetup.logEvents.size());
            var contents = closeableLogSetup.logEvents.get(0).getMessage().getFormattedMessage();
            log.info("Output="+contents);
            Assertions.assertTrue(contents.contains(NODE_ID));
            Assertions.assertTrue(contents.contains(TEST_EXCEPTION_MESSAGE));
        }
    }
}