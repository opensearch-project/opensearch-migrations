package org.opensearch.migrations.replay;

import java.io.ByteArrayInputStream;
import java.io.SequenceInputStream;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ReplayUtils {
    public static SequenceInputStream byteArraysToInputStream(List<byte[]> data) {
        return byteArraysToInputStream(data.stream());
    }
    public static SequenceInputStream byteArraysToInputStream(Stream<byte[]> data) {
        return new SequenceInputStream(Collections.enumeration(
                data.map(b -> new ByteArrayInputStream(b)).collect(Collectors.toList())));
    }
}
