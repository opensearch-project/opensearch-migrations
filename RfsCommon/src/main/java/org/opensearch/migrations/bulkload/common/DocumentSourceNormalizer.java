package org.opensearch.migrations.bulkload.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Getter;
import org.yaml.snakeyaml.LoaderOptions;

/**
 * Converts a stored document {@code _source} to JSON, the only body format RFS sends to a target.
 *
 * <p>JSON sources are returned as the same array without copying or parsing; validation remains
 * the responsibility of the writer. Smile, CBOR, and YAML sources are streamed token by token into
 * a JSON generator, which keeps numbers at their decoded precision (including big integers and
 * decimals) and writes binary values as base64, as Elasticsearch and OpenSearch do when they render
 * such a source as JSON.
 */
public final class DocumentSourceNormalizer {
    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final SmileFactory SMILE_FACTORY = new SmileFactory();
    private static final CBORFactory CBOR_FACTORY = new CBORFactory();
    private static final YAMLFactory YAML_FACTORY = YAMLFactory.builder()
        .loaderOptions(yamlLoaderOptions())
        .build();

    private DocumentSourceNormalizer() {}

    /** A source that cannot be converted to JSON. Only that document should fail. */
    @Getter
    public static class UndecodableSourceException extends IOException {
        private final DocumentSourceFormat format;

        public UndecodableSourceException(DocumentSourceFormat format, String message, Throwable cause) {
            super(message, cause);
            this.format = format;
        }
    }

    /**
     * @return {@code source} itself when it is JSON (or empty), otherwise a new JSON encoding of it
     * @throws UndecodableSourceException if the format is unknown or the bytes cannot be decoded
     */
    public static byte[] toJson(byte[] source) throws UndecodableSourceException {
        if (source == null || source.length == 0 || source[0] == '{') {
            return source;
        }
        var format = DocumentSourceFormat.detect(source);
        return switch (format) {
            case JSON -> source;
            case SMILE -> convert(SMILE_FACTORY, format, source);
            case CBOR -> convert(CBOR_FACTORY, format, source);
            case YAML -> convert(YAML_FACTORY, format, source);
            case UNKNOWN -> throw new UndecodableSourceException(format,
                "Document source is not JSON, SMILE, CBOR, or YAML (leading bytes: " + leadingHex(source) + ")",
                null);
        };
    }

    private static byte[] convert(JsonFactory sourceFactory, DocumentSourceFormat format, byte[] source)
        throws UndecodableSourceException {
        var out = new ByteArrayOutputStream(source.length + (source.length >> 1));
        try (JsonParser parser = sourceFactory.createParser(source);
             JsonGenerator generator = JSON_FACTORY.createGenerator(out)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new UndecodableSourceException(format,
                    "Document source in " + format + " format is not an object", null);
            }
            generator.copyCurrentStructure(parser);
        } catch (UndecodableSourceException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new UndecodableSourceException(format,
                "Failed to convert document source from " + format + " to JSON: " + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    private static String leadingHex(byte[] bytes) {
        var hex = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, 8); i++) {
            if (i > 0) {
                hex.append(' ');
            }
            hex.append(String.format("%02x", bytes[i]));
        }
        return hex.toString();
    }

    private static LoaderOptions yamlLoaderOptions() {
        var options = new LoaderOptions();
        // A source document is bounded by what the cluster accepted, not by SnakeYAML's 3MB default.
        options.setCodePointLimit(Integer.MAX_VALUE);
        return options;
    }
}
