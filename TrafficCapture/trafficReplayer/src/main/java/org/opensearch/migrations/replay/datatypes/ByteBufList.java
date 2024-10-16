package org.opensearch.migrations.replay.datatypes;

import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;

public class ByteBufList extends AbstractReferenceCounted {

    ArrayList<ByteBuf> data = new ArrayList<>();

    public ByteBufList(ByteBuf ...items) {
        for (var i : items) {
            add(i);
        }
    }

    public boolean add(ByteBuf nextRequestPacket) {
        return data.add(nextRequestPacket.retainedDuplicate());
    }

    public boolean isClosed() {
        return data == null;
    }

    public boolean isEmpty() {
        return data != null && data.isEmpty();
    }

    public int size() {
        return data.size();
    }

    public Stream<ByteBuf> streamUnretained() {
        return data.stream().map(ByteBuf::duplicate);
    }

    public Stream<byte[]> asByteArrayStream() {
        return streamUnretained().map(bb -> {
            byte[] bArr = new byte[bb.readableBytes()];
            bb.readBytes(bArr);
            return bArr;
        });
    }

    public CompositeByteBuf asCompositeByteBufRetained() {
        return asCompositeByteBufRetained(data.stream());
    }

    public static CompositeByteBuf asCompositeByteBuf(Stream<ByteBuf> byteBufs) {
        var compositeByteBuf = Unpooled.compositeBuffer(1024);
        byteBufs.forEach(byteBuf -> compositeByteBuf.addComponent(true, byteBuf.duplicate()));
        return compositeByteBuf;
    }

    public static CompositeByteBuf asCompositeByteBufRetained(Stream<ByteBuf> byteBufs) {
        var compositeByteBuf = Unpooled.compositeBuffer(1024);
        byteBufs.forEach(byteBuf -> compositeByteBuf.addComponent(true, byteBuf.retainedDuplicate()));
        return compositeByteBuf;
    }

    @Override
    public void deallocate() {
        data.forEach(ReferenceCounted::release);
        // Once we're closed, I'd rather see an NPE rather than refCnt errors from netty, which
        // could cause us to look in many places before finding out that it was just localize
        // to how callers were handling this object
        data.clear();
        data = null;
    }

    @Override
    public ReferenceCounted touch(Object o) {
        return this;
    }

    @Override
    public String toString() {
        if (isClosed()) {
            return "CLOSED";
        }
        return new StringJoiner(", ", ByteBufList.class.getSimpleName() + "[", "]").add("data=" + data)
            .toString();
    }
}
