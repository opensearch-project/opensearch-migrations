package org.opensearch.migrations.replay;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
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

    public HttpMessageAndTimestamp(Instant firstPacketTimestamp) {
        this.firstPacketTimestamp = firstPacketTimestamp;
        this.packetBytes = new ArrayList<>();
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
                .flatMapToInt(bArr->IntStream.range(0,bArr.length).map(idx->bArr[idx]))
                .limit(MAX_BYTES_SHOWN_FOR_TO_STRING)
                .mapToObj(b->""+(char)b)
                .collect(Collectors.joining());
        final StringBuilder sb = new StringBuilder("HttpMessageAndTimestamp{");
        sb.append("firstPacketTimestamp=").append(firstPacketTimestamp);
        sb.append(", lastPacketTimestamp=").append(lastPacketTimestamp);
        sb.append(", packetBytes=[").append(packetBytesAsStr);
        if (packetBytesAsStr.length() >= MAX_BYTES_SHOWN_FOR_TO_STRING) {
            sb.append("...");
        }
        sb.append("]}");
        return sb.toString();
    }
}
