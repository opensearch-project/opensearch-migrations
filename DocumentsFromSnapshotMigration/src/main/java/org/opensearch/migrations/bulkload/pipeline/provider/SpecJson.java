package org.opensearch.migrations.bulkload.pipeline.provider;

import java.io.IOException;

import org.opensearch.migrations.Version;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.deser.std.FromStringDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.databind.type.LogicalType;

/**
 * Binds provider configs to their typed specs. Binding is strict because a misspelled or mistyped
 * filter would otherwise read as absent and widen what gets migrated.
 */
final class SpecJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .withCoercionConfig(LogicalType.Textual, textual -> textual
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
        .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
        .addModule(new SimpleModule()
            .addSerializer(Version.class, new ToStringSerializer())
            .addDeserializer(Version.class, new VersionDeserializer()))
        .build();

    private SpecJson() {}

    static <S> S bind(JsonNode config, Class<S> specType) {
        if (config == null || !config.isObject()) {
            throw new IllegalArgumentException("Document source config must be a JSON object");
        }
        try {
            return MAPPER.treeToValue(config, specType);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Document source config is invalid: " + e.getMessage(), e);
        }
    }

    static ObjectNode toJson(Object spec) {
        return MAPPER.valueToTree(spec);
    }

    static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Document source config is missing required field '" + field + "'");
        }
        return value;
    }

    private static final class VersionDeserializer extends FromStringDeserializer<Version> {
        VersionDeserializer() {
            super(Version.class);
        }

        @Override
        protected Version _deserialize(String value, DeserializationContext context) throws IOException {
            return Version.fromString(value);
        }
    }
}
