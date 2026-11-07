package org.opensearch.migrations.bulkload.pipeline.adapter;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OpenSearchIndexCreatorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void createIndex_removesInternalSettings() {
        var client = mock(OpenSearchClient.class);
        var settings = parse("{\"creation_date\":\"1\",\"uuid\":\"u\",\"provided_name\":\"p\",\"number_of_shards\":\"1\","
            + "\"version\":{\"created\":\"6080099\"},\"blocks\":{\"write\":\"true\"}}");
        var metadata = new IndexMetadataSnapshot("idx", 1, 0, null, settings, null);

        OpenSearchIndexCreator.createIndex(client, metadata, MAPPER, null);

        var body = ArgumentCaptor.forClass(ObjectNode.class);
        verify(client).createIndex(eq("idx"), body.capture(), isNull());
        assertThat(body.getValue().get("settings").toString(), equalTo("{\"number_of_shards\":\"1\"}"));
    }

    @SneakyThrows
    private static ObjectNode parse(String json) {
        return (ObjectNode) MAPPER.readTree(json);
    }
}
