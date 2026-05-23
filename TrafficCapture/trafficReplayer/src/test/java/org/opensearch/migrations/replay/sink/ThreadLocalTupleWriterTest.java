package org.opensearch.migrations.replay.sink;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.ParsedHttpMessagesAsDicts;
import org.opensearch.migrations.replay.SourceTargetCaptureTuple;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ThreadLocalTupleWriterTest {

    @Test
    void appliesTupleTransformBeforeWritingToSink() {
        var writtenTuple = new AtomicReference<Map<String, Object>>();
        var parsed = mock(ParsedHttpMessagesAsDicts.class);
        var tuple = mock(SourceTargetCaptureTuple.class);
        var tupleMap = new LinkedHashMap<String, Object>();
        tupleMap.put("connectionId", "stream.0");
        when(parsed.toTupleMap(tuple)).thenReturn(tupleMap);

        try (var writer = new ThreadLocalTupleWriter(
            sinkIndex -> new CallbackTupleSink(writtenTuple::set),
            () -> incoming -> {
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) incoming;
                map.put("transformApplied", true);
                return map;
            }
        )) {
            writer.writeTuple(tuple, parsed).join();
        }

        Assertions.assertNotNull(writtenTuple.get());
        Assertions.assertEquals(true, writtenTuple.get().get("transformApplied"));
    }
}
