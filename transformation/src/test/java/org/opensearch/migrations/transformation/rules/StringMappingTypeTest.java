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
public class StringMappingTypeTest {
    @SneakyThrows
    @Test
    void testApplyTransformation() {
        var indexJson = (ObjectNode)new ObjectMapper().readTree(//
            "{\r\n" + //
            "  \"settings\" : {\r\n" + //
            "    \"index\" : {\r\n" + //
            "      \"creation_date\" : \"1733959046299\",\r\n" + //
            "      \"number_of_shards\" : \"5\",\r\n" + //
            "      \"number_of_replicas\" : \"0\",\r\n" + //
            "      \"uuid\" : \"7hZYO5c1Q8OCVAwuuH18Nw\",\r\n" + //
            "      \"version\" : {\r\n" + //
            "        \"created\" : \"5061699\"\r\n" + //
            "      },\r\n" + //
            "      \"provided_name\" : \"test_index\"\r\n" + //
            "    }\r\n" + //
            "  },\r\n" + //
            "  \"aliases\" : { },\r\n" + //
            "  \"mappings\" : {\r\n" + //
            "    \"properties\" : {\r\n" + //
            "      \"field1\" : {\r\n" + //
            "        \"type\" : \"string\"\r\n" + //
            "      },\r\n" + //
            "      \"field2\" : {\r\n" + //
            "        \"type\" : \"long\"\r\n" + //
            "      },\r\n" + //
            "      \"field3\" : {\r\n" + //
            "        \"type\" : \"float\"\r\n" + //
            "      }\r\n" + //
            "    }\r\n" + //
            "  }\r\n" + //
            "}");
        var copyJson = indexJson.deepCopy();

        // Action
        var transformation = new StringMappingType();
        var index = mock(Index.class);
        when(index.getRawJson()).thenReturn(copyJson);
        assertThat(transformation.canApply(index), equalTo(CanApplyResult.YES));

        // Verification
        log.atInfo().setMessage("Original\n{}").addArgument(() -> indexJson.toPrettyString()).log();
        var wasChanged = transformation.applyTransformation(index);
        log.atInfo().setMessage("After{}\n{}")
            .addArgument(wasChanged ? " *Changed* " : "")
            .addArgument(() -> copyJson.toPrettyString())
            .log();

        assertThat(wasChanged, equalTo(true));
        assertThat(copyJson.toPrettyString(), not(equalTo(indexJson.toPrettyString())));
        assertThat(copyJson.toPrettyString(), not(containsString("string")));
        assertThat(copyJson.toPrettyString(), containsString("text"));
        assertThat(copyJson.toPrettyString(), containsString("keyword"));
    
    }
}
