package org.opensearch.migrations.replay.netty;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@WrapWithNettyLeakDetection
class InterimHttpResponseHandlerTest {

    @Test
    void waitsForFinalResponseBeforeCompletingExchange() {
        var result = new AtomicReference<AggregatedRawResponse>();
        var responseBuilder = AggregatedRawResponse.builder(Instant.now());
        var watcher = new BacksideHttpWatcherHandler(responseBuilder);
        var channel = new EmbeddedChannel(
            new HttpResponseDecoder(),
            new InterimHttpResponseHandler(),
            watcher
        );
        watcher.addCallback(result::set);

        channel.writeInbound(Unpooled.copiedBuffer(
            "HTTP/1.1 100 Continue\r\n\r\n",
            StandardCharsets.US_ASCII
        ));
        Assertions.assertNull(result.get());

        channel.writeInbound(Unpooled.copiedBuffer(
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK",
            StandardCharsets.US_ASCII
        ));

        Assertions.assertNotNull(result.get());
        Assertions.assertEquals(200, result.get().getRawResponse().status().code());
        channel.finishAndReleaseAll();
    }

    @Test
    void preservesSwitchingProtocolsAsTerminalResponse() {
        var channel = new EmbeddedChannel(new InterimHttpResponseHandler());
        var response = Unpooled.copiedBuffer(
            "HTTP/1.1 101 Switching Protocols\r\nConnection: upgrade\r\nUpgrade: websocket\r\n\r\n",
            StandardCharsets.US_ASCII
        );
        var decoder = new EmbeddedChannel(new HttpResponseDecoder());

        Assertions.assertTrue(decoder.writeInbound(response));
        while (true) {
            var message = decoder.readInbound();
            if (message == null) {
                break;
            }
            channel.writeInbound(message);
        }

        var forwardedResponse = channel.readInbound();
        Assertions.assertNotNull(forwardedResponse);
        ReferenceCountUtil.release(forwardedResponse);
        decoder.finishAndReleaseAll();
        channel.finishAndReleaseAll();
    }
}
