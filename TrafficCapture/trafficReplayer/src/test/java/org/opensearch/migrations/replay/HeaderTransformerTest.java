package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformer;
import org.opensearch.migrations.transform.JsonTransformer;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class HeaderTransformerTest {

    private static final String SILLY_TARGET_CLUSTER_NAME = "remoteguest";
    private static final String SOURCE_CLUSTER_NAME = "localhost";

    @Test
    public void testTransformer() throws Exception {
        var referenceStringBuilder = new StringBuilder();
        // mock object.  values don't matter at all - not what we're testing
        final var dummyAggregatedResponse = new AggregatedRawResponse(17, null, null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var transformingHandler = new HttpJsonTransformer(
                JsonTransformer.newBuilder()
                        .addHostSwitchOperation(SILLY_TARGET_CLUSTER_NAME)
                        .build(), testPacketCapture);

        Random r = new Random(2);
        var stringParts = IntStream.range(0, 1).mapToObj(i-> TestUtils.makeRandomString(r)).map(o->(String)o)
                .collect(Collectors.toList());

        CompletableFuture<Void> allConsumesFuture =
                TestUtils.dualWriteRequestWithBodyAndCombineConsumptionFutures(transformingHandler,
                        stringParts,
                        referenceStringBuilder,
                        contentLength -> "GET / HTTP/1.1\n" +
                                "HoSt: " + SOURCE_CLUSTER_NAME +  "\n" +
                                "content-length: " + contentLength + "\n"
                );

        var innermostFinalizeCallCount = new AtomicInteger();
        var finalizationFuture = allConsumesFuture.thenCompose(v->transformingHandler.finalizeRequest());
        finalizationFuture.whenComplete((aggregatedRawResponse,t)->{
            Assertions.assertNull(t);
            Assertions.assertNotNull(aggregatedRawResponse);
            // do nothing but check connectivity between the layers in the bottom most handler
            innermostFinalizeCallCount.incrementAndGet();
            Assertions.assertEquals(dummyAggregatedResponse, aggregatedRawResponse);
        });
        finalizationFuture.get();
        Assertions.assertEquals(TestUtils.resolveReferenceString(referenceStringBuilder,
                        List.of(new AbstractMap.SimpleEntry(SOURCE_CLUSTER_NAME, SILLY_TARGET_CLUSTER_NAME))),
                testPacketCapture.getCapturedAsString());
        Assertions.assertEquals(1, innermostFinalizeCallCount.get());
    }

}
