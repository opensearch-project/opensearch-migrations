package org.opensearch.migrations.replay.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Collection;

import java.util.stream.Stream;

public final class NettyUtils {
    public static Stream<ByteBuf> createCloseableByteBufStream(Stream<byte[]> byteArrStream) {
        return RefSafeStreamUtils.refSafeMap(byteArrStream, Unpooled::wrappedBuffer);
    }

    public static Stream<ByteBuf> createCloseableByteBufStream(Collection<byte[]> byteArrCollection) {
        return createCloseableByteBufStream(byteArrCollection.stream());
    }

    private NettyUtils() {}
}