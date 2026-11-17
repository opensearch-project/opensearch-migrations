package org.opensearch.migrations.bulkload.lucene;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.Uid;
import org.opensearch.migrations.bulkload.lucene.version_10.Field10;
import org.opensearch.migrations.bulkload.lucene.version_5.Field5;
import org.opensearch.migrations.bulkload.lucene.version_6.Field6;
import org.opensearch.migrations.bulkload.lucene.version_7.Field7;
import org.opensearch.migrations.bulkload.lucene.version_9.Field9;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LuceneFieldUidTest {
    @FunctionalInterface
    interface FieldFactory {
        LuceneField create(byte[] bytes, int offset, int length);
    }

    static Stream<Arguments> fieldFactories() {
        return Stream.of(
            Arguments.of("Lucene 5", (FieldFactory) (bytes, offset, length) ->
                new Field5(new shadow.lucene5.org.apache.lucene.document.StoredField("_id",
                    new shadow.lucene5.org.apache.lucene.util.BytesRef(bytes, offset, length)))),
            Arguments.of("Lucene 6", (FieldFactory) (bytes, offset, length) ->
                new Field6(new shadow.lucene6.org.apache.lucene.document.StoredField("_id",
                    new shadow.lucene6.org.apache.lucene.util.BytesRef(bytes, offset, length)))),
            Arguments.of("Lucene 7", (FieldFactory) (bytes, offset, length) ->
                new Field7(new shadow.lucene7.org.apache.lucene.document.StoredField("_id",
                    new shadow.lucene7.org.apache.lucene.util.BytesRef(bytes, offset, length)))),
            Arguments.of("Lucene 9", (FieldFactory) (bytes, offset, length) ->
                new Field9(new shadow.lucene9.org.apache.lucene.document.StoredField("_id",
                    new shadow.lucene9.org.apache.lucene.util.BytesRef(bytes, offset, length)))),
            Arguments.of("Lucene 10", (FieldFactory) (bytes, offset, length) ->
                new Field10(new shadow.lucene10.org.apache.lucene.document.StoredField("_id",
                    new shadow.lucene10.org.apache.lucene.util.BytesRef(bytes, offset, length))))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fieldFactories")
    void decodesOnlyTheUtf8IdSlice(String version, FieldFactory factory) {
        String id = "00000000-0000-4000-8000-000000000001";
        assertSlicedId(factory, utf8Id(id), id);
        assertSlicedId(factory, utf8Id("customer-\u00e9-\ud83d\ude80"), "customer-\u00e9-\ud83d\ude80");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fieldFactories")
    void decodesOnlyTheNumericIdSlice(String version, FieldFactory factory) {
        assertSlicedId(factory, new byte[] {(byte) Uid.NUMERIC, 0x12, 0x34}, "1234");
        assertSlicedId(factory, new byte[] {(byte) Uid.NUMERIC, 0x12, 0x3f}, "123");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fieldFactories")
    void decodesOnlyTheBase64IdSlice(String version, FieldFactory factory) {
        byte[] idBytes = {0x01, 0x23, 0x45, 0x67};
        assertSlicedId(factory, idBytes, Base64.getUrlEncoder().withoutPadding().encodeToString(idBytes));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fieldFactories")
    void decodesOnlyTheEscapedBase64IdSlice(String version, FieldFactory factory) {
        byte[] idBytes = {(byte) Uid.UTF8, 0x01, 0x23};
        byte[] encoded = {(byte) Uid.BASE64_ESCAPE, idBytes[0], idBytes[1], idBytes[2]};
        assertSlicedId(factory, encoded, Base64.getUrlEncoder().withoutPadding().encodeToString(idBytes));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fieldFactories")
    void preservesAnActualCommaSeparatedSourceId(String version, FieldFactory factory) {
        String id = IntStream.range(0, 18)
            .mapToObj(i -> String.format("00000000-0000-4000-8000-%012d", i))
            .collect(Collectors.joining(","));
        assertEquals(665, id.getBytes(StandardCharsets.UTF_8).length);
        byte[] encoded = utf8Id(id);
        assertEquals(id, factory.create(encoded, 0, encoded.length).asUid());
    }

    @Test
    void preservesLegacyStringIds() {
        String id = "customer,legacy";
        assertEquals(id, new Field6(
            new shadow.lucene6.org.apache.lucene.document.StoredField("_id", id)).asUid());
        assertEquals(id, new Field7(
            new shadow.lucene7.org.apache.lucene.document.StoredField("_id", id)).asUid());
    }

    private static void assertSlicedId(FieldFactory factory, byte[] encodedId, String expectedId) {
        for (int offset : new int[] {0, 7}) {
            byte[] backing = new byte[offset + encodedId.length + 700];
            Arrays.fill(backing, (byte) 'x');
            System.arraycopy(encodedId, 0, backing, offset, encodedId.length);
            byte[] original = backing.clone();
            assertEquals(expectedId, factory.create(backing, offset, encodedId.length).asUid(),
                "ID must exclude bytes outside the field slice at offset " + offset);
            assertArrayEquals(original, backing,
                "Decoding must not modify the shared backing array");
        }
    }

    private static byte[] utf8Id(String id) {
        byte[] text = id.getBytes(StandardCharsets.UTF_8);
        byte[] encoded = new byte[text.length + 1];
        encoded[0] = (byte) Uid.UTF8;
        System.arraycopy(text, 0, encoded, 1, text.length);
        return encoded;
    }
}
