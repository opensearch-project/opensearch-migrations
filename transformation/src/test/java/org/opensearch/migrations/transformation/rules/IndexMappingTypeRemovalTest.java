package org.opensearch.migrations.transformation.rules;

import java.util.function.BiFunction;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.transformation.CanApplyResult;
import org.opensearch.migrations.transformation.CanApplyResult.Unsupported;
import org.opensearch.migrations.transformation.entity.Index;

import lombok.extern.slf4j.Slf4j;
import org.mockito.Mockito;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
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
        var transformer = new IndexMappingTypeRemoval();
        var index = mock(Index.class);
        Mockito.when(index.rawJson()).thenReturn(indexJson);
        return transformer.canApply(index);
    }

    private boolean applyTransformation(final ObjectNode indexJson) {
        var transformer = new IndexMappingTypeRemoval();
        var index = mock(Index.class);
        Mockito.when(index.rawJson()).thenReturn(indexJson);

        log.atInfo().setMessage("Original\n{}").addArgument(indexJson.toPrettyString()).log();
        var wasChanged = transformer.applyTransformation(index);

        log.atInfo()
            .setMessage("After{}\n{}")
            .addArgument(wasChanged ? " *Changed* " : "")
            .addArgument(indexJson.toPrettyString())
            .log();
        return wasChanged;
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
    void testApplyTransformation_mappingIsObjectNotArray() {
        // Setup
        var typeName = "foobar";
        var originalJson = mappingObjectWithCustomType.apply(typeName);
        var indexJson = originalJson.deepCopy();

        // Action
        var wasChanged = applyTransformation(indexJson);
        assertThat(canApply(originalJson), equalTo(CanApplyResult.NO));

        // Verification
        assertThat(wasChanged, equalTo(false));
        assertThat(indexJson.toPrettyString(), equalTo(originalJson.toPrettyString()));
        assertThat(indexJson.toPrettyString(), containsString(typeName));
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

    @Test
    void testApplyTransformation_twoCustomTypes() {
        // Setup
        var originalJson = mappingWithMutlipleTypes.apply("t1", "t2");
        var indexJson = originalJson.deepCopy();

        // Action
        var wasChanged = applyTransformation(indexJson);
        var canApply = canApply(originalJson);
        assertThat(canApply, instanceOf(Unsupported.class));
        assertThat(((Unsupported) canApply).getReason(), equalTo("Multiple mapping types are not supported"));

        // Verification
        assertThat(wasChanged, equalTo(false));
        assertThat(originalJson.toPrettyString(), equalTo(indexJson.toPrettyString()));
    }

    @Test
    void testApplyTransformation_twoMappingEntries() {
        // Setup
        var originalJson = mutlipleMappingsWithSingleTypes.apply("t1", "t2");
        var indexJson = originalJson.deepCopy();

        // Action
        var wasChanged = applyTransformation(indexJson);
        var canApply = canApply(originalJson);
        assertThat(canApply, instanceOf(Unsupported.class));
        assertThat(((Unsupported) canApply).getReason(), equalTo("Multiple mapping types are not supported"));

        // Verification
        assertThat(wasChanged, equalTo(false));
        assertThat(originalJson.toPrettyString(), equalTo(indexJson.toPrettyString()));
    }
}
