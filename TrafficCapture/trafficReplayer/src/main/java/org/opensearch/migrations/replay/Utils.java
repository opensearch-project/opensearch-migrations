package org.opensearch.migrations.replay;

import com.google.protobuf.ByteString;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
public class Utils {
    public static final int MAX_BYTES_SHOWN_FOR_TO_STRING = 128;
    public static final int MAX_PAYLOAD_SIZE_TO_PRINT = 1024 * 1024; // 1MB

    public static Instant setIfLater(AtomicReference<Instant> referenceValue, Instant pointInTime) {
        return referenceValue.updateAndGet(existingInstant -> existingInstant.isBefore(pointInTime) ?
                pointInTime : existingInstant);
    }

    public static long setIfLater(AtomicLong referenceValue, long pointInTimeMillis) {
        return referenceValue.updateAndGet(existing -> Math.max(existing, pointInTimeMillis));
    }

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

    @SneakyThrows(value = {IOException.class})
    public static String packetsToCompressedTrafficStream(Stream<byte[]> byteArrStream) {
        var tsb = TrafficStream.newBuilder()
                .setNumberOfThisLastChunk(1);
        var trafficStreamOfReads =
                byteArrStream.map(bArr->ReadObservation.newBuilder().setData(ByteString.copyFrom(bArr)).build())
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
        }
    }
    
    public TrafficStream trafficStreamFromCompressedString(String encodedAndZippedStr) throws IOException {
        try (var bais = new ByteArrayInputStream(Base64.getDecoder().decode(encodedAndZippedStr))) {
            try (var gzis = new GZIPInputStream(bais)) {
                return TrafficStream.parseDelimitedFrom(gzis);
            }
        }
    }
}
