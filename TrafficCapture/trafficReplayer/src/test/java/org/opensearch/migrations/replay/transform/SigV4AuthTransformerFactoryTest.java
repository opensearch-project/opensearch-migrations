package org.opensearch.migrations.replay.transform;


import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.datahandlers.http.HttpJsonRequestWithFaultingPayload;
import org.opensearch.migrations.replay.datahandlers.http.ListKeyAdaptingCaseInsensitiveHeadersMap;
import org.opensearch.migrations.replay.datahandlers.http.SigningByteBufListProducer;
import org.opensearch.migrations.replay.datahandlers.http.StrictCaseInsensitiveHttpHeadersMap;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@WrapWithNettyLeakDetection
class SigningByteBufListProducerOwnershipTest {
    @Test
    void everyAttemptSignsFreshHeadersAndOwnsOnlyItsSerializedPackets() {
        var signatures = new AtomicInteger();
        var serializedAuthorizations = new ArrayList<String>();
        var body = Unpooled.copiedBuffer("body", StandardCharsets.UTF_8);
        var producer = new SigningByteBufListProducer(
            template(),
            new ArrayList<>(List.of(body)),
            request -> Map.of(
                "Authorization",
                List.of("attempt-" + signatures.incrementAndGet())
            ),
            List.of(List.of(1), List.of(4)),
            request -> {
                var authorization = request.headers()
                    .insensitiveGet("Authorization")
                    .getFirst();
                serializedAuthorizations.add(authorization);
                var header = Unpooled.copiedBuffer(
                    authorization + "\r\n",
                    StandardCharsets.UTF_8
                );
                var packets = new ByteBufList(header, body);
                header.release();
                return packets;
            }
        );

        var first = producer.newAttempt();
        var second = producer.newAttempt();
        try {
            Assertions.assertEquals(2, signatures.get());
            Assertions.assertEquals(
                List.of("attempt-1", "attempt-2"),
                serializedAuthorizations
            );
            Assertions.assertNotSame(first.packets(), second.packets());

            first.close();
            first.close();
            Assertions.assertTrue(first.packets().isClosed());
            Assertions.assertFalse(second.packets().isClosed());
            Assertions.assertEquals(
                2,
                body.refCnt(),
                "the producer and the still-open second attempt retain the reusable body"
            );

            second.close();
            Assertions.assertTrue(second.packets().isClosed());
            Assertions.assertEquals(
                1,
                body.refCnt(),
                "attempt closure releases its signed packet list without closing the producer body"
            );
        } finally {
            first.close();
            second.close();
            producer.close();
        }
        Assertions.assertEquals(0, body.refCnt());
    }

    private static HttpJsonRequestWithFaultingPayload template() {
        var request = new HttpJsonRequestWithFaultingPayload();
        request.setMethod("GET");
        request.setPath("/");
        request.setProtocol("HTTP/1.1");
        request.setHeaders(new ListKeyAdaptingCaseInsensitiveHeadersMap(
            StrictCaseInsensitiveHttpHeadersMap.fromMap(
                Map.of("Host", List.of("source.example"))
            )
        ));
        return request;
    }
}
