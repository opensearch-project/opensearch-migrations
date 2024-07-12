package org.opensearch.migrations.replay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.protobuf.ByteString;

import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Utils {
    public static final int MAX_BYTES_SHOWN_FOR_TO_STRING = 128;
    public static final int MAX_PAYLOAD_BYTES_TO_PRINT = 100 * 1024 * 1024; // 100MiB based on
                                                                            // https://docs.aws.amazon.com/opensearch-service/latest/developerguide/limits.html#network-limits

    public static Instant setIfLater(AtomicReference<Instant> referenceValue, Instant pointInTime) {
        return referenceValue.updateAndGet(
            existingInstant -> existingInstant.isBefore(pointInTime) ? pointInTime : existingInstant
        );
    }

    public static long setIfLater(AtomicLong referenceValue, long pointInTimeMillis) {
        return referenceValue.updateAndGet(existing -> Math.max(existing, pointInTimeMillis));
    }

    @SneakyThrows(value = { IOException.class })
    public static String packetsToCompressedTrafficStream(Stream<byte[]> byteArrStream) {
        var tsb = TrafficStream.newBuilder().setNumberOfThisLastChunk(1);
        var trafficStreamOfReads = byteArrStream.map(
            bArr -> ReadObservation.newBuilder().setData(ByteString.copyFrom(bArr)).build()
        )
            .map(r -> TrafficObservation.newBuilder().setRead(r))
            .collect(org.opensearch.migrations.Utils.foldLeft(tsb, (existing, newObs) -> tsb.addSubStream(newObs)))
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
