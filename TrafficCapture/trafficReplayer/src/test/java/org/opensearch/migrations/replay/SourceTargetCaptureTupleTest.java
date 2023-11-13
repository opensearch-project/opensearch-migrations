package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.datatypes.UniqueSourceRequestKey;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class SourceTargetCaptureTupleTest {
    private static final String NODE_ID = "n";
    public static final String TEST_EXCEPTION_MESSAGE = "TEST_EXCEPTION";

    @Test
    public void testTupleNewWithNullKeyThrows() {
        Assertions.assertThrows(Exception.class,
                ()->new SourceTargetCaptureTuple(null, null, null,
                        null, null, null, null));
    }

    @Test
    public void testOutputterWithNulls() throws IOException {
        var emptyTuple = new SourceTargetCaptureTuple(
                new UniqueReplayerRequestKey(new PojoTrafficStreamKey(NODE_ID,"c",0), 0, 0),
                null, null, null, null, null, null);
        String contents;
        try (var byteArrayOutputStream = new ByteArrayOutputStream()) {
            var consumer = new SourceTargetCaptureTuple.TupleToStreamConsumer(byteArrayOutputStream);
            consumer.accept(emptyTuple);
            contents = new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
        }
        log.info("Output="+contents);
        Assertions.assertTrue(contents.contains(NODE_ID));
    }

    @Test
    public void testOutputterWithNullsShowsException() throws IOException {
        var exception = new Exception(TEST_EXCEPTION_MESSAGE);
        var emptyTuple = new SourceTargetCaptureTuple(
                new UniqueReplayerRequestKey(new PojoTrafficStreamKey(NODE_ID,"c",0), 0, 0),
                null, null, null, null, exception, null);
        String contents;
        try (var byteArrayOutputStream = new ByteArrayOutputStream()) {
            var consumer = new SourceTargetCaptureTuple.TupleToStreamConsumer(byteArrayOutputStream);
            consumer.accept(emptyTuple);
            contents = new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
        }
        log.info("Output="+contents);
        Assertions.assertTrue(contents.contains(NODE_ID));
        Assertions.assertTrue(contents.contains(TEST_EXCEPTION_MESSAGE));
    }
}