package org.opensearch.migrations.replay;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

class TrafficReplayerTest {
    @Test
    public void testReader() throws IOException {
        var tr = new TrafficReplayer();
        List<List<byte[]>> byteArrays = new ArrayList<>();
        TrafficReplayer.ReplayEngine re = new TrafficReplayer.ReplayEngine(b -> byteArrays.add(b.collect(Collectors.toList())));
        try (var sr = new StringReader(SampleFile.contents)) {
            try (var br = new BufferedReader(sr)) {
                tr.consumeLinesForReader(br, re);
            }
        }
        Assertions.assertEquals(1, byteArrays.size());
        Assertions.assertEquals(2, byteArrays.get(0).size());
    }
}