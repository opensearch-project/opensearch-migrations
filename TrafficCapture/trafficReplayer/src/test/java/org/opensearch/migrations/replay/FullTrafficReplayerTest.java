package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.testutils.SimpleHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@WrapWithNettyLeakDetection(repetitions = 1)
public class FullTrafficReplayerTest {
//    @Test
//    public void fullTest() throws Exception {
//        var httpServer = SimpleHttpServer.makeServer(false, TestHttpServerContext::makeResponse);
//        var tr = new TrafficReplayer(httpServer.localhostEndpoint(),
//                new StaticAuthTransformerFactory("TEST"),
//                true, 1,
//                TrafficReplayer.buildDefaultJsonTransformer(httpServer.localhostEndpoint().getHost()));
//
//        try (var os = new ByteArrayOutputStream();
//             var bos = new BufferedOutputStream(os);
//             var is = new FileInputStream(FILL_ME_IN);
//             var trafficSource = new InputStreamOfTraffic(is);
//             var blockingTrafficSource = new BlockingTrafficSource(trafficSource, Duration.ofMillis(80))) {
//            tr.runReplayWithIOStreams(Duration.ofMinutes(1), blockingTrafficSource, bos, new TimeShifter(16));
//        } catch (Exception e) {
//            log.atError().setCause(e).setMessage(()->"eating exception to check for memory leaks.").log();
//        }
//    }
}
