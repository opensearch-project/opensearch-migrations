package org.opensearch.migrations.replay.netty;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
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
    void finalResponseStartsAfterAnInterimResponseInAnEarlierPacket() throws InterruptedException {
        var exchange = new SniffedExchange();
        exchange.receive("HTTP/1.1 100 Continue\r\n\r\n");
        var afterInterim = exchange.pause();
        exchange.receive("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n");
        var afterHead = exchange.pause();
        exchange.receive("OK");

        var response = exchange.finish();
        Assertions.assertFalse(response.getFinalResponseFirstPacketTime().isBefore(afterInterim));
        Assertions.assertTrue(response.getFinalResponseFirstPacketTime().isBefore(afterHead));
        Assertions.assertFalse(response.getLastPacketTime().isBefore(afterHead));
    }

    @Test
    void finalResponseStartsInThePacketThatAlsoEndsTheInterimResponse() throws InterruptedException {
        var exchange = new SniffedExchange();
        exchange.receive("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n");
        var afterHead = exchange.pause();
        exchange.receive("OK");

        var response = exchange.finish();
        Assertions.assertTrue(response.getFinalResponseFirstPacketTime().isBefore(afterHead));
    }

    @Test
    void finalResponseWithoutAnInterimResponseStartsWithTheFirstPacket() throws InterruptedException {
        var exchange = new SniffedExchange();
        exchange.receive("HTTP/1.1 200 OK\r\n");
        var afterStatusLine = exchange.pause();
        exchange.receive("Content-Length: 2\r\n\r\nOK");

        var response = exchange.finish();
        Assertions.assertTrue(response.getFinalResponseFirstPacketTime().isBefore(afterStatusLine));
    }

    /** Runs the target's receive pipeline, whose sniffer stamps each packet with its receipt time. */
    private static final class SniffedExchange {
        private final AtomicReference<AggregatedRawResponse> result = new AtomicReference<>();
        private final AggregatedRawResponse.Builder responseBuilder = AggregatedRawResponse.builder(Instant.now());
        private final BacksideHttpWatcherHandler watcher = new BacksideHttpWatcherHandler(responseBuilder);
        private final EmbeddedChannel channel = new EmbeddedChannel(
            new BacksideSnifferHandler(responseBuilder),
            new HttpResponseDecoder(),
            new InterimHttpResponseHandler(responseBuilder::markInterimResponse),
            watcher
        );

        SniffedExchange() {
            watcher.addCallback(result::set);
        }

        void receive(String packet) {
            channel.writeInbound(Unpooled.copiedBuffer(packet, StandardCharsets.US_ASCII));
        }

        /** Returns an instant strictly between the packets received before and after this call. */
        Instant pause() throws InterruptedException {
            Thread.sleep(2);
            var marker = Instant.now();
            Thread.sleep(2);
            return marker;
        }

        AggregatedRawResponse finish() {
            var response = Objects.requireNonNull(result.get(), "the final response did not complete");
            channel.finishAndReleaseAll();
            return response;
        }
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
