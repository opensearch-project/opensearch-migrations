package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.util.NettyUtils;
import org.opensearch.migrations.replay.util.RefSafeHolder;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HttpByteBufFormatter {

    public static final String CRLF_LINE_DELIMITER = "\r\n";
    public static final String LF_LINE_DELIMITER = "\n";
    private static final String DEFAULT_LINE_DELIMITER = CRLF_LINE_DELIMITER;

    private static final ThreadLocal<Optional<PacketPrintFormat>> printStyle = ThreadLocal.withInitial(Optional::empty);

    public enum PacketPrintFormat {
        TRUNCATED,
        FULL_BYTES,
        PARSED_HTTP,
        PARSED_HTTP_SORTED_HEADERS
    }

    public static <T> T setPrintStyleForCallable(PacketPrintFormat packetPrintFormat, Callable<T> r) throws Exception {
        var oldStyle = printStyle.get();
        printStyle.set(Optional.of(packetPrintFormat));
        try {
            return r.call();
        } finally {
            if (oldStyle.isPresent()) {
                printStyle.set(oldStyle);
            } else {
                printStyle.remove();
            }
        }
    }

    @SneakyThrows
    public static <T> T setPrintStyleFor(PacketPrintFormat packetPrintFormat, Supplier<T> supplier) {
        return setPrintStyleForCallable(packetPrintFormat, (supplier::get));
    }

    public enum HttpMessageType {
        REQUEST,
        RESPONSE
    }

    public static String httpPacketBytesToString(HttpMessageType msgType, List<byte[]> byteArrStream) {
        return httpPacketBytesToString(msgType, byteArrStream, DEFAULT_LINE_DELIMITER);
    }

    public static String httpPacketBufsToString(HttpMessageType msgType, Stream<ByteBuf> byteBufStream) {
        return httpPacketBufsToString(msgType, byteBufStream, DEFAULT_LINE_DELIMITER);
    }

    public static String httpPacketBytesToString(HttpMessageType msgType, List<byte[]> byteArrs, String lineDelimiter) {
        // This isn't memory efficient,
        // but stringifying byte bufs through a full parse and reserializing them was already really slow!
        try (var stream = NettyUtils.createRefCntNeutralCloseableByteBufStream(byteArrs)) {
            return httpPacketBufsToString(msgType, stream, lineDelimiter);
        }
    }

    public static String httpPacketBufsToString(
        HttpMessageType msgType,
        Stream<ByteBuf> byteBufStream,
        String lineDelimiter
    ) {
        switch (printStyle.get().orElse(PacketPrintFormat.TRUNCATED)) {
            case TRUNCATED:
                return httpPacketBufsToString(byteBufStream, Utils.MAX_BYTES_SHOWN_FOR_TO_STRING);
            case FULL_BYTES:
                return httpPacketBufsToString(byteBufStream, Long.MAX_VALUE);
            case PARSED_HTTP:
                return httpPacketsToPrettyPrintedString(msgType, byteBufStream, false, lineDelimiter);
            case PARSED_HTTP_SORTED_HEADERS:
                return httpPacketsToPrettyPrintedString(msgType, byteBufStream, true, lineDelimiter);
            default:
                throw new IllegalStateException("Unknown PacketPrintFormat: " + printStyle.get());
        }
    }

    public static String httpPacketsToPrettyPrintedString(
        HttpMessageType msgType,
        Stream<ByteBuf> byteBufStream,
        boolean sortHeaders,
        String lineDelimiter
    ) {
        try (var messageHolder = RefSafeHolder.create(parseHttpMessageFromBufs(msgType, byteBufStream))) {
            final HttpMessage httpMessage = messageHolder.get();
            if (httpMessage != null) {
                if (httpMessage instanceof FullHttpRequest) {
                    return prettyPrintNettyRequest((FullHttpRequest) httpMessage, sortHeaders, lineDelimiter);
                } else if (httpMessage instanceof FullHttpResponse) {
                    return prettyPrintNettyResponse((FullHttpResponse) httpMessage, sortHeaders, lineDelimiter);
                } else {
                    throw new IllegalStateException(
                        "Embedded channel with an HttpObjectAggregator returned an "
                            + "unexpected object of type "
                            + httpMessage.getClass()
                            + ": "
                            + httpMessage
                    );
                }
            } else {
                return "[NULL]";
            }
        }
    }

    public static String prettyPrintNettyRequest(FullHttpRequest msg, boolean sortHeaders, String lineDelimiter) {
        var sj = new StringJoiner(lineDelimiter);
        sj.add(msg.method() + " " + msg.uri() + " " + msg.protocolVersion().text());
        return prettyPrintNettyMessage(sj, sortHeaders, msg, msg.content());
    }

    public static String prettyPrintNettyResponse(FullHttpResponse msg, boolean sortHeaders, String lineDelimiter) {
        var sj = new StringJoiner(lineDelimiter);
        sj.add(msg.protocolVersion().text() + " " + msg.status().code() + " " + msg.status().reasonPhrase());
        return prettyPrintNettyMessage(sj, sortHeaders, msg, msg.content());
    }

    private static String prettyPrintNettyMessage(StringJoiner sj, boolean sorted, HttpMessage msg, ByteBuf content) {
        var h = msg.headers().entries().stream();
        if (sorted) {
            h = h.sorted(Map.Entry.comparingByKey());
        }
        h.forEach(kvp -> sj.add(String.format("%s: %s", kvp.getKey(), kvp.getValue())));
        sj.add("");
        sj.add(content.toString(StandardCharsets.UTF_8));
        return sj.toString();
    }

    /**
     * This won't alter the incoming byteBufStream at all - all buffers are duplicated as they're
     * passed into the parsing channel.  The output message MAY be a ByteBufHolder, in which case,
     * releasing the content() is the caller's responsibility.
     * @param msgType
     * @param byteBufStream
     * @return
     */
    public static HttpMessage parseHttpMessageFromBufs(HttpMessageType msgType, Stream<ByteBuf> byteBufStream) {
        EmbeddedChannel channel = new EmbeddedChannel(
            msgType == HttpMessageType.REQUEST ? new HttpServerCodec() : new HttpClientCodec(),
            new HttpContentDecompressor(),
            new HttpObjectAggregator(Utils.MAX_PAYLOAD_BYTES_TO_PRINT)  // Set max content length if needed
        );
        try {
            byteBufStream.forEachOrdered(b -> channel.writeInbound(b.retainedDuplicate()));
            return channel.readInbound();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    public static FullHttpRequest parseHttpRequestFromBufs(Stream<ByteBuf> byteBufStream) {
        return (FullHttpRequest) parseHttpMessageFromBufs(HttpMessageType.REQUEST, byteBufStream);
    }

    public static FullHttpResponse parseHttpResponseFromBufs(Stream<ByteBuf> byteBufStream) {
        return (FullHttpResponse) parseHttpMessageFromBufs(HttpMessageType.RESPONSE, byteBufStream);
    }

    public static String httpPacketBufsToString(Stream<ByteBuf> byteBufStream, long maxBytesToShow) {
        if (byteBufStream == null) {
            return "null";
        }
        return byteBufStream.map(originalByteBuf -> {
            var bb = originalByteBuf.duplicate();
            var length = bb.readableBytes();
            var str = IntStream.range(0, length)
                .map(idx -> bb.readByte())
                .limit(maxBytesToShow)
                .mapToObj(b -> "" + (char) b)
                .collect(Collectors.joining());
            return "[" + (length > maxBytesToShow ? str + "..." : str) + "]";
        }).collect(Collectors.joining(","));
    }
}
