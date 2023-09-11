package org.opensearch.migrations.replay;

import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.RawPackets;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

@Slf4j
public class HttpMessageAndTimestamp {

    public static class Request extends HttpMessageAndTimestamp {
        public Request(Instant firstPacketTimestamp) {
            super(firstPacketTimestamp);
        }
        @Override
        public String toString() {
            return super.format(Optional.of(Utils.HttpMessageType.Request));
        }
    }

    public static class Response extends HttpMessageAndTimestamp {
        public Response(Instant firstPacketTimestamp) {
            super(firstPacketTimestamp);
        }
        @Override
        public String toString() {
            return super.format(Optional.of(Utils.HttpMessageType.Request));
        }
    }

    @Getter
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

    public String format(Optional<Utils.HttpMessageType> messageTypeOp) {
        var packetBytesAsStr = messageTypeOp.map(mt->Utils.httpPacketBytesToString(mt, packetBytes))
                .orElseGet(()->Utils.httpPacketBufsToString(packetBytes.stream().map(b-> Unpooled.wrappedBuffer(b)),
                        Utils.MAX_PAYLOAD_SIZE_TO_PRINT));
        final StringBuilder sb = new StringBuilder("HttpMessageAndTimestamp{");
        sb.append("firstPacketTimestamp=").append(firstPacketTimestamp);
        sb.append(", lastPacketTimestamp=").append(lastPacketTimestamp);
        sb.append(", message=[").append(packetBytesAsStr);
        sb.append("]}");
        return sb.toString();
    }

    @Override
    public String toString() {
        return format(Optional.empty());
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
