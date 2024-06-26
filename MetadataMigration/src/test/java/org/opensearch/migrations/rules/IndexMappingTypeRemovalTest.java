package org.opensearch.migrations.rules;

import static org.mockito.Mockito.mock;

import java.util.function.BiFunction;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.opensearch.migrations.transformation.CanApplyResult;
import org.opensearch.migrations.transformation.entity.Index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
@Slf4j
public class IndexMappingTypeRemovalTest {

    final ObjectMapper mapper = new ObjectMapper();

    private final String defaultMappingProperties = //
        "\"properties\": {\n" + //
        "  \"age\": {\n" + //
        "  \"type\": \"integer\"\n" + //
        "  }\n" + //
        "}\n";

    private final ObjectNode mappingWithoutAnyType = indexSettingJson( //
        "\"mappings\": {\n" + //
        defaultMappingProperties + //
        "},\n");

    private final ObjectNode mappingWithDocType = indexSettingJson( //
        "\"mappings\": {\n" + //
        "  \"_doc\": {\n" + //
        defaultMappingProperties + //
        "  }\n" + //
        "},\n");

    private final Function<String, ObjectNode> mappingWithCustomType = typeName -> indexSettingJson( //
        "\"mappings\": {\n" + //
        "  \"" + typeName + "\": {\n" + //
        defaultMappingProperties + //
        "  }\n" + //
        "},\n");

    private final BiFunction<String, String, ObjectNode> mappingWithMutlipleCustomTypes = (typeName1, typeName2) -> indexSettingJson( //
        "\"mappings\": {\n" + //
        "  \"" + typeName1 + "\": {\n" + //
        defaultMappingProperties + //
        "  },\n" + //
        "  \"" + typeName2 + "\": {\n" + //
        defaultMappingProperties + //
        "  }\n" + //
        "},\n");

    public ObjectNode indexSettingJson(final String mappingSection) {
        try {
            return (ObjectNode) mapper.readTree(
                "{\n" + //
                "  \"settings\": {\n" + //
                "    \"index\": {\n" + //
                "      \"number_of_shards\": 2,\n" + //
                "      \"number_of_replicas\": 1\n" + //
                "    }\n" + //
                "  },\n" + //
                mappingSection +
                "  \"aliases\": {\n" + //
                "    \"sample-alias1\": {}\n" + //
                "  }\n" + //
                "}");
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private CanApplyResult canApply(final ObjectNode indexJson) {
        var transformer = new IndexMappingTypeRemoval();
        var index = mock(Index.class);
        Mockito.when(index.raw()).thenReturn(indexJson);
        return transformer.canApply(index);
    }

    private boolean applyTransformation(final ObjectNode indexJson) {
        var transformer = new IndexMappingTypeRemoval();
        var index = mock(Index.class);
        Mockito.when(index.raw()).thenReturn(indexJson);

        log.atInfo().setMessage("Original\n{}").addArgument(indexJson.toPrettyString()).log();
        var wasChanged = transformer.applyTransformation(index);

        log.atInfo().setMessage("After{}\n{}").addArgument(wasChanged ? " *Changed* ": "").addArgument(indexJson.toPrettyString()).log();
        return wasChanged;
    }

    @Test
    void testApplyTransformation_noTypes() {
        // Setup 
        var originalJson = mappingWithoutAnyType;
        var indexJson = originalJson.deepCopy();

        // Action
        var wasChanged = applyTransformation(indexJson);

        // Verification
        assertThat(wasChanged, equalTo(false));
        assertThat(indexJson.toPrettyString(), equalTo(originalJson.toPrettyString()));
    }

    @Test
    void testApplyTransformation_DocType() {
        // Setup 
        var originalJson = mappingWithDocType;
        var indexJson = originalJson.deepCopy();

        // Action
        var wasChanged = applyTransformation(indexJson);

        // Verification
        assertThat(wasChanged, equalTo(true));
        assertThat(indexJson.toPrettyString(), not(equalTo(originalJson.toPrettyString())));
        assertThat(indexJson.toPrettyString(), not(containsString("_doc")));
    }

    @Test
    void testApplyTransformation_customTypes() {
        // Setup 
        var typeName = "foobar";
        var originalJson = mappingWithCustomType.apply(typeName);
        var indexJson = originalJson.deepCopy();

        // Action
        var wasChanged = applyTransformation(indexJson);

        // Verification
        assertThat(wasChanged, equalTo(true));
        assertThat(indexJson.toPrettyString(), not(equalTo(originalJson.toPrettyString())));
        assertThat(indexJson.toPrettyString(), not(containsString("_doc")));
    }

    @Test
    void testApplyTransformation_twoCustomTypes() {
        // Setup 
        var originalJson = mappingWithMutlipleCustomTypes.apply("t1", "t2");
        var indexJson = originalJson.deepCopy();

        // Action
        var wasChanged = applyTransformation(indexJson);
        assertThat(canApply(originalJson), equalTo(CanApplyResult.UNSUPPORTED));

        // Verification
        assertThat(wasChanged, equalTo(false));
        assertThat(originalJson.toPrettyString(), equalTo(indexJson.toPrettyString()));
    }
}
