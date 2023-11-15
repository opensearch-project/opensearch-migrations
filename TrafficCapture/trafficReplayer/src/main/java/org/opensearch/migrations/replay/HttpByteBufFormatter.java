package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import lombok.SneakyThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class HttpByteBufFormatter {

    private static final ThreadLocal<Optional<PacketPrintFormat>> printStyle =
            ThreadLocal.withInitial(Optional::empty);

    public enum PacketPrintFormat {
        TRUNCATED, FULL_BYTES, PARSED_HTTP
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

    public enum HttpMessageType { REQUEST, RESPONSE }

    public static String httpPacketBytesToString(HttpMessageType msgType, List<byte[]> byteArrStream) {
        return httpPacketBytesToString(msgType,
                Optional.ofNullable(byteArrStream).map(p -> p.stream()).orElse(Stream.of()));
    }

    public static String httpPacketBytesToString(HttpMessageType msgType, Stream<byte[]> byteArrStream) {
        // This isn't memory efficient,
        // but stringifying byte bufs through a full parse and reserializing them was already really slow!
        return httpPacketBufsToString(msgType, byteArrStream.map(Unpooled::wrappedBuffer), true);
    }

    public static String httpPacketBufsToString(HttpMessageType msgType, Stream<ByteBuf> byteBufStream,
                                                boolean releaseByteBufs) {
        switch (printStyle.get().orElse(PacketPrintFormat.TRUNCATED)) {
            case TRUNCATED:
                return httpPacketBufsToString(byteBufStream, Utils.MAX_BYTES_SHOWN_FOR_TO_STRING, releaseByteBufs);
            case FULL_BYTES:
                return httpPacketBufsToString(byteBufStream, Long.MAX_VALUE, releaseByteBufs);
            case PARSED_HTTP:
                return httpPacketsToPrettyPrintedString(msgType, byteBufStream, releaseByteBufs);
            default:
                throw new IllegalStateException("Unknown PacketPrintFormat: " + printStyle.get());
        }
    }

    public static String httpPacketsToPrettyPrintedString(HttpMessageType msgType, Stream<ByteBuf> byteBufStream,
                                                          boolean releaseByteBufs) {
        HttpMessage httpMessage = parseHttpMessageFromBufs(msgType, byteBufStream, releaseByteBufs);
        var holderOp = Optional.ofNullable((httpMessage instanceof ByteBufHolder) ? (ByteBufHolder) httpMessage : null);
        try {
            if (httpMessage instanceof FullHttpRequest) {
                return prettyPrintNettyRequest((FullHttpRequest) httpMessage);
            } else if (httpMessage instanceof FullHttpResponse) {
                return prettyPrintNettyResponse((FullHttpResponse) httpMessage);
            } else if (httpMessage == null) {
                return "[NULL]";
            } else {
                throw new IllegalStateException("Embedded channel with an HttpObjectAggregator returned an " +
                        "unexpected object of type " + httpMessage.getClass() + ": " + httpMessage);
            }
        } finally {
            holderOp.ifPresent(bbh->bbh.content().release());
        }
    }

    public static String prettyPrintNettyRequest(FullHttpRequest msg) {
        var sj = new StringJoiner("\n");
        sj.add(msg.method() + " " + msg.uri() + " " + msg.protocolVersion().text());
        return prettyPrintNettyMessage(sj, msg, msg.content());
    }

    static String prettyPrintNettyResponse(FullHttpResponse msg) {
        var sj = new StringJoiner("\n");
        sj.add(msg.protocolVersion().text() + " " + msg.status().code() + " " + msg.status().reasonPhrase());
        return prettyPrintNettyMessage(sj, msg, msg.content());
    }

    private static String prettyPrintNettyMessage(StringJoiner sj, HttpMessage msg, ByteBuf content) {
        msg.headers().forEach(kvp -> sj.add(String.format("%s: %s", kvp.getKey(), kvp.getValue())));
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
    public static HttpMessage parseHttpMessageFromBufs(HttpMessageType msgType, Stream<ByteBuf> byteBufStream,
                                                       boolean releaseByteBufs) {
        EmbeddedChannel channel = new EmbeddedChannel(
                msgType == HttpMessageType.REQUEST ? new HttpServerCodec() : new HttpClientCodec(),
                new HttpContentDecompressor(),
                new HttpObjectAggregator(Utils.MAX_PAYLOAD_SIZE_TO_PRINT)  // Set max content length if needed
        );

        byteBufStream.forEach(b -> {
            try {
                channel.writeInbound(b.retainedDuplicate());
            } finally {
                if (releaseByteBufs) {
                    b.release();
                }
            }
        });

        return channel.readInbound();
    }

    public static FullHttpRequest parseHttpRequestFromBufs(Stream<ByteBuf> byteBufStream, boolean releaseByteBufs) {
        return (FullHttpRequest) parseHttpMessageFromBufs(HttpMessageType.REQUEST, byteBufStream, releaseByteBufs);
    }

    public static FullHttpResponse parseHttpResponseFromBufs(Stream<ByteBuf> byteBufStream, boolean releaseByteBufs) {
        return (FullHttpResponse) parseHttpMessageFromBufs(HttpMessageType.RESPONSE, byteBufStream, releaseByteBufs);
    }

    public static String httpPacketBufsToString(Stream<ByteBuf> byteBufStream, long maxBytesToShow,
                                                boolean releaseByteBufs) {
        if (byteBufStream == null) {
            return "null";
        }
        return byteBufStream.map(originalByteBuf -> {
                    try {
                        var bb = originalByteBuf.duplicate();
                        var length = bb.readableBytes();
                        var str = IntStream.range(0, length).map(idx -> bb.readByte())
                                .limit(maxBytesToShow)
                                .mapToObj(b -> "" + (char) b)
                                .collect(Collectors.joining());
                        return "[" + (length > maxBytesToShow ? str + "..." : str) + "]";
                    } finally {
                        if (releaseByteBufs) {
                            originalByteBuf.release();
                        }
                    }})
                .collect(Collectors.joining(","));
    }
}
