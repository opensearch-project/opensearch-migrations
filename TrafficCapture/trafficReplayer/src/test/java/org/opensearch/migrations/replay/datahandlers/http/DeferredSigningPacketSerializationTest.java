package org.opensearch.migrations.replay.datahandlers.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@WrapWithNettyLeakDetection
class DeferredSigningPacketSerializationTest {
    @Test
    void headerOnlySerializationDoesNotInsertAnEmptyPacketBeforeThePreservedBody() {
        var body = Unpooled.copiedBuffer("body", StandardCharsets.UTF_8);
        var headers = new HttpJsonRequestWithFaultingPayload();
        headers.setMethod("POST");
        headers.setPath("/index/_doc");
        headers.setProtocol("HTTP/1.1");
        headers.setHeaders(new ListKeyAdaptingCaseInsensitiveHeadersMap(
            StrictCaseInsensitiveHttpHeadersMap.fromMap(
                Map.of(
                    "Authorization", List.of("signed"),
                    "Content-Length", List.of(Integer.toString(body.readableBytes())),
                    "Host", List.of("target.example")
                )
            )
        ));

        var packets = HttpJsonTransformingConsumer.serializeHeadersAndPrependToBody(
            headers,
            new ArrayList<>(List.of(body)),
            List.of(List.of(64), List.of(body.readableBytes()))
        );
        try {
            var packetBytes = packets.asByteArrayStream().toList();
            Assertions.assertEquals(2, packetBytes.size());
            Assertions.assertTrue(
                new String(packetBytes.getFirst(), StandardCharsets.UTF_8)
                    .endsWith("\r\n\r\n")
            );
            Assertions.assertArrayEquals(
                "body".getBytes(StandardCharsets.UTF_8),
                packetBytes.get(1)
            );
        } finally {
            packets.release();
            body.release();
        }
    }
}
