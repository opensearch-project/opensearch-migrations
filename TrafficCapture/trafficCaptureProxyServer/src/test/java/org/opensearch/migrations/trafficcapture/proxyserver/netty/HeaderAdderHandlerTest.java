package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

class HeaderAdderHandlerTest {

    public static Stream<Arguments> makeArgs() {
        return Stream.of(
            Arguments.of("\n"),
            Arguments.of("\r\n"));
    }

    @ParameterizedTest
    @MethodSource("makeArgs")
    public void simpleCheck(String lineEnding) {
        var extraHeader = "host: my.host\n";
        var newHeader = Unpooled.wrappedBuffer(extraHeader.getBytes(StandardCharsets.UTF_8));
        final var msg = makeMessage(lineEnding, "");

        var channel = new EmbeddedChannel(new HeaderAdderHandler(newHeader));
        channel.writeInbound(Unpooled.wrappedBuffer(msg.getBytes(StandardCharsets.UTF_8)));
        var output = Unpooled.compositeBuffer();
        channel.inboundMessages().forEach(v -> output.addComponent(true, (ByteBuf) v));

        Assertions.assertEquals(makeMessage(lineEnding, extraHeader), output.toString(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @MethodSource("makeArgs")
    public void fragmentedCheck(String lineEnding) {
        var extraHeader = "host: my.host\n";
        var newHeader = Unpooled.wrappedBuffer(extraHeader.getBytes(StandardCharsets.UTF_8));
        final var msg = makeMessage(lineEnding, "");

        var channel = new EmbeddedChannel(new HeaderAdderHandler(newHeader));
        msg.chars().forEach(c -> channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{(byte) c})));
        var output = Unpooled.compositeBuffer();
        channel.inboundMessages().forEach(v -> output.addComponent(true, (ByteBuf) v));

        Assertions.assertEquals(makeMessage(lineEnding, extraHeader), output.toString(StandardCharsets.UTF_8));
    }

    String makeMessage(String lineEnding, String extraHeader) {
        return "GET / HTTP/1.1" + lineEnding +
            extraHeader +
            "NICEHeader: v1" + lineEnding +
            "silLYHeader: yyy" + lineEnding +
            lineEnding;
    }
}