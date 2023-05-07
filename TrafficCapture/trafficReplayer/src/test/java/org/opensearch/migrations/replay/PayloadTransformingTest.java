package org.opensearch.migrations.replay;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformer;
import org.opensearch.migrations.transform.JsonTransformBuilder;
import org.opensearch.migrations.transform.JsonTransformer;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class PayloadTransformingTest {
    @Test
    public void testSimplePayloadTransform() throws ExecutionException, InterruptedException {
        var referenceStringBuilder = new StringBuilder();
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), null);
        var transformingHandler = new HttpJsonTransformer(
                JsonTransformer.newBuilder()
                        .addCannedOperation(JsonTransformBuilder.CANNED_OPERATIONS.ADD_GZIP)
                        .build(), testPacketCapture);

        Random r = new Random(2);
        var stringParts = IntStream.range(0, 1).mapToObj(i-> TestUtils.makeRandomString(r)).map(o->(String)o)
                .collect(Collectors.toList());

        CompletableFuture<Void> allConsumesFuture =
                TestUtils.dualWriteRequestWithBodyAndCombineConsumptionFutures(transformingHandler,
                        stringParts,
                        referenceStringBuilder,
                        contentLength -> "GET / HTTP/1.1\n" +
                                "host: localhost\n" +
                                "content-length: " + contentLength + "\n"
                );

        var innermostFinalizeCallCount = new AtomicInteger();
        var finalizationFuture = allConsumesFuture.thenCompose(v->transformingHandler.finalizeRequest());
        finalizationFuture.whenComplete((aggregatedRawResponse,t)->{
            Assertions.assertNull(t);
            Assertions.assertNotNull(aggregatedRawResponse);
            // do nothing but check connectivity between the layers in the bottom most handler
            innermostFinalizeCallCount.incrementAndGet();
        });
        finalizationFuture.get();
        Assertions.assertEquals(TestUtils.resolveReferenceString(referenceStringBuilder),
                testPacketCapture.getCapturedAsString());
        Assertions.assertEquals(1, innermostFinalizeCallCount.get());

    }
}
