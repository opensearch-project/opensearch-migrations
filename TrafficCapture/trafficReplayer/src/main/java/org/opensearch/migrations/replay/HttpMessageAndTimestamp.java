package org.opensearch.migrations.replay;

import lombok.Getter;
import lombok.Setter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class HttpMessageAndTimestamp {
    public static final int MAX_BYTES_SHOWN_FOR_TO_STRING = 32;
    private Instant firstPacketTimestamp;
    @Getter
    @Setter
    private Instant lastPacketTimestamp;

    /**
     * TODO - handle out-of-order inserts by making this a radix map
     */
    public final ArrayList<byte[]> packetBytes;
    ByteArrayOutputStream currentSegmentBytes;

    public HttpMessageAndTimestamp(Instant firstPacketTimestamp) {
        this.firstPacketTimestamp = firstPacketTimestamp;
        this.packetBytes = new ArrayList<>();
    }

    public boolean hasInProgressSegment() {
        return currentSegmentBytes != null;
    }

    public void add(byte[] b) {
        packetBytes.add(b);
    }

    public Stream<byte[]> stream() {
        return packetBytes.stream();
    }

    @Override
    public String toString() {
        var packetBytesAsStr = packetBytes.stream()
                .map(bArr-> {
                    var str = IntStream.range(0, bArr.length).map(idx -> bArr[idx])
                            .limit(MAX_BYTES_SHOWN_FOR_TO_STRING)
                            .mapToObj(b -> "" + (char) b)
                            .collect(Collectors.joining());
                    return "[" + (bArr.length > MAX_BYTES_SHOWN_FOR_TO_STRING ? str + "..." : str) + "]";
                })
                .collect(Collectors.joining(","));
        final StringBuilder sb = new StringBuilder("HttpMessageAndTimestamp{");
        sb.append("firstPacketTimestamp=").append(firstPacketTimestamp);
        sb.append(", lastPacketTimestamp=").append(lastPacketTimestamp);
        sb.append(", packetBytes=[").append(packetBytesAsStr);
        sb.append("]}");
        return sb.toString();
    }

    public void addSegment(byte[] data) {
        if (currentSegmentBytes == null) {
            currentSegmentBytes = new ByteArrayOutputStream();
        }
        try {
            currentSegmentBytes.write(data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void finalizeRequestSegments(Instant timestamp) {
        packetBytes.add(currentSegmentBytes.toByteArray());
        this.lastPacketTimestamp = timestamp;
        currentSegmentBytes = null;
    }
}
