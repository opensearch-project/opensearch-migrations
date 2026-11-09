package org.opensearch.migrations.bulkload.common;

/**
 * Content formats that Elasticsearch and OpenSearch accept for a document body and store, unchanged,
 * as the document's {@code _source} (and {@code _recovery_source}). A client that indexes with
 * {@code Content-Type: application/smile}, {@code application/cbor}, or {@code application/yaml}
 * leaves those bytes in the snapshot; nothing in the stored field records which format was used,
 * so it must be inferred from the leading bytes.
 *
 * <p>Detection rules, checked in this order:
 * <ul>
 *   <li>{@link #JSON}: first byte {@code '{'}. This is checked first so the common case costs a
 *       single comparison.</li>
 *   <li>{@link #SMILE}: the Smile header {@code ':' ')' '\n'} (0x3A 0x29 0x0A), which the Smile
 *       specification makes the first three bytes of every document with a header.</li>
 *   <li>{@link #YAML}: the YAML directives-end marker {@code ---}. Elasticsearch and OpenSearch
 *       reject YAML bodies that do not start with it, so a stored YAML source always does.</li>
 *   <li>{@link #CBOR}: the self-describe tag 55799 (0xD9 0xD9 0xF7, RFC 8949 section 3.4.6), or an
 *       initial byte with major type 5, "map" (0xA0-0xBF, RFC 8949 section 3.1). A source document
 *       is always an object, and no JSON, Smile, or YAML document can begin with these bytes.</li>
 *   <li>{@link #JSON}: an optional UTF-8 byte order mark and JSON whitespace before {@code '{'}.</li>
 * </ul>
 * Anything else is {@link #UNKNOWN}.
 */
public enum DocumentSourceFormat {
    JSON,
    SMILE,
    CBOR,
    YAML,
    UNKNOWN;

    private static final int CBOR_MAJOR_TYPE_MASK = 0xE0;
    private static final int CBOR_MAJOR_TYPE_MAP = 0xA0;
    private static final int CBOR_TAG_TWO_BYTE_ARGUMENT = 0xD9;
    private static final int CBOR_SELF_DESCRIBE_LOW_BYTE = 0xF7;

    public static DocumentSourceFormat detect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return UNKNOWN;
        }
        if (bytes[0] == '{') {
            return JSON;
        }
        if (startsWith(bytes, (byte) ':', (byte) ')', (byte) '\n')) {
            return SMILE;
        }
        if (startsWith(bytes, (byte) '-', (byte) '-', (byte) '-')) {
            return YAML;
        }
        if (isCborMapStart(bytes)) {
            return CBOR;
        }
        return isJsonAfterPreamble(bytes) ? JSON : UNKNOWN;
    }

    private static boolean startsWith(byte[] bytes, byte b0, byte b1, byte b2) {
        return bytes.length >= 3 && bytes[0] == b0 && bytes[1] == b1 && bytes[2] == b2;
    }

    private static boolean isCborMapStart(byte[] bytes) {
        int first = Byte.toUnsignedInt(bytes[0]);
        if ((first & CBOR_MAJOR_TYPE_MASK) == CBOR_MAJOR_TYPE_MAP) {
            return true;
        }
        return bytes.length >= 3
            && first == CBOR_TAG_TWO_BYTE_ARGUMENT
            && Byte.toUnsignedInt(bytes[1]) == CBOR_TAG_TWO_BYTE_ARGUMENT
            && Byte.toUnsignedInt(bytes[2]) == CBOR_SELF_DESCRIBE_LOW_BYTE;
    }

    private static boolean isJsonAfterPreamble(byte[] bytes) {
        int i = 0;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            i = 3;
        }
        for (; i < bytes.length; i++) {
            byte b = bytes[i];
            if (b == '{') {
                return true;
            }
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r') {
                return false;
            }
        }
        return false;
    }
}
