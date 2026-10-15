package org.opensearch.migrations.bulkload.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

class SourceInternalIndexSettingsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void strip_nestedSettings() {
        var settings = parse("{\"creation_date\":\"1\",\"uuid\":\"u\",\"provided_name\":\"p\",\"number_of_shards\":\"1\","
            + "\"version\":{\"created\":\"6082399\",\"upgraded\":\"6082399\"},\"history\":{\"uuid\":\"h\"},"
            + "\"resize\":{\"source\":{\"name\":\"n\",\"uuid\":\"u\"}},\"verified_before_close\":\"true\"}");

        var removed = SourceInternalIndexSettings.strip(settings);

        assertThat(settings.toString(), equalTo("{\"number_of_shards\":\"1\"}"));
        assertThat(removed, containsInAnyOrder("creation_date", "uuid", "provided_name", "version.created", "version.upgraded",
            "history.uuid", "resize.source.name", "resize.source.uuid", "verified_before_close"));
    }

    @Test
    void strip_flatKeysWithIndexPrefix() {
        var settings = parse("{\"index.creation_date\":\"1\",\"index.version.created\":\"6082399\",\"index.number_of_shards\":\"1\"}");

        var removed = SourceInternalIndexSettings.strip(settings);

        assertThat(settings.toString(), equalTo("{\"index.number_of_shards\":\"1\"}"));
        assertThat(removed, containsInAnyOrder("index.creation_date", "index.version.created"));
    }

    @Test
    void strip_underIndexObject() {
        var settings = parse("{\"index\":{\"uuid\":\"u\",\"version\":{\"created\":\"6082399\"},\"refresh_interval\":\"1s\"}}");

        var removed = SourceInternalIndexSettings.strip(settings);

        assertThat(settings.toString(), equalTo("{\"index\":{\"refresh_interval\":\"1s\"}}"));
        assertThat(removed, containsInAnyOrder("index.uuid", "index.version.created"));
    }

    @Test
    void strip_keepsUserSettingsAndUntouchedEmptyObjects() {
        var json = "{\"mapping\":{\"total_fields\":{\"limit\":\"2000\"}},\"routing\":{\"allocation\":{\"total_shards_per_node\":\"2\"}},"
            + "\"analysis\":{},\"number_of_replicas\":\"1\"}";
        var settings = parse(json);

        var removed = SourceInternalIndexSettings.strip(settings);

        assertThat(removed, empty());
        assertThat(settings, equalTo(parse(json)));
    }

    @SneakyThrows
    private static ObjectNode parse(String json) {
        return (ObjectNode) MAPPER.readTree(json);
    }
}
