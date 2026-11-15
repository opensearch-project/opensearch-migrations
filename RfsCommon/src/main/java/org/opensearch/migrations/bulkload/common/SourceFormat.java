package org.opensearch.migrations.bulkload.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.yaml.snakeyaml.LoaderOptions;

/**
 * The XContent format of stored {@code _source} bytes.
 *
 * <p>Elasticsearch and OpenSearch store {@code _source} exactly as the client sent it, so a document
 * indexed with {@code Content-Type: application/smile} (as ES does for its own system indices, for
 * example {@code .geoip_databases}), {@code application/cbor}, or {@code application/yaml} stays in that
 * format in snapshots. Bulk requests require JSON, so these sources must be converted.
 *
 * <p>Detection mirrors Elasticsearch's {@code XContentFactory.xContentType(byte[])}, which the
 * source cluster itself used to parse every stored document, so any source the cluster accepted
 * is detectable here.
 */
public enum SourceFormat {
    JSON(null),
    SMILE(new SmileFactory()),
    CBOR(new CBORFactory()),
    YAML(YAMLFactory.builder().loaderOptions(unboundedYamlLoaderOptions()).build()),
    UNKNOWN(null);

    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private final JsonFactory factory;

    SourceFormat(JsonFactory factory) {
        this.factory = factory;
    }

    public static SourceFormat detect(byte[] bytes) {
        if (bytes == null || bytes.length < 2) {
            return UNKNOWN;
        }
        if (bytes[0] == '{') {
            return JSON;
        }
        if (startsWith(bytes, ':', ')', '\n')) {
            return SMILE;
        }
        if (startsWith(bytes, '-', '-', '-')) {
            return YAML;
        }
        if (isCbor(bytes)) {
            return CBOR;
        }
        return isJsonAfterBomOrWhitespace(bytes) ? JSON : UNKNOWN;
    }

    private static boolean startsWith(byte[] bytes, int b0, int b1, int b2) {
        return bytes.length > 2 && bytes[0] == (byte) b0 && bytes[1] == (byte) b1 && bytes[2] == (byte) b2;
    }

    /** CBOR self-describe tag 55799, or major type 5 (map), including the indefinite-length map 0xBF. */
    private static boolean isCbor(byte[] bytes) {
        return startsWith(bytes, 0xD9, 0xD9, 0xF7) || (bytes[0] & 0xE0) == 0xA0;
    }

    private static boolean isJsonAfterBomOrWhitespace(byte[] bytes) {
        for (int i = hasUtf8Bom(bytes) ? 3 : 0; i < bytes.length; i++) {
            if (bytes[i] == '{') {
                return true;
            }
            if (!Character.isWhitespace(bytes[i])) {
                return false;
            }
        }
        return false;
    }

    /**
     * Returns JSON for the source: the same array when it is already JSON starting with {@code '{'},
     * otherwise a converted copy.
     *
     * @throws InvalidDocumentSourceException if the format is unknown or the bytes cannot be decoded
     */
    public static byte[] toJson(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new InvalidDocumentSourceException("Document source is empty");
        }
        if (bytes[0] == '{') {
            return bytes; // Fast path: no detection loop, no copy
        }
        var format = detect(bytes);
        switch (format) {
            case JSON:
                // A UTF-8 byte order mark would end up in the middle of a bulk body line.
                return hasUtf8Bom(bytes) ? Arrays.copyOfRange(bytes, 3, bytes.length) : bytes;
            case UNKNOWN:
                throw new InvalidDocumentSourceException("Document source is not JSON, SMILE, CBOR, or YAML"
                    + describeHead(bytes));
            default:
                return format.convert(bytes);
        }
    }

    private byte[] convert(byte[] bytes) {
        try (JsonParser parser = factory.createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new InvalidDocumentSourceException(name() + " document source is not an object");
            }
            var out = new ByteArrayOutputStream(bytes.length + 64);
            try (var generator = JSON_FACTORY.createGenerator(out)) {
                // Streaming copy: binary values become base64 strings, as Elasticsearch renders them in JSON.
                generator.copyCurrentStructure(parser);
            }
            return out.toByteArray();
        } catch (InvalidDocumentSourceException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new InvalidDocumentSourceException("Unable to convert " + name() + " document source to JSON", e);
        }
    }

    /** A source is bounded by what the cluster accepted, not by SnakeYAML's 3 MB default. */
    private static LoaderOptions unboundedYamlLoaderOptions() {
        var options = new LoaderOptions();
        options.setCodePointLimit(Integer.MAX_VALUE);
        return options;
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length > 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF;
    }

    private static String describeHead(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return " (empty)";
        }
        var sb = new StringBuilder(" (length ").append(bytes.length).append(", leading bytes");
        for (int i = 0; i < Math.min(8, bytes.length); i++) {
            sb.append(String.format(" %02x", bytes[i]));
        }
        return sb.append(')').toString();
    }

    /** A document whose {@code _source} cannot be represented as JSON. Affects only that document. */
    public static class InvalidDocumentSourceException extends IllegalArgumentException {
        public InvalidDocumentSourceException(String message) {
            super(message);
        }

        public InvalidDocumentSourceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
