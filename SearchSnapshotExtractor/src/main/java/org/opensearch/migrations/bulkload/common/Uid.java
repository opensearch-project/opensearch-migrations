package org.opensearch.migrations.bulkload.common;

import java.util.Arrays;
import java.util.Base64;

import shadow.lucene9.org.apache.lucene.util.BytesRef;

/**
 * This class is a cut-down copy of the org.elasticsearch.index.mapper.Uid class from the Elasticsearch project.
 * See: https://github.com/elastic/elasticsearch/blob/6.8/server/src/main/java/org/elasticsearch/index/mapper/Uid.java#L32
 */
public class Uid {
    private Uid() {}

    public static final int UTF8 = 0xff;
    public static final int NUMERIC = 0xfe;
    public static final int BASE64_ESCAPE = 0xfd;

    private static String decodeNumericId(byte[] idBytes, int offset, int len) {
        assert Byte.toUnsignedInt(idBytes[offset]) == NUMERIC;
        int length = (len - 1) * 2;
        char[] chars = new char[length];
        for (int i = 1; i < len; ++i) {
            final int b = Byte.toUnsignedInt(idBytes[offset + i]);
            final int b1 = (b >>> 4);
            final int b2 = b & 0x0f;
            chars[(i - 1) * 2] = (char) (b1 + '0');
            if (i == len - 1 && b2 == 0x0f) {
                length--;
                break;
            }
            chars[(i - 1) * 2 + 1] = (char) (b2 + '0');
        }
        return new String(chars, 0, length);
    }

    private static String decodeUtf8Id(byte[] idBytes, int offset, int length) {
        assert Byte.toUnsignedInt(idBytes[offset]) == UTF8;
        return new BytesRef(idBytes, offset + 1, length - 1).utf8ToString();
    }

    private static String decodeBase64Id(byte[] idBytes, int offset, int length) {
        assert Byte.toUnsignedInt(idBytes[offset]) <= BASE64_ESCAPE;
        if (Byte.toUnsignedInt(idBytes[offset]) == BASE64_ESCAPE) {
            idBytes = Arrays.copyOfRange(idBytes, offset + 1, offset + length);
        } else if (!(idBytes.length == length && offset == 0)) { // no need to copy if it's not a slice
            idBytes = Arrays.copyOfRange(idBytes, offset, offset + length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(idBytes);
    }

    /** Decode an indexed id back to its original form.
     *  @see <a href="https://github.com/elastic/elasticsearch/blob/6.8/server/src/main/java/org/elasticsearch/index/mapper/Uid.java#L232">org.elasticsearch.index.mapper.Uid#encodeId</a> */
    public static String decodeId(byte[] idBytes) {
        return decodeId(idBytes, 0, idBytes.length);
    }

    /** Decode an indexed id back to its original form.
     *  @see <a href="https://github.com/elastic/elasticsearch/blob/6.8/server/src/main/java/org/elasticsearch/index/mapper/Uid.java#L232">org.elasticsearch.index.mapper.Uid#encodeId</a> */
    public static String decodeId(byte[] idBytes, int offset, int length) {
        if (length == 0) {
            throw new IllegalArgumentException("Ids can't be empty");
        }
        final int magicChar = Byte.toUnsignedInt(idBytes[offset]);
        switch (magicChar) {
            case NUMERIC:
                return decodeNumericId(idBytes, offset, length);
            case UTF8:
                return decodeUtf8Id(idBytes, offset, length);
            default:
                return decodeBase64Id(idBytes, offset, length);
        }
    }
}
