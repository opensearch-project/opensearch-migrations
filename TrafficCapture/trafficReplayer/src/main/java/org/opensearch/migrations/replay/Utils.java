package org.opensearch.migrations.replay;

import com.google.protobuf.ByteString;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpServerCodec;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

public class Utils {
    public static final int MAX_BYTES_SHOWN_FOR_TO_STRING = 4096;
    public static final int MAX_PAYLOAD_SIZE_TO_PRINT = 1024 * 1024; // 1MB
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

    public static String httpPacketsToString(List<byte[]> packetStream) {
        return httpPacketsToString(Optional.ofNullable(packetStream).map(p->p.stream()).orElse(null));
    }

    public static String httpPacketsToString(Stream<byte[]> packetStream) {
        switch (printStyle.get()) {
            case TRUNCATED:
                return httpPacketsToString(packetStream, MAX_BYTES_SHOWN_FOR_TO_STRING);
            case FULL_BYTES:
                return httpPacketsToString(packetStream, Long.MAX_VALUE);
            case PARSED_HTTP:
                return httpPacketsToPrettyPrintedString(packetStream);
            default:
                throw new RuntimeException("Unknown PacketPrintFormat: " + printStyle.get());
        }
    }

    public static String httpPacketsToPrettyPrintedString(Stream<byte[]> packetStream) {
        EmbeddedChannel channel = new EmbeddedChannel(
                new HttpServerCodec(),
                new HttpContentDecompressor(),
                new HttpObjectAggregator(MAX_PAYLOAD_SIZE_TO_PRINT)  // Set max content length if needed
        );

        packetStream.forEach(b-> {channel.writeInbound(channel.alloc().buffer().writeBytes(b));});

        var httpMessage = (HttpMessage) channel.readInbound();
        if (httpMessage instanceof FullHttpRequest) {
            return prettyprintNettyRequest((FullHttpRequest) httpMessage);
        } else if (httpMessage instanceof FullHttpResponse) {
            return prettyprintNettyResponse((FullHttpResponse) httpMessage);
        } else {
            throw new RuntimeException("Embedded channel with an HttpObjectAggregator returned an unexpected object " +
                    "of type " + httpMessage.getClass() + ": " + httpMessage);
        }
    }

    public static String prettyprintNettyRequest(FullHttpRequest msg) {
        var sj = new StringJoiner("\n");
        sj.add(msg.method() + " " + msg.uri() + " " + msg.protocolVersion().text());
        return prettyprintNettyMessage(sj, msg, msg.content());
    }

    public static String prettyprintNettyResponse(FullHttpResponse msg) {
        var sj = new StringJoiner("\n");
        sj.add(msg.protocolVersion().text() + " " + msg.status().code() + " " + msg.status().reasonPhrase());
        return prettyprintNettyMessage(sj, msg, msg.content());
    }


    private static String prettyprintNettyMessage(StringJoiner sj, HttpMessage msg, ByteBuf content) {
        msg.headers().forEach(kvp->sj.add(String.format("%s: %s", kvp.getKey(), kvp.getValue())));
        sj.add("");
        sj.add(content.toString(StandardCharsets.UTF_8));
        return sj.toString();
    }

    private static String httpPacketsToString(Stream<byte[]> packetStream, long maxBytesToShow) {
        if (packetStream == null) { return "null"; }
        return packetStream.map(bArr-> {
                    var str = IntStream.range(0, bArr.length).map(idx -> bArr[idx])
                            .limit(maxBytesToShow)
                            .mapToObj(b -> "" + (char) b)
                            .collect(Collectors.joining());
                    return "[" + (bArr.length > maxBytesToShow ? str + "..." : str) + "]";
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
}
