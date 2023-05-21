package org.opensearch.migrations.replay;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.stream.Stream;

public class HttpMessageAndTimestamp {
    private Instant firstPacketTimestamp;
    @Getter
    @Setter
    private Instant lastPacketTimestamp;

    public final ArrayList<byte[]> packetBytes;

    public HttpMessageAndTimestamp(Instant firstPacketTimestamp) {
        this.firstPacketTimestamp = firstPacketTimestamp;
        this.packetBytes = new ArrayList<>();
    }

    public Duration getTotalDuration() {
        return Duration.between(firstPacketTimestamp, lastPacketTimestamp);
    }

    public void add(byte[] b) {
        packetBytes.add(b);
    }

    public Stream<byte[]> stream() {
        return packetBytes.stream();
    }
}
