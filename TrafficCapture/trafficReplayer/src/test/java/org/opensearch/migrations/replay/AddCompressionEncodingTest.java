package org.opensearch.migrations.replay;

import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformer;
import org.opensearch.migrations.transform.JsonTransformBuilder;
import org.opensearch.migrations.transform.JsonTransformer;

import java.time.Duration;

public class AddCompressionEncodingTest {
    @Test
    public void addingCompressionRequestHeaderCompressesPayload() {
        final var dummyAggregatedResponse = new AggregatedRawResponse(17, null, null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);
        var compressingHandler = new HttpJsonTransformer(
                JsonTransformer.newBuilder()
                        .addCannedOperation(JsonTransformBuilder.CANNED_OPERATIONS.ADD_GZIP)
                        .build(), testPacketCapture);
    }
}
