package org.opensearch.migrations.replay.sink;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

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

*/
// REBUILD-LIMBO-END(G11)