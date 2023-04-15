package org.opensearch.migrations.replay;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

class TrafficReplayerTest {

    @Test
    public void testLineMatcher() {

        var testReadEventStrBuilder = new StringBuilder();
        testReadEventStrBuilder.append("[2023-02-19T17:25:54,638][TRACE][o.o.h.t.WireLogger       ] [primary-cluster-node-1] [id: 0xc40dae30, L:/127.0.0.1:9200 - R:/127.0.0.1:52094]" +
                " READ 86:");
        String partial = "ewoUgICJ";
        for (int i=0; i<1024*1024/partial.length(); ++i) {
            testReadEventStrBuilder.append(partial);
        }
        var testReadEventStr = testReadEventStrBuilder.toString();
        for (int i=0;i<1*100; ++i) {
            TrafficReplayer.LINE_MATCHER.matcher(testReadEventStr).matches();
        }
        Assertions.assertTrue(TrafficReplayer.LINE_MATCHER.matcher(testReadEventStr).matches());
    }

    @Test
    public void testReader() throws IOException, URISyntaxException, InterruptedException {
        var tr = new TrafficReplayer(new URI("http://localhost:9200"));
        List<List<byte[]>> byteArrays = new ArrayList<>();
        TrafficReplayer.ReplayEngine re = new TrafficReplayer.ReplayEngine(rrpp -> {
            byteArrays.add(rrpp.getRequestDataStream().collect(Collectors.toList()));
            var ms = rrpp.getTotalDuration().toMillis();
            Assertions.assertTrue(ms > 0);

        });
        
        try (var sr = new StringReader(SampleFile.contents)) {
            try (var br = new BufferedReader(sr)) {
                try (var cssw = CloseableStringStreamWrapper.generateStreamFromBufferedReader(br)) {
                    tr.runReplay(cssw.stream(), re);
                }
            }
        }
        Assertions.assertEquals(1, byteArrays.size());
        Assertions.assertEquals(2, byteArrays.get(0).size());
    }
}