package org.opensearch.migrations.replay.datatypes;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.stream.Stream;

public class TransformedPackets implements AutoCloseable {

    ArrayList<ByteBuf> data = new ArrayList<>();

    public boolean add(ByteBuf nextRequestPacket) {
        return data.add(nextRequestPacket.retainedDuplicate());
    }

    public int size() { return data.size(); }

    public Stream<ByteBuf> stream() {
        return data.stream().map(bb->bb.duplicate());
    }

    public Stream<byte[]> asByteArrayStream() {
        return stream().map(bb -> {
            byte[] bArr = new byte[bb.readableBytes()];
            bb.readBytes(bArr);
            return bArr;
        });
    }

    @Override
    public void close() {
        data.stream().forEach(bb->bb.release());
        // Once we're closed, I'd rather see an NPE rather than refCnt errors from netty, which
        // could cause us to look in many places before finding out that it was just localize
        // to how callers were handling this object
        data.clear();
        data = null;
    }
}
