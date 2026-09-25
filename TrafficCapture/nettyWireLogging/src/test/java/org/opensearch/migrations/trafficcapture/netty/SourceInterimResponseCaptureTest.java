/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.trafficcapture.netty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceInterimResponseCaptureTest {

    @Test
    void capturesOneHundredOneHundredTwoOneHundredThreeAndMultipleInterimsAsTypedEvents() throws Exception {
        var offloader = new RecordingOffloader();
        try (var rootContext = new TestRootContext()) {
            var channel = channel(rootContext, offloader);
            channel.writeInbound(ascii(
                "POST /thing HTTP/1.1\r\nContent-Length: 2\r\n\r\na"
            ));
            channel.writeOutbound(ascii("HTTP/1.1 100 Cont"));
            channel.writeOutbound(ascii("inue\r\n\r\n"));
            channel.writeInbound(ascii("b"));
            channel.writeOutbound(ascii(
                "HTTP/1.1 102 Processing\r\n\r\n"
                    + "HTTP/1.1 103 Early Hints\r\n\r\n"
                    + "HTTP/1.1 103 Early Hints\r\nLink: </next>\r\n\r\n"
                    + "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
            ));

            Assertions.assertEquals(
                List.of(
                    "HTTP/1.1 100 Continue\r\n\r\n",
                    "HTTP/1.1 102 Processing\r\n\r\n",
                    "HTTP/1.1 103 Early Hints\r\n\r\n",
                    "HTTP/1.1 103 Early Hints\r\nLink: </next>\r\n\r\n"
                ),
                offloader.interimResponses
            );
            Assertions.assertEquals(
                List.of("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"),
                offloader.finalWrites
            );
            channel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "HTTP/1.1 101 Switching Protocols\r\n\r\n",
        "HTTP/1.1 413 Content Too Large\r\nContent-Length: 0\r\n\r\n",
        "HTTP/1.1 417 Expectation Failed\r\nContent-Length: 0\r\n\r\n"
    })
    void switchingProtocolsAndRejectionsRemainOrdinaryFinalWrites(String response) throws Exception {
        var offloader = new RecordingOffloader();
        try (var rootContext = new TestRootContext()) {
            var channel = channel(rootContext, offloader);
            channel.writeInbound(ascii("GET /thing HTTP/1.1\r\nHost: source\r\n\r\n"));
            channel.writeOutbound(ascii(response));

            Assertions.assertTrue(offloader.interimResponses.isEmpty());
            Assertions.assertEquals(List.of(response), offloader.finalWrites);
            channel.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel(TestRootContext rootContext, RecordingOffloader offloader)
        throws IOException {
        return new EmbeddedChannel(
            new ConditionallyReliableLoggingHttpHandler<>(
                rootContext,
                "node",
                "connection",
                ignored -> offloader,
                new RequestCapturePredicate(),
                request -> false,
                new CaptureProcessState(CaptureFailurePolicy.FAIL_OPEN)
            )
        );
    }

    private static ByteBuf ascii(String value) {
        return Unpooled.copiedBuffer(value, StandardCharsets.US_ASCII);
    }

    private static final class RecordingOffloader extends NoopChannelConnectionCaptureSerializer<Void> {
        private final List<String> interimResponses = new ArrayList<>();
        private final List<String> finalWrites = new ArrayList<>();

        @Override
        public void addInterimResponseEvent(Instant timestamp, ByteBuf buffer) {
            interimResponses.add(asString(buffer));
        }

        @Override
        public void addWriteEvent(Instant timestamp, ByteBuf buffer) {
            finalWrites.add(asString(buffer));
        }

        private static String asString(ByteBuf buffer) {
            return buffer.toString(
                buffer.readerIndex(),
                buffer.readableBytes(),
                StandardCharsets.US_ASCII
            );
        }
    }
}
