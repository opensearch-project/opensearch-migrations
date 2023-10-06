package org.opensearch.migrations.replay;

import com.google.protobuf.CodedOutputStream;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.testcontainers.shaded.org.apache.commons.io.output.NullOutputStream;

import java.io.BufferedOutputStream;
import java.time.Duration;

@Slf4j
@WrapWithNettyLeakDetection(repetitions = 1)
public class FullTrafficReplayerTest {
    //@Test
//    public void fullTest() throws Exception {
//        var httpServer = SimpleNettyHttpServer.makeServer(false, TestHttpServerContext::makeResponse);
//        var tr = new TrafficReplayer(httpServer.localhostEndpoint(),
//                new StaticAuthTransformerFactory("TEST"),
//                true, 8,
//                TrafficReplayer.buildDefaultJsonTransformer(httpServer.localhostEndpoint().getHost()));
//
//        try (var os = new NullOutputStream();
//             var bos = new BufferedOutputStream(os);
//             var trafficSource = new V0_1TrafficCaptureSource(...);
//             var blockingTrafficSource = new BlockingTrafficSource(trafficSource, Duration.ofMinutes(80),
//                     3, CapturedTrafficToHttpTransactionAccumulator::countRequestsInTrafficStream,
//                     ts->1//+(CodedOutputStream.computeMessageSizeNoTag(ts)/1024)
//             )) {
//            tr.runReplayWithIOStreams(Duration.ofSeconds(70), blockingTrafficSource, bos,
//                    new TimeShifter(1*1000));
//        } catch (Exception e) {
//            log.atError().setCause(e).setMessage(()->"eating exception to check for memory leaks.").log();
//            throw e;
//        }
//    }
}
