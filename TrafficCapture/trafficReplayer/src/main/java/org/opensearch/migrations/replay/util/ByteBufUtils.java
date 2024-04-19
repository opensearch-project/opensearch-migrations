package org.opensearch.migrations.replay.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Collection;

import java.util.stream.Stream;

public interface ByteBufUtils {
    static Stream<ByteBuf> createCloseableByteBufStream(Stream<byte[]> byteArrStream) {
        return RefSafeStreamUtils.refSafeMap(byteArrStream, Unpooled::wrappedBuffer);
    }

    static Stream<ByteBuf> createCloseableByteBufStream(Collection<byte[]> byteArrCollection) {
        return createCloseableByteBufStream(byteArrCollection.stream());
    }
}