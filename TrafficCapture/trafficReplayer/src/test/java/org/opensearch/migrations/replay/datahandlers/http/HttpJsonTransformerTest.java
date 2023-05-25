package org.opensearch.migrations.replay.datahandlers.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.TestCapturePacketToHttpHandler;
import org.opensearch.migrations.replay.TestUtils;
import org.opensearch.migrations.transform.JoltJsonTransformer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;

class HttpJsonTransformerTest {
    @Test
    public void testPassThroughSinglePacketPost() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(17, null, null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var transformingHandler = new HttpJsonTransformer(JoltJsonTransformer.newBuilder().build(), testPacketCapture);
        byte[] testBytes;
        try (var sampleStream = HttpJsonTransformer.class.getResourceAsStream(
                "/requests/raw/post_formUrlEncoded_withFixedLength.txt")) {
            testBytes = sampleStream.readAllBytes();
        }
        transformingHandler.consumeBytes(testBytes);
        var returnedResponse = transformingHandler.finalizeRequest().get();
        Assertions.assertEquals(new String(testBytes, StandardCharsets.UTF_8),
                testPacketCapture.getCapturedAsString());
        Assertions.assertArrayEquals(testBytes, testPacketCapture.getBytesCaptured());
        Assertions.assertEquals(AggregatedRawResponse.HttpRequestTransformationStatus.SKIPPED,
                returnedResponse.getTransformationStatus());
    }
}