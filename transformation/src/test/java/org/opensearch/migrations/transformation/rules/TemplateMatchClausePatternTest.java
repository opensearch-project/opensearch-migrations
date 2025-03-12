package org.opensearch.migrations.transformation.rules;

import org.opensearch.migrations.transformation.CanApplyResult;
import org.opensearch.migrations.transformation.entity.Index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Slf4j
public class TemplateMatchClausePatternTest {
 
    final ObjectMapper mapper = new ObjectMapper();

    private final String es5TemplateString = //
        "{\r\n" + //
        "  \"template\": \"te*\",\r\n" + //
        "  \"settings\": {\r\n" + //
        "    \"number_of_shards\": 1\r\n" + //
        "  },\r\n" + //
        "  \"mappings\": {\r\n" + //
        "    \"type1\": {\r\n" + //
        "      \"_source\": {\r\n" + //
        "        \"enabled\": false\r\n" + //
        "      },\r\n" + //
        "      \"properties\": {\r\n" + //
        "        \"host_name\": {\r\n" + //
        "          \"type\": \"keyword\"\r\n" + //
        "        },\r\n" + //
        "        \"created_at\": {\r\n" + //
        "          \"type\": \"date\",\r\n" + //
        "          \"format\": \"EEE MMM dd HH:mm:ss Z YYYY\"\r\n" + //
        "        }\r\n" + //
        "      }\r\n" + //
        "    }\r\n" + //
        "  }\r\n" + //
        "}";

    @Test
    @SneakyThrows
    void testCanApply_valid_input() {
        // Setup
        var originalJson = (ObjectNode) mapper.readTree(es5TemplateString);
        var copyJson = originalJson.deepCopy();

        // Action
        var transformation = new TemplateMatchClausePattern();
        var index = mock(Index.class);
        when(index.getRawJson()).thenReturn(copyJson);
        assertThat(transformation.canApply(index), equalTo(CanApplyResult.YES));

        // Verification
        log.atInfo().setMessage("Original\n{}").addArgument(() -> originalJson.toPrettyString()).log();
        var wasChanged = transformation.applyTransformation(index);
        log.atInfo().setMessage("After{}\n{}")
        .addArgument(wasChanged ? " *Changed* " : "")
        .addArgument(() -> copyJson.toPrettyString())
        .log();

        assertThat(wasChanged, equalTo(true));
        assertThat(copyJson.toPrettyString(), not(equalTo(originalJson.toPrettyString())));
        assertThat(copyJson.toPrettyString(), not(containsString("template")));
        assertThat(copyJson.toPrettyString(), containsString("[ \"te*\" ]"));
        assertThat(copyJson.toPrettyString(), containsString("index_patterns"));
    }

    @Test
    @SneakyThrows
    void testCanApply_invalid_input() {
        // Setup
        var originalJson = (ObjectNode) mapper.readTree("{}");
        var copyJson = originalJson.deepCopy();

        // Action
        var transformation = new TemplateMatchClausePattern();
        var index = mock(Index.class);
        when(index.getRawJson()).thenReturn(copyJson);
        assertThat(transformation.canApply(index), equalTo(CanApplyResult.NO));

        // Verification
        var wasChanged = transformation.applyTransformation(index);
        assertThat(wasChanged, equalTo(false));
        assertThat(copyJson.toPrettyString(), equalTo(originalJson.toPrettyString()));
    }
}
