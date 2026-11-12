package org.opensearch.migrations.bulkload.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures the cost of indexing and slicing many-key, deeply nested documents.
 *
 * <p>Build and run only this size matrix with:
 * {@code ./gradlew :RFS:jmhJar && java -jar RFS/build/libs/RFS-*-jmh.jar JsonObjectSplitterBenchmark -prof gc}.
 * The ordinary JUnit test uses smaller inputs so it remains suitable for the regular test suite.
 */
public class JsonObjectSplitterBenchmark {
    private static final int MIB = 1024 * 1024;

    @State(Scope.Thread)
    public static class SplitState {
        @Param({"10", "100", "190", "390"})
        public int documentMiB;

        @Param({"4096"})
        public int valueBytes;

        @Param({"512"})
        public int containerCount;

        @Param({"8"})
        public int nestingDepth;

        @Param({"100"})
        public int fragmentMiB;

        byte[] document;

        @Setup(Level.Trial)
        public void setUp() throws IOException {
            document = nestedDocument(documentMiB * MIB, valueBytes, containerCount, nestingDepth);
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Warmup(iterations = 1)
    @Measurement(iterations = 3)
    public int splitManyKeyNestedDocument(SplitState state, Blackhole blackhole) throws IOException {
        List<byte[]> fragments = JsonObjectSplitter.split(state.document, state.fragmentMiB * MIB);
        blackhole.consume(fragments);
        return fragments.size();
    }

    @Test
    void smallerNestedDocumentsRoundTripThroughDeepMerge() throws IOException {
        for (int sizeMiB : List.of(1, 4, 16)) {
            byte[] document = nestedDocument(sizeMiB * MIB, 1024, 64, 5);
            List<byte[]> fragments = JsonObjectSplitter.split(document, 3 * MIB);

            assertTrue(fragments.size() >= Math.ceil(sizeMiB / 3.0));
            assertTrue(fragments.stream().allMatch(fragment -> fragment.length <= 3 * MIB));
            assertEquals(ObjectMapperFactory.createDefaultMapper().readTree(document), merge(fragments));
        }
    }

    static byte[] nestedDocument(int targetBytes, int valueBytes, int containerCount, int nestingDepth)
        throws IOException {
        String value = "x".repeat(valueBytes);
        int leafCount = Math.max(1, targetBytes / (valueBytes + 25));
        var output = new ByteArrayOutputStream(targetBytes + MIB);
        JsonFactory factory = ObjectMapperFactory.createDefaultMapper().getFactory();
        try (JsonGenerator generator = factory.createGenerator(output)) {
            generator.writeStartObject();
            generator.writeObjectFieldStart("benchmark_payload");
            generator.writeNumberField("target_bytes", targetBytes);
            generator.writeObjectFieldStart("groups");
            int leaf = 0;
            for (int group = 0; group < containerCount && leaf < leafCount; ++group) {
                generator.writeObjectFieldStart("group_" + padded(group, 4));
                for (int depth = 0; depth < nestingDepth; ++depth) {
                    generator.writeObjectFieldStart("level_" + padded(depth, 2));
                }
                int groupEnd = (int) Math.ceil((group + 1.0) * leafCount / containerCount);
                while (leaf < groupEnd) {
                    generator.writeStringField("key_" + padded(leaf, 7), value);
                    ++leaf;
                }
                for (int depth = 0; depth < nestingDepth; ++depth) {
                    generator.writeEndObject();
                }
                generator.writeEndObject();
            }
            generator.writeEndObject();
            generator.writeEndObject();
            generator.writeEndObject();
        }
        return output.toByteArray();
    }

    private static String padded(int value, int width) {
        return String.format("%0" + width + "d", value);
    }

    private static JsonNode merge(List<byte[]> fragments) throws IOException {
        var mapper = ObjectMapperFactory.createDefaultMapper();
        ObjectNode merged = mapper.createObjectNode();
        for (byte[] fragment : fragments) {
            deepMerge(merged, (ObjectNode) mapper.readTree(fragment));
        }
        return merged;
    }

    private static void deepMerge(ObjectNode target, ObjectNode source) {
        source.fields().forEachRemaining(entry -> {
            JsonNode oldValue = target.get(entry.getKey());
            JsonNode newValue = entry.getValue();
            if (oldValue instanceof ObjectNode oldObject && newValue instanceof ObjectNode newObject) {
                deepMerge(oldObject, newObject);
            } else {
                target.set(entry.getKey(), newValue);
            }
        });
    }
}
