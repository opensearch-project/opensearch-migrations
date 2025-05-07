package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@WrapWithNettyLeakDetection()
@Slf4j
class HeaderAdderHandlerTest {

    private void runTestsWithSize(Supplier<IntStream> sizesSupplier) {
        runTestWithSize("\n", sizesSupplier.get());
        runTestWithSize("\r\n", sizesSupplier.get());
    }

    @Test
    public void simpleCheck() {
        runTestsWithSize(() -> IntStream.of(Integer.MAX_VALUE));
    }

    @Test
    public void individualBytesCheck() {
        runTestsWithSize(() -> IntStream.generate(()->1));
    }

    @Test
    public void multipleRequestsCheck_headerAdder_withComplexBody_andResponse() {
        var extraHeader = "host: my.host";
        var newHeader = Unpooled.wrappedBuffer(extraHeader.getBytes(StandardCharsets.UTF_8));

        var body = "line1\r\nHost: fake.value\r\nPOST /oops HTTP/1.1\r\n\r\nEND";
        var contentLength = body.getBytes(StandardCharsets.UTF_8).length;
        var contentLengthHeader = "Content-Length: " + contentLength;

        EmbeddedChannel channel = new EmbeddedChannel(new HeaderAdderHandler(newHeader));

        var request = String.join("\r\n",
                "POST /submit HTTP/1.1",
                contentLengthHeader,
                "",
                body
        );

        var expected = String.join("\r\n",
                "POST /submit HTTP/1.1",
                extraHeader,
                contentLengthHeader,
                "",
                body
        );

        try {
            for (int i = 0; i < 5; i++) {
                channel.writeInbound(Unpooled.wrappedBuffer(request.getBytes(StandardCharsets.UTF_8)));
                ByteBuf result = channel.readInbound();
                Assertions.assertEquals(expected, result.toString(StandardCharsets.UTF_8));
                result.release();

                channel.writeOutbound(Unpooled.copiedBuffer("HTTP/1.1 200 OK\r\n\r\n", StandardCharsets.UTF_8));
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "8,27,9999",
        "8,12,16,999"
    })
    public void fragmentedBytesCheck(String sizesStr) {
        runTestsWithSize(() -> Arrays.stream(sizesStr.split(",")).mapToInt(Integer::parseInt));
    }

    private void runTestWithSize(String lineEnding, IntStream sizes) {
        var extraHeader = "host: my.host";
        var newHeader = Unpooled.wrappedBuffer(extraHeader.getBytes(StandardCharsets.UTF_8));
        final var msg = makeMessage(lineEnding, "");

        var channel = new EmbeddedChannel(new HeaderAdderHandler(newHeader));
        sliceMessageIntoChannelWrites(channel, msg, sizes);
        var output = Unpooled.compositeBuffer();
        channel.inboundMessages().forEach(v -> output.addComponent(true, ((ByteBuf) v).retain()));
        channel.finishAndReleaseAll();

        Assertions.assertEquals(makeMessage(lineEnding, extraHeader + lineEnding), output.toString(StandardCharsets.UTF_8));
        output.release();
    }

    public static void sliceMessageIntoChannelWrites(EmbeddedChannel channel, String msg, IntStream sizes) {
        final var lastStart = new AtomicInteger();
        sizes
            .mapToObj(len -> {
                var startIdx = lastStart.get();
                if (startIdx >= msg.length()) { return null; }
                var endIdx = startIdx + len;
                var substr = msg.substring(lastStart.get(), Math.min(endIdx, msg.length()));
                lastStart.set(endIdx);
                log.atTrace().setMessage("s: {}").addArgument(substr).log();
                return substr;
            })
            .takeWhile(Objects::nonNull)
            .forEach(substr -> {
                var bytes = substr.getBytes(StandardCharsets.UTF_8);
                var buf = channel.alloc().buffer(bytes.length);
                channel.writeInbound(buf.writeBytes(bytes));
            });
    }

    String makeMessage(String lineEnding, String extraHeader) {
        return "GET / HTTP/1.1" + lineEnding +
            extraHeader +
            "NICEHeader: v1" + lineEnding +
            "silLYHeader: yyy" + lineEnding +
            lineEnding;
    }
}
