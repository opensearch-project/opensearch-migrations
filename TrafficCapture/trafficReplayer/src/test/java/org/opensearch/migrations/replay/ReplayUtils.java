package org.opensearch.migrations.replay;

import java.io.ByteArrayInputStream;
import java.io.SequenceInputStream;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;

public class ReplayUtils {
    private ReplayUtils() {}

    public static SequenceInputStream byteArraysToInputStream(List<byte[]> data) {
        return byteArraysToInputStream(data.stream());
    }

    public static SequenceInputStream byteBufsToInputStream(Stream<ByteBuf> byteBufStream) {
        return byteArraysToInputStream(byteBufStream.map(bb -> {
            byte[] asBytes = new byte[bb.readableBytes()];
            bb.duplicate().readBytes(asBytes);
            return asBytes;
        }));
    }

    public static SequenceInputStream byteArraysToInputStream(Stream<byte[]> data) {
        return new SequenceInputStream(
            Collections.enumeration(data.map(ByteArrayInputStream::new).collect(Collectors.toList()))
        );
    }
}
