package org.opensearch.migrations.replay.netty;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.AggregatedRawResponse;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BacksideHttpWatcherHandlerTest {

    private static final String HTTP_RESPONSE_100 = "HTTP/1.1 100 Continue\r\n\r\n";
    private static final String HTTP_RESPONSE_103 = "HTTP/1.1 103 Early Hints\r\n"
        + "Link: </style.css>; rel=preload\r\n\r\n";
    private static final String HTTP_RESPONSE_200 = "HTTP/1.1 200 OK\r\n"
        + "Content-Length: 2\r\n\r\nOK";
    private static final String HTTP_RESPONSE_101 = "HTTP/1.1 101 Switching Protocols\r\n"
        + "Upgrade: websocket\r\nConnection: Upgrade\r\n\r\n";

    private static AggregatedRawResponse drive(String... responseChunks) {
        var builder = AggregatedRawResponse.builder(Instant.now());
        var watcher = new BacksideHttpWatcherHandler(builder);
        var channel = new EmbeddedChannel(new HttpClientCodec(), watcher);
        var capture = new AtomicReference<AggregatedRawResponse>();
        watcher.addCallback(capture::set);
        for (var chunk : responseChunks) {
            channel.writeInbound(Unpooled.wrappedBuffer(chunk.getBytes(StandardCharsets.UTF_8)));
        }
        channel.finishAndReleaseAll();
        return capture.get();
    }

    @Test
    void interim100ContinueIsCapturedSeparately() {
        var result = drive(HTTP_RESPONSE_100, HTTP_RESPONSE_200);
        Assertions.assertEquals(200, result.getRawResponse().status().code());
        Assertions.assertEquals(1, result.getInterimResponsePackets().size());
        var interim = new String(result.getInterimResponsePackets().get(0), StandardCharsets.ISO_8859_1);
        Assertions.assertTrue(interim.startsWith("HTTP/1.1 100 Continue"),
            () -> "expected 100 Continue, got: " + interim);
    }

    @Test
    void multiple1xxResponsesAreAllCaptured() {
        var result = drive(HTTP_RESPONSE_103, HTTP_RESPONSE_103, HTTP_RESPONSE_200);
        Assertions.assertEquals(200, result.getRawResponse().status().code());
        Assertions.assertEquals(2, result.getInterimResponsePackets().size());
    }

    @Test
    void switchingProtocols101IsTreatedAsFinalResponse() {
        var result = drive(HTTP_RESPONSE_101);
        Assertions.assertEquals(101, result.getRawResponse().status().code());
        Assertions.assertTrue(result.getInterimResponsePackets().isEmpty(),
            "101 must not be captured as interim");
    }

    @Test
    void noInterimResponsesWhenAbsent() {
        var result = drive(HTTP_RESPONSE_200);
        Assertions.assertEquals(200, result.getRawResponse().status().code());
        Assertions.assertTrue(result.getInterimResponsePackets().isEmpty());
    }
}
