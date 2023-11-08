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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class PrettyPrinter {

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

    public static <T> T setPrintStyleFor(PacketPrintFormat packetPrintFormat, Supplier<T> supplier) {
        try {
            return setPrintStyleForCallable(packetPrintFormat, (supplier::get));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public enum HttpMessageType { REQUEST, RESPONSE }

    public static String httpPacketBytesToString(HttpMessageType msgType, List<byte[]> byteArrStream) {
        return httpPacketBytesToString(msgType,
                Optional.ofNullable(byteArrStream).map(p -> p.stream()).orElse(Stream.of()));
    }

    public static String httpPacketBytesToString(HttpMessageType msgType, Stream<byte[]> byteArrStream) {
        // This isn't memory efficient,
        // but stringifying byte bufs through a full parse and reserializing them was already really slow!
        var cleanupByteBufs = new ArrayList<ByteBuf>();
        try {
            return httpPacketBufsToString(msgType, byteArrStream.map(bArr ->{
                var bb = Unpooled.wrappedBuffer(bArr);
                cleanupByteBufs.add(bb);
                return bb;
            }));
        } finally {
            cleanupByteBufs.stream().forEach(bb->bb.release());
        }
    }

    public static String httpPacketBufsToString(HttpMessageType msgType, Stream<ByteBuf> byteBufStream) {
        switch (printStyle.get().orElse(PacketPrintFormat.TRUNCATED)) {
            case TRUNCATED:
                return httpPacketBufsToString(byteBufStream, Utils.MAX_BYTES_SHOWN_FOR_TO_STRING);
            case FULL_BYTES:
                return httpPacketBufsToString(byteBufStream, Long.MAX_VALUE);
            case PARSED_HTTP:
                return httpPacketsToPrettyPrintedString(msgType, byteBufStream);
            default:
                throw new RuntimeException("Unknown PacketPrintFormat: " + printStyle.get());
        }
    }

    public static String httpPacketsToPrettyPrintedString(HttpMessageType msgType, Stream<ByteBuf> byteBufStream) {
        HttpMessage httpMessage = parseHttpMessageFromBufs(msgType, byteBufStream);
        var holderOp = Optional.ofNullable((httpMessage instanceof ByteBufHolder) ? (ByteBufHolder) httpMessage : null);
        try {
            if (httpMessage instanceof FullHttpRequest) {
                return prettyPrintNettyRequest((FullHttpRequest) httpMessage);
            } else if (httpMessage instanceof FullHttpResponse) {
                return prettyPrintNettyResponse((FullHttpResponse) httpMessage);
            } else if (httpMessage == null) {
                return "[NULL]";
            } else {
                throw new RuntimeException("Embedded channel with an HttpObjectAggregator returned an unexpected object " +
                        "of type " + httpMessage.getClass() + ": " + httpMessage);
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

    public static String prettyPrintNettyResponse(FullHttpResponse msg) {
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
    static HttpMessage parseHttpMessageFromBufs(HttpMessageType msgType, Stream<ByteBuf> byteBufStream) {
        EmbeddedChannel channel = new EmbeddedChannel(
                msgType == HttpMessageType.REQUEST ? new HttpServerCodec() : new HttpClientCodec(),
                new HttpContentDecompressor(),
                new HttpObjectAggregator(Utils.MAX_PAYLOAD_SIZE_TO_PRINT)  // Set max content length if needed
        );

        byteBufStream.forEach(b -> channel.writeInbound(b.retainedDuplicate()));

        return channel.readInbound();
    }

    public static String httpPacketBufsToString(Stream<ByteBuf> byteBufStream, long maxBytesToShow) {
        if (byteBufStream == null) {
            return "null";
        }
        return byteBufStream.map(originalByteBuf -> {
                    var bb = originalByteBuf.duplicate();
                    var length = bb.readableBytes();
                    var str = IntStream.range(0, length).map(idx -> bb.readByte())
                            .limit(maxBytesToShow)
                            .mapToObj(b -> "" + (char) b)
                            .collect(Collectors.joining());
                    return "[" + (length > maxBytesToShow ? str + "..." : str) + "]";
                })
                .collect(Collectors.joining(","));
    }
}
