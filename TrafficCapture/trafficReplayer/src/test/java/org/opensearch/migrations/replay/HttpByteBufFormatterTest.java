package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.opensearch.migrations.replay.util.RefSafeStreamUtils;
import org.opensearch.migrations.testutils.CountingNettyResourceLeakDetector;
import org.opensearch.migrations.testutils.TestUtilities;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

@WrapWithNettyLeakDetection
public class HttpByteBufFormatterTest {

    @BeforeAll
    public static void setup() {
        CountingNettyResourceLeakDetector.activate();
    }

    final static String SAMPLE_REQUEST_STRING = "GET / HTTP/1.1\r\n"
        + "Host: localhost\r\n"
        + "Connection: Keep-Alive\r\n"
        + "User-Agent: UnitTest\r\n"
        + "\r\n";

    final static String SAMPLE_REQUEST_AS_BLOCKS = "[G],[E],[T],[ ],[/],[ ],[H],[T],[T],[P],[/],[1],[.],[1],"
        + "[\r],[\n],"
        + "[H],[o],[s],[t],[:],[ ],[l],[o],[c],[a],[l],[h],[o],[s],[t],"
        + "[\r],[\n],"
        + "[C],[o],[n],[n],[e],[c],[t],[i],[o],[n],[:],[ ],[K],[e],[e],[p],[-],[A],[l],[i],[v],[e],"
        + "[\r],[\n],"
        + "[U],[s],[e],[r],[-],[A],[g],[e],[n],[t],[:],[ ],[U],[n],[i],[t],[T],[e],[s],[t],"
        + "[\r],[\n],"
        + "[\r],[\n]";

    final static String SAMPLE_REQUEST_AS_PARSED_HTTP = "GET / HTTP/1.1\r\n"
        + "Host: localhost\r\n"
        + "Connection: Keep-Alive\r\n"
        + "User-Agent: UnitTest\r\n"
        + "content-length: 0\r\n"
        + "\r\n";

    final static String SAMPLE_REQUEST_AS_PARSED_HTTP_SORTED = "GET / HTTP/1.1\r\n"
        + "Connection: Keep-Alive\r\n"
        + "Host: localhost\r\n"
        + "User-Agent: UnitTest\r\n"
        + "content-length: 0\r\n"
        + "\r\n";

    enum BufferType {
        BYTE_ARRAY,
        UNPOOLED_BYTEBUF,
        POOLED_BYTEBUF
    }

    enum BufferContent {
        SimpleGetRequest,
        Empty
    }

    private static Stream<Arguments> makeCombos() {
        return Arrays.stream(BufferType.values())
            .flatMap(
                b -> Arrays.stream(HttpByteBufFormatter.PacketPrintFormat.values())
                    .flatMap(fmt -> Arrays.stream(BufferContent.values()).map(str -> Arguments.of(fmt, b, str)))
            );
    }

    public static byte[] getBytesForScenario(BufferContent contentDirective) {
        switch (contentDirective) {
            case SimpleGetRequest:
                return SAMPLE_REQUEST_STRING.getBytes(StandardCharsets.UTF_8);
            case Empty:
                return new byte[0];
            default:
                throw new IllegalStateException("Unknown scenario type: " + contentDirective);
        }
    }

    @ParameterizedTest
    @MethodSource("makeCombos")
    @WrapWithNettyLeakDetection(repetitions = 4)
    public void httpPacketBufsToString(
        HttpByteBufFormatter.PacketPrintFormat format,
        BufferType bufferType,
        BufferContent contentDirective
    ) {
        var fullTrafficBytes = getBytesForScenario(contentDirective);
        var byteArrays = new ArrayList<byte[]>();
        for (int i = 0, step = 1; i < fullTrafficBytes.length; i += step) {
            byteArrays.add(Arrays.copyOfRange(fullTrafficBytes, i, i + step));
        }
        String outputString = HttpByteBufFormatter.setPrintStyleFor(
            format,
            () -> prettyPrint(byteArrays, HttpByteBufFormatter.HttpMessageType.REQUEST, bufferType)
        );
        Assertions.assertEquals(
            getExpectedResult(format, contentDirective),
            outputString,
            "Strings did not match, after escaping, showing expected and actual on different lines: \n"
                + escapeSpecialCharacters(getExpectedResult(format, contentDirective))
                + "\n"
                + escapeSpecialCharacters(outputString)
        );
    }

    public static String escapeSpecialCharacters(String input) {
        return input.replace("\\", "\\\\")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
            .replace("\f", "\\f")
            .replace("\r", "\\r")
            .replace("\"", "\\\"")
            .replace("'", "\\'");
    }

    @Test
    public void httpPostPacketToHttpParsedString() throws Exception {
        try (
            var sampleStream = HttpByteBufFormatterTest.class.getResourceAsStream(
                "/requests/raw/post_formUrlEncoded_withFixedLength.txt"
            )
        ) {
            var fullTrafficBytes = sampleStream.readAllBytes();

            var byteArrays = new ArrayList<byte[]>();
            for (int i = 0, step = 1; i < fullTrafficBytes.length; i += step) {
                byteArrays.add(Arrays.copyOfRange(fullTrafficBytes, i, i + step));
            }
            String outputString = HttpByteBufFormatter.setPrintStyleFor(
                HttpByteBufFormatter.PacketPrintFormat.PARSED_HTTP,
                () -> prettyPrint(byteArrays, HttpByteBufFormatter.HttpMessageType.REQUEST, BufferType.POOLED_BYTEBUF)
            );
            Assertions.assertEquals(new String(fullTrafficBytes, StandardCharsets.UTF_8), outputString);
        }
    }

    private static String prettyPrint(
        List<byte[]> byteArrays,
        HttpByteBufFormatter.HttpMessageType messageType,
        BufferType bufferType
    ) {
        switch (bufferType) {
            case BYTE_ARRAY:
                return HttpByteBufFormatter.httpPacketBytesToString(messageType, byteArrays);
            case UNPOOLED_BYTEBUF:
                return prettyPrintByteBufs(byteArrays, messageType, false);
            case POOLED_BYTEBUF:
                return prettyPrintByteBufs(byteArrays, messageType, true);
            default:
                throw new IllegalStateException("Unknown type: " + bufferType);
        }
    }

    private static String prettyPrintByteBufs(
        List<byte[]> byteArrays,
        HttpByteBufFormatter.HttpMessageType messageType,
        boolean usePooled
    ) {
        return RefSafeStreamUtils.refSafeTransform(
            byteArrays.stream(),
            b -> TestUtilities.getByteBuf(b, usePooled),
            bbs -> HttpByteBufFormatter.httpPacketBufsToString(messageType, bbs)
        );

    }

    static String getExpectedResult(HttpByteBufFormatter.PacketPrintFormat format, BufferContent content) {
        switch (format) {
            case TRUNCATED:
            case FULL_BYTES:
                switch (content) {
                    case Empty:
                        return "";
                    case SimpleGetRequest:
                        return SAMPLE_REQUEST_AS_BLOCKS;
                    default:
                        throw new IllegalStateException("Unknown BufferContent value: " + content);
                }
            case PARSED_HTTP:
            case PARSED_HTTP_SORTED_HEADERS:
                switch (content) {
                    case Empty:
                        return "[NULL]";
                    case SimpleGetRequest:
                        switch (format) {
                            case PARSED_HTTP:
                                return SAMPLE_REQUEST_AS_PARSED_HTTP;
                            case PARSED_HTTP_SORTED_HEADERS:
                                return SAMPLE_REQUEST_AS_PARSED_HTTP_SORTED;
                        }
                    default:
                        throw new IllegalStateException("Unknown BufferContent value: " + content);
                }
            default:
                throw new IllegalStateException("Unknown PacketPrintFormat: " + format);
        }
    }
}
