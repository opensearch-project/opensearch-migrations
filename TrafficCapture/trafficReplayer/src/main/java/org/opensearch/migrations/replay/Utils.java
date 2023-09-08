package org.opensearch.migrations.replay;

import com.google.protobuf.ByteString;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class Utils {
    public static final int MAX_BYTES_SHOWN_FOR_TO_STRING = 128;
    public static final int MAX_PAYLOAD_SIZE_TO_PRINT = 1024 * 1024; // 1MB

    static Instant setIfLater(AtomicReference<Instant> referenceValue, Instant pointInTime) {
        return referenceValue.updateAndGet(existingInstant -> existingInstant.isBefore(pointInTime) ?
                pointInTime : existingInstant);
    }

    static long setIfLater(AtomicLong referenceValue, long pointInTimeMillis) {
        return referenceValue.updateAndGet(existing -> Math.max(existing, pointInTimeMillis));
    }

    public enum PacketPrintFormat {
        TRUNCATED, FULL_BYTES, PARSED_HTTP
    }
    public static final ThreadLocal<PacketPrintFormat> printStyle =
            ThreadLocal.withInitial(()->PacketPrintFormat.TRUNCATED);

    /**
     * See https://en.wikipedia.org/wiki/Fold_(higher-order_function)
     */
    public static <A, B> Collector<A, ?, B>
    foldLeft(final B seedValue, final BiFunction<? super B, ? super A, ? extends B> f) {
        return Collectors.collectingAndThen(
                Collectors.reducing(
                        Function.<B>identity(),
                        a -> b -> f.apply(b, a),
                        Function::andThen),
                finisherArg -> finisherArg.apply(seedValue)
        );
    }

    public static <T> T setPrintStyleForCallable(PacketPrintFormat packetPrintFormat, Callable<T> r) throws Exception {
        var oldStyle = printStyle.get();
        printStyle.set(packetPrintFormat);
        try {
            return r.call();
        } finally {
            printStyle.set(oldStyle);
        }
    }

    public static <T> T setPrintStyleFor(PacketPrintFormat packetPrintFormat, Supplier<T> supplier) {
        try {
            return setPrintStyleForCallable(packetPrintFormat, (()->supplier.get()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setPrintStyleFor(PacketPrintFormat packetPrintFormat, Runnable r) {
        setPrintStyleFor(packetPrintFormat, ()-> null);
    }

    public enum HttpMessageType { Request, Response }

    public static String httpPacketBytesToString(HttpMessageType msgType, List<byte[]> packetStream) {
        return httpPacketBytesToString(msgType, Optional.ofNullable(packetStream).map(p->p.stream())
                .orElse(null));
    }

    public static String httpPacketBytesToString(HttpMessageType msgType, Stream<byte[]> packetStream) {
        return httpPacketBufsToString(msgType, packetStream.map(bArr->Unpooled.wrappedBuffer(bArr)));
    }

    public static String httpPacketBufsToString(HttpMessageType msgType, Stream<ByteBuf> packetStream) {
        packetStream = packetStream.map(bb->bb.duplicate()); // assume that callers don't want read-indices to be spent
        switch (printStyle.get()) {
            case TRUNCATED:
                return httpPacketBufsToString(packetStream, MAX_BYTES_SHOWN_FOR_TO_STRING);
            case FULL_BYTES:
                return httpPacketBufsToString(packetStream, Long.MAX_VALUE);
            case PARSED_HTTP:
                return httpPacketsToPrettyPrintedString(msgType, packetStream);
            default:
                throw new RuntimeException("Unknown PacketPrintFormat: " + printStyle.get());
        }
    }

    public static String httpPacketsToPrettyPrintedString(HttpMessageType msgType, Stream<ByteBuf> packetStream) {
        HttpMessage httpMessage = parseHttpMessage(msgType, packetStream);
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
    }

    public static HttpMessage parseHttpMessage(HttpMessageType msgType, Stream<ByteBuf> packetStream) {
        EmbeddedChannel channel = new EmbeddedChannel(
                msgType == HttpMessageType.Request ? new HttpServerCodec() : new HttpClientCodec(),
                new HttpContentDecompressor(),
                new HttpObjectAggregator(MAX_PAYLOAD_SIZE_TO_PRINT)  // Set max content length if needed
        );

        packetStream.forEach(b-> {channel.writeInbound(channel.alloc().buffer().writeBytes(b));});

        return channel.readInbound();
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
        msg.headers().forEach(kvp->sj.add(String.format("%s: %s", kvp.getKey(), kvp.getValue())));
        sj.add("");
        sj.add(content.toString(StandardCharsets.UTF_8));
        return sj.toString();
    }

    public static String httpPacketBufsToString(Stream<ByteBuf> packetStream, long maxBytesToShow) {
        if (packetStream == null) { return "null"; }
        return packetStream.map(bArr-> {
            var length = bArr.readableBytes();
                    var str = IntStream.range(0, length).map(idx -> bArr.readByte())
                            .limit(maxBytesToShow)
                            .mapToObj(b -> "" + (char) b)
                            .collect(Collectors.joining());
                    return "[" + (length > maxBytesToShow ? str + "..." : str) + "]";
                })
                .collect(Collectors.joining(","));
    }

    public static String packetsToCompressedTrafficStream(Stream<byte[]> packetStream) {
        var tsb = TrafficStream.newBuilder()
                .setNumberOfThisLastChunk(1);
        var trafficStreamOfReads =
                packetStream.map(bArr->ReadObservation.newBuilder().setData(ByteString.copyFrom(bArr)).build())
                .map(r->TrafficObservation.newBuilder().setRead(r))
                .collect(foldLeft(tsb, (existing,newObs)->tsb.addSubStream(newObs)))
                .build();
        try (var baos = new ByteArrayOutputStream()) {
            try (var gzStream = new GZIPOutputStream(baos)) {
                trafficStreamOfReads.writeTo(gzStream);
            }
            baos.flush();
            var binaryContents = baos.toByteArray();
            return Base64.getEncoder().encodeToString(binaryContents);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    public TrafficStream trafficStreamFromCompressedString(String encodedAndZippedStr) throws Exception {
        try (var bais = new ByteArrayInputStream(Base64.getDecoder().decode(encodedAndZippedStr))) {
            try (var gzis = new GZIPInputStream(bais)) {
                return TrafficStream.parseDelimitedFrom(gzis);
            }
        }
    }
}
