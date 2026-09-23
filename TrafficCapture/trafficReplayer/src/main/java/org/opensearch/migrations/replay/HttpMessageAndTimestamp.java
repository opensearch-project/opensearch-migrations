package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G5) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: RawPackets . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G5)
/*

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.datatypes.RawPackets;
import org.opensearch.migrations.replay.util.NettyUtils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Lombok;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@EqualsAndHashCode(exclude = "currentSegmentBytes")
public class HttpMessageAndTimestamp {

    public static class Request extends HttpMessageAndTimestamp {
        public Request(Instant firstPacketTimestamp) {
            super(firstPacketTimestamp);
        }

        @Override
        public String toString() {
            return super.format(Optional.of(HttpByteBufFormatter.HttpMessageType.REQUEST));
        }
    }

    public static class Response extends HttpMessageAndTimestamp {
        public Response(Instant firstPacketTimestamp) {
            super(firstPacketTimestamp);
        }

        @Override
        public String toString() {
            return super.format(Optional.of(HttpByteBufFormatter.HttpMessageType.RESPONSE));
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

    public ByteBuf asByteBuf() {
        var compositeBuf = Unpooled.compositeBuffer();
        packetBytes.stream()
            .map(Unpooled::wrappedBuffer)
            .forEach(buffer -> compositeBuf.addComponent(true, buffer));
        return compositeBuf.asReadOnly();
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

    public String format(Optional<HttpByteBufFormatter.HttpMessageType> messageTypeOp) {
        try (var bufStream = NettyUtils.createRefCntNeutralCloseableByteBufStream(packetBytes)) {
            var packetBytesAsStr = messageTypeOp.map(
                mt -> HttpByteBufFormatter.httpPacketBytesToString(
                    mt,
                    packetBytes,
                    HttpByteBufFormatter.LF_LINE_DELIMITER
                )
            ).orElseGet(() -> HttpByteBufFormatter.httpPacketBufsToString(bufStream, Utils.MAX_PAYLOAD_BYTES_TO_PRINT));
            final StringBuilder sb = new StringBuilder("HttpMessageAndTimestamp{");
            sb.append("firstPacketTimestamp=").append(firstPacketTimestamp);
            sb.append(", lastPacketTimestamp=").append(lastPacketTimestamp);
            sb.append(", message=[").append(packetBytesAsStr);
            sb.append("]}");
            return sb.toString();
        }
    }

    public void addSegment(byte[] data) {
        if (currentSegmentBytes == null) {
            currentSegmentBytes = new ByteArrayOutputStream();
        }
        try {
            currentSegmentBytes.write(data);
        } catch (IOException e) {
            throw Lombok.sneakyThrow(e);
        }
    }

    public void finalizeRequestSegments(Instant timestamp) {
        packetBytes.add(currentSegmentBytes.toByteArray());
        this.lastPacketTimestamp = timestamp;
        currentSegmentBytes = null;
    }

    @Override
    public String toString() {
        return format(Optional.empty());
    }

}

*/
// REBUILD-LIMBO-END(G5)