package org.opensearch.migrations.replay;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opensearch.migrations.testutils.CountingNettyResourceLeakDetector;
import org.opensearch.migrations.testutils.TestUtilities;
import org.opensearch.migrations.testutils.TestWithNettyLeakDetection;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@TestWithNettyLeakDetection
public class PrettyPrinterTest {

    @BeforeAll
    public static void setup() {
        CountingNettyResourceLeakDetector.activate();
    }

    final static String SAMPLE_REQUEST_STRING =
            "GET / HTTP/1.1\r\n" +
                    "Connection: Keep-Alive\r\n" +
                    "Host: localhost\r\n" +
                    "User-Agent: UnitTest\r\n" +
                    "Connection: Keep-Alive\r\n" +
                    "\r\n";

    final static String SAMPLE_REQUEST_AS_BLOCKS = "[G],[E],[T],[ ],[/],[ ],[H],[T],[T],[P],[/],[1],[.],[1],[\r" +
            "],[\n" +
            "],[C],[o],[n],[n],[e],[c],[t],[i],[o],[n],[:],[ ],[K],[e],[e],[p],[-],[A],[l],[i],[v],[e],[\r" +
            "],[\n" +
            "],[H],[o],[s],[t],[:],[ ],[l],[o],[c],[a],[l],[h],[o],[s],[t],[\r" +
            "],[\n" +
            "],[U],[s],[e],[r],[-],[A],[g],[e],[n],[t],[:],[ ],[U],[n],[i],[t],[T],[e],[s],[t],[\r" +
            "],[\n" +
            "],[C],[o],[n],[n],[e],[c],[t],[i],[o],[n],[:],[ ],[K],[e],[e],[p],[-],[A],[l],[i],[v],[e],[\r" +
            "],[\n" +
            "],[\r" +
            "],[\n" +
            "]";
    final static String SAMPLE_REQUEST_AS_PARSED_HTTP = "GET / HTTP/1.1\n" +
            "Connection: Keep-Alive\n" +
            "Host: localhost\n" +
            "User-Agent: UnitTest\n" +
            "Connection: Keep-Alive\n" +
            "content-length: 0\n" +
            "\n";

    enum BufferType {
        BYTE_ARRAY, UNPOOLED_BYTEBUF, POOLED_BYTEBUF
    }

    private static Stream<Arguments> makeCombos() {
        return Arrays.stream(BufferType.values())
                .flatMap(b-> Arrays.stream(PrettyPrinter.PacketPrintFormat.values()).map(e->Arguments.of(e,b)));
    }

    @ParameterizedTest
    @MethodSource("makeCombos")
    @TestWithNettyLeakDetection(repetitions = 4)
    public void httpPacketBufsToString(PrettyPrinter.PacketPrintFormat format, BufferType bufferType) {
        byte[] fullTrafficBytes = SAMPLE_REQUEST_STRING.getBytes(StandardCharsets.UTF_8);
        var byteArrays = new ArrayList<byte[]>();
        for (int i=0,step=1; i<fullTrafficBytes.length; i+=step) {
            byteArrays.add(Arrays.copyOfRange(fullTrafficBytes, i, i+step));
        }
        String outputString =
                PrettyPrinter.setPrintStyleFor(format, ()->
                        prettyPrint(byteArrays, PrettyPrinter.HttpMessageType.Request, bufferType));
        Assertions.assertEquals(getExpectedResult(format), outputString);
    }

    private static String prettyPrint(List<byte[]> byteArrays,
                               PrettyPrinter.HttpMessageType messageType,
                               BufferType bufferType) {
        switch (bufferType) {
            case BYTE_ARRAY:
                return PrettyPrinter.httpPacketBytesToString(messageType, byteArrays);
            case UNPOOLED_BYTEBUF:
                return prettyPrintByteBufs(byteArrays, messageType, false);
            case POOLED_BYTEBUF:
                return prettyPrintByteBufs(byteArrays, messageType, true);
            default:
                throw new RuntimeException("Unknown type: " + bufferType);
        }
    }

    private static String prettyPrintByteBufs(List<byte[]> byteArrays,
                                       PrettyPrinter.HttpMessageType messageType,
                                       boolean usePooled) {
        var bbList = byteArrays.stream().map(b->TestUtilities.getByteBuf(b,usePooled)).collect(Collectors.toList());
        var formattedString = PrettyPrinter.httpPacketBufsToString(messageType, bbList.stream());
        bbList.forEach(bb->bb.release());
        return formattedString;
    }

    static String getExpectedResult(PrettyPrinter.PacketPrintFormat format) {
        switch (format) {
            case TRUNCATED:
            case FULL_BYTES:
                return SAMPLE_REQUEST_AS_BLOCKS;
            case PARSED_HTTP:
                return SAMPLE_REQUEST_AS_PARSED_HTTP;
            default:
                throw new RuntimeException("Unknown PacketPrintFormat: "+format);
        }
    }
}