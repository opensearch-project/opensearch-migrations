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
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.LastHttpContent;
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
                return httpPacketsToPrettyPrintedString(msgType, byteBufStream, false, lineDelimiter,
                    Utils.MAX_PAYLOAD_BYTES_TO_PRINT);
            case PARSED_HTTP_SORTED_HEADERS:
                return httpPacketsToPrettyPrintedString(msgType, byteBufStream, true, lineDelimiter,
                    Utils.MAX_PAYLOAD_BYTES_TO_PRINT);
            default:
                throw new IllegalStateException("Unknown PacketPrintFormat: " + printStyle.get());
        }
    }

    /**
     * @see HttpByteBufFormatter#parseHttpMessageFromBufs
     */
    public static String httpPacketsToPrettyPrintedString(
        HttpMessageType msgType,
        Stream<ByteBuf> byteBufStream,
        boolean sortHeaders,
        String lineDelimiter,
        int maxPayloadBytes
    ) {
        try (var messageHolder = RefSafeHolder.create(parseHttpMessageFromBufs(msgType, byteBufStream, maxPayloadBytes))) {
            final var httpMessage = messageHolder.get();
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

    private static class TruncatingAggregator extends ChannelInboundHandlerAdapter {
        HttpMessage message;
        CompositeByteBuf aggregatedContents;
        private int bytesLeftToRead;
        private int bytesDropped;

        public TruncatingAggregator(int payloadSize) {
            bytesLeftToRead = payloadSize;
            this.aggregatedContents = Unpooled.compositeBuffer();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof HttpContent) {
                HttpContent httpContent = (HttpContent) msg;
                ByteBuf content = httpContent.content();
                var contentSize = content.readableBytes();
                if (contentSize > bytesLeftToRead) {
                    var bytesToTruncate = contentSize - bytesLeftToRead;
                    bytesDropped += bytesToTruncate;
                    content.writerIndex(content.writerIndex() - bytesToTruncate);
                    contentSize = content.readableBytes();
                }
                if (contentSize > 0) {
                    bytesLeftToRead -= contentSize;
                    aggregatedContents.addComponent(true, content);
                } else {
                    content.release();
                }
            }
            if (msg instanceof HttpMessage) { // this & HttpContent are interfaces & 'Full' messages implement both
                message = (HttpMessage) msg;
            }
            if (msg instanceof LastHttpContent) {
                if (bytesDropped > 0) {
                    message.headers().add("payloadBytesDropped", bytesDropped);
                }
                var finalMsg = (message instanceof HttpRequest)
                    ? new DefaultFullHttpRequest(message.protocolVersion(),
                        ((HttpRequest) message).method(),
                        ((HttpRequest) message).uri(),
                        aggregatedContents,
                        message.headers(),
                        ((LastHttpContent) msg).trailingHeaders())
                    : new DefaultFullHttpResponse(message.protocolVersion(),
                        ((HttpResponse)message).status(),
                        aggregatedContents,
                        message.headers(),
                        ((LastHttpContent) msg).trailingHeaders());
                super.channelRead(ctx, finalMsg);
            }
        }
    }

    /**
     * This won't alter the incoming byteBufStream at all - all buffers are duplicated as they're
     * passed into the parsing channel.  The output message MAY be a ByteBufHolder, in which case,
     * releasing the content() is the caller's responsibility.
     *
     * Standard headers won't be removed or changed (like the content-length was in the HttpObjectAggregator).
     * The exact headers should be used except when the contents are truncated.  When the contents are
     * truncated a new "payloadBytesDropped" header will be added with the number of bytes that were truncated.
     * Generally, the actual length of the returned contents ByteBuf won't be available.
     * If it was a chunked encoding, no indication will be present.
     * If the content-length header was used and the message wasn't truncated, the header value will be
     * consistent with the length.  However, if the message WAS truncated for content-length delimited
     * messages, the content-length header value will remain as it was in the original message and the
     * length of the contents will be shortened by payloadBytesDropped.
     */
    public static HttpMessage parseHttpMessageFromBufs(HttpMessageType msgType,
                                                       Stream<ByteBuf> byteBufStream,
                                                       int payloadCeilingBytes) {
        return processHttpMessageFromBufs(msgType,
            byteBufStream,
            new TruncatingAggregator(payloadCeilingBytes));
    }

    public static <T> T processHttpMessageFromBufs(HttpMessageType msgType,
                                                   Stream<ByteBuf> byteBufStream,
                                                   ChannelHandler... handlers) {
        EmbeddedChannel channel = new EmbeddedChannel(
            msgType == HttpMessageType.REQUEST ? new HttpRequestDecoder() : new HttpResponseDecoder(),
            new HttpContentDecompressor(0)
        );
        for (var h : handlers) {
            channel.pipeline().addLast(h);
        }
        try {
            byteBufStream.forEachOrdered(b -> channel.writeInbound(b.retainedDuplicate()));
            T output = channel.readInbound();
            if (output == null && HttpMessageType.RESPONSE.equals(msgType)) {
                log.atDebug().setMessage( () ->
                        "HTTP response was not processed after decoding all bytes. " +
                        "Manually writing empty last content to the channel to signal" +
                        " end of stream to channel handlers." +
                        "This will happen HEAD and CONNECT responses or if a server " +
                        "sends a malformed or incomplete response.").log();
                channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
                output = channel.readInbound();
            }
            channel.checkException();
            return output;
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * @see HttpByteBufFormatter#parseHttpMessageFromBufs
     */
    public static FullHttpRequest parseHttpRequestFromBufs(Stream<ByteBuf> byteBufStream, int maxPayloadBytes) {
        return (FullHttpRequest) parseHttpMessageFromBufs(HttpMessageType.REQUEST, byteBufStream, maxPayloadBytes);
    }

    /**
     * @see HttpByteBufFormatter#parseHttpMessageFromBufs
     */
    public static FullHttpResponse parseHttpResponseFromBufs(Stream<ByteBuf> byteBufStream, int maxPayloadBytes) {
        return (FullHttpResponse) parseHttpMessageFromBufs(HttpMessageType.RESPONSE, byteBufStream, maxPayloadBytes);
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
