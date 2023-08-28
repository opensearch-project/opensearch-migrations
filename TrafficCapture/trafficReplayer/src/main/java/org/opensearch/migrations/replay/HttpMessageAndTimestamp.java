package org.opensearch.migrations.replay;

import lombok.Getter;
import lombok.Setter;
import org.opensearch.migrations.replay.datatypes.RawPackets;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.stream.Stream;

public class HttpMessageAndTimestamp {
    private Instant firstPacketTimestamp;
    @Getter
    @Setter
    private Instant lastPacketTimestamp;

    public final RawPackets packetBytes;
    ByteArrayOutputStream currentSegmentBytes;

    public HttpMessageAndTimestamp(Instant firstPacketTimestamp) {
        this.firstPacketTimestamp = firstPacketTimestamp;
        this.packetBytes = new RawPackets();
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
        var packetBytesAsStr = Utils.httpPacketsToString(packetBytes);
        final StringBuilder sb = new StringBuilder("HttpMessageAndTimestamp{");
        sb.append("firstPacketTimestamp=").append(firstPacketTimestamp);
        sb.append(", lastPacketTimestamp=").append(lastPacketTimestamp);
        sb.append(", message=[").append(packetBytesAsStr);
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
