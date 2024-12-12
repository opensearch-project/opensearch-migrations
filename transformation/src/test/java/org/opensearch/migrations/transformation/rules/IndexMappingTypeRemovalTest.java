package org.opensearch.migrations.transformation.rules;

import java.util.function.BiFunction;
import java.util.function.Function;

import org.opensearch.migrations.transformation.CanApplyResult;
import org.opensearch.migrations.transformation.CanApplyResult.Unsupported;
import org.opensearch.migrations.transformation.entity.Index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

@Slf4j
public class IndexMappingTypeRemovalTest {

    final ObjectMapper mapper = new ObjectMapper();

    private final String defaultMappingProperties = //
        "\"properties\": {\n" + //
            "  \"age\": {\n" + //
            "  \"type\": \"integer\"\n" + //
            "  }\n" + //
            "}\n";

    private final Function<String, ObjectNode> mappingObjectWithCustomType = typeName -> indexSettingJson(
        //
        "\"mappings\": {\n" + //
            "  \"" + typeName + "\": {\n" + //
            defaultMappingProperties + //
            "  }\n" + //
            "},\n"
    );

    private final Function<String, ObjectNode> mappingWithType = typeName -> indexSettingJson(
        //
        "\"mappings\": [{\n" + //
            "  \"" + typeName + "\": {\n" + //
            defaultMappingProperties + //
            "  }\n" + //
            "}],\n"
    );

    private final BiFunction<String, String, ObjectNode> mappingWithMutlipleTypes = (
        typeName1,
        typeName2) -> indexSettingJson(
            //
            "\"mappings\": [{\n" + //
                "  \"" + typeName1 + "\": {\n" + //
                defaultMappingProperties + //
                "  },\n" + //
                "  \"" + typeName2 + "\": {\n" + //
                defaultMappingProperties + //
                "  }\n" + //
                "}],\n"
    );

    private final BiFunction<String, String, ObjectNode> mutlipleMappingsWithSingleTypes = (
        typeName1,
        typeName2) -> indexSettingJson(
            //
            "\"mappings\": [{\n" + //
                "  \"" + typeName1 + "\": {\n" + //
                defaultMappingProperties + //
                "  }\n" + //
                "},\n" + //
                "{\n" + //
                "  \"" + typeName2 + "\": {\n" + //
                defaultMappingProperties + //
                "  }\n" + //
                "}],\n"
    );

    private final BiFunction<String, String, ObjectNode> conflictingMappingWithMultipleTypes = (typeName1, typeName2) -> indexSettingJson(
        "\"mappings\": [{\n" +
            "  \"" + typeName1 + "\": {\n" +
            "    \"properties\": {\n" +
            "      \"age\": { \"type\": \"integer\" }\n" +
            "    }\n" +
            "  }},{\n" +
            "  \"" + typeName2 + "\": {\n" +
            "    \"properties\": {\n" +
            "      \"age\": { \"type\": \"text\" }\n" +
            "    }\n" +
            "  }\n" +
            "}],\n"
    );


    public ObjectNode indexSettingJson(final String mappingSection) {
        try {
            return (ObjectNode) mapper.readTree("{\n" + //
                "  \"settings\": {\n" + //
                "    \"index\": {\n" + //
                "      \"number_of_shards\": 2,\n" + //
                "      \"number_of_replicas\": 1\n" + //
                "    }\n" + //
                "  },\n" + //
                mappingSection + "  \"aliases\": {\n" + //
                "    \"sample-alias1\": {}\n" + //
                "  }\n" + //
                "}");
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private CanApplyResult canApply(final ObjectNode indexJson) {
        return canApply(IndexMappingTypeRemoval.MultiTypeResolutionBehavior.NONE, indexJson);
    }

    private CanApplyResult canApply(final IndexMappingTypeRemoval.MultiTypeResolutionBehavior behavior, final ObjectNode indexJson) {
        var transformer = new IndexMappingTypeRemoval(behavior);
        var index = mock(Index.class);
        Mockito.when(index.getRawJson()).thenReturn(indexJson);
        return transformer.canApply(index);
    }
    private boolean applyTransformation(final ObjectNode indexJson) {
        return applyTransformation(IndexMappingTypeRemoval.MultiTypeResolutionBehavior.NONE, indexJson);
    }

    private boolean applyTransformation(final IndexMappingTypeRemoval.MultiTypeResolutionBehavior behavior, final ObjectNode indexJson) {
        var transformer = new IndexMappingTypeRemoval(behavior);
        var index = mock(Index.class);
        Mockito.when(index.getRawJson()).thenReturn(indexJson);

        log.atInfo().setMessage("Original\n{}").addArgument(() -> indexJson.toPrettyString()).log();
        var wasChanged = transformer.applyTransformation(index);

        log.atInfo().setMessage("After{}\n{}")
            .addArgument(wasChanged ? " *Changed* " : "")
            .addArgument(() -> indexJson.toPrettyString())
            .log();
        return wasChanged;
    }

    @Test
    void testApplyTransformation_emptyMappingArray() {
        // Setup
        var originalJson = indexSettingJson("\"mappings\": [],");
        var indexJson = originalJson.deepCopy();

        // Action
        var wasChanged = applyTransformation(indexJson);
        assertThat(canApply(originalJson), equalTo(CanApplyResult.NO));

        // Verification
        assertThat(wasChanged, equalTo(false));
        assertThat(indexJson.toPrettyString(), equalTo(originalJson.toPrettyString()));
    }

    @Test
    void testApplyTransformation_noMappingNode() {
        // Setup
        var originalJson = indexSettingJson("");
        var indexJson = originalJson.deepCopy();

        // Action
        var wasChanged = applyTransformation(indexJson);
        assertThat(canApply(originalJson), equalTo(CanApplyResult.NO));

        // Verification
        assertThat(wasChanged, equalTo(false));
        assertThat(indexJson.toPrettyString(), equalTo(originalJson.toPrettyString()));
    }

    @Test
    void testApplyTransformation_mappingNestedObject() {
        // Setup
        var typeName = "foobar";
        var originalJson = mappingObjectWithCustomType.apply(typeName);
        var indexJson = originalJson.deepCopy();

        // Action
        var wasChanged = applyTransformation(indexJson);
        assertThat(canApply(originalJson), equalTo(CanApplyResult.YES));

        // Verification
        assertThat(wasChanged, equalTo(true));
        assertThat(indexJson.toPrettyString(), not(equalTo(originalJson.toPrettyString())));
        assertThat(indexJson.toPrettyString(), not(containsString(typeName)));
    }

    @Test
    void testApplyTransformation_docType() {
        // Setup
        var typeName = "_doc";
        var originalJson = mappingWithType.apply(typeName);
        var indexJson = originalJson.deepCopy();

        // Action
        var wasChanged = applyTransformation(indexJson);
        assertThat(canApply(originalJson), equalTo(CanApplyResult.YES));

        // Verification
        assertThat(wasChanged, equalTo(true));
        assertThat(indexJson.toPrettyString(), not(equalTo(originalJson.toPrettyString())));
        assertThat(indexJson.toPrettyString(), not(containsString(typeName)));
    }

    @Test
    void testApplyTransformation_customTypes() {
        // Setup
        var typeName = "foobar";
        var originalJson = mappingWithType.apply(typeName);
        var indexJson = originalJson.deepCopy();

        // Action
        var wasChanged = applyTransformation(indexJson);
        assertThat(canApply(originalJson), equalTo(CanApplyResult.YES));

        // Verification
        assertThat(wasChanged, equalTo(true));
        assertThat(indexJson.toPrettyString(), not(equalTo(originalJson.toPrettyString())));
        assertThat(indexJson.toPrettyString(), not(containsString(typeName)));
    }

    @ParameterizedTest
    @CsvSource({
        "SPLIT, 'Split on multiple mapping types is not supported'",
        "NONE, 'No multi type resolution behavior declared, specify --multi-type-behavior to process'"
    })
    void testApplyTransformation_twoCustomTypes(String resolutionBehavior, String expectedReason) {
        // Setup
        var originalJson = mappingWithMutlipleTypes.apply("t1", "t2");
        var indexJson = originalJson.deepCopy();

        var behavior = IndexMappingTypeRemoval.MultiTypeResolutionBehavior.valueOf(resolutionBehavior);

        // Action
        var wasChanged = applyTransformation(behavior, indexJson);
        var canApply = canApply(behavior, originalJson);
        assertThat(canApply, instanceOf(Unsupported.class));
        assertThat(((Unsupported) canApply).getReason(), equalTo(expectedReason));

        // Verification
        assertThat(wasChanged, equalTo(false));
        assertThat(originalJson.toPrettyString(), equalTo(indexJson.toPrettyString()));
    }


    @ParameterizedTest
    @CsvSource({
        "SPLIT, 'Split on multiple mapping types is not supported'",
        "NONE, 'No multi type resolution behavior declared, specify --multi-type-behavior to process'"
    })
    void testApplyTransformation_twoMappingEntries(String resolutionBehavior, String expectedReason) {
        // Setup
        var originalJson = mutlipleMappingsWithSingleTypes.apply("t1", "t2");
        var indexJson = originalJson.deepCopy();
        var behavior = IndexMappingTypeRemoval.MultiTypeResolutionBehavior.valueOf(resolutionBehavior);

        // Action
        var wasChanged = applyTransformation(behavior, indexJson);
        var canApply = canApply(behavior, originalJson);
        assertThat(canApply, instanceOf(Unsupported.class));
        assertThat(((Unsupported) canApply).getReason(), equalTo(expectedReason));

        // Verification
        assertThat(wasChanged, equalTo(false));
        assertThat(originalJson.toPrettyString(), equalTo(indexJson.toPrettyString()));
    }

    // Helper method to create Index with specified raw JSON
    private Index createMockIndex(ObjectNode indexJson) {
        var index = mock(Index.class);
        Mockito.when(index.getRawJson()).thenReturn(indexJson);
        return index;
    }

    // Helper method to apply transformation with a specified transformer
    private boolean applyTransformation(final ObjectNode indexJson, IndexMappingTypeRemoval transformer) {
        var index = createMockIndex(indexJson);
        log.atInfo().setMessage("Original\n{}").addArgument(() -> indexJson.toPrettyString()).log();
        var wasChanged = transformer.applyTransformation(index);
        log.atInfo().setMessage("After{}\n{}")
            .addArgument(wasChanged ? " *Changed* " : "")
            .addArgument(() -> indexJson.toPrettyString())
            .log();
        return wasChanged;
    }

    @Test
    void testApplyTransformation_multiTypeUnion_noConflicts() {
        // Setup
        var originalJson = mappingWithMutlipleTypes.apply("type1", "type2");
        var indexJson = originalJson.deepCopy();
        var transformer = new IndexMappingTypeRemoval(IndexMappingTypeRemoval.MultiTypeResolutionBehavior.UNION);

        // Action
        var wasChanged = applyTransformation(indexJson, transformer);
        var canApply = transformer.canApply(createMockIndex(originalJson));

        // Verification
        assertThat(canApply, equalTo(CanApplyResult.YES));
        assertThat(wasChanged, equalTo(true));

        // Check that the "mappings" node has "properties" with merged fields from both types
        var propertiesNode = indexJson.get("mappings").get("properties");
        assertThat(propertiesNode, notNullValue());
        // Assuming both types have "age" property from defaultMappingProperties
        assertThat(propertiesNode.has("age"), equalTo(true));
    }

    @Test
    void testApplyTransformation_multiTypeUnion_withConflicts() {
        // Setup
        var originalJson = conflictingMappingWithMultipleTypes.apply("type1", "type2");
        var indexJson = originalJson.deepCopy();
        var transformer = new IndexMappingTypeRemoval(IndexMappingTypeRemoval.MultiTypeResolutionBehavior.UNION);

        // Action & Verification
        var exception = assertThrows(IllegalArgumentException.class, () -> applyTransformation(indexJson, transformer));
        assertThat(exception.getMessage(), containsString("Conflicting definitions for property during union age"));
    }
}
