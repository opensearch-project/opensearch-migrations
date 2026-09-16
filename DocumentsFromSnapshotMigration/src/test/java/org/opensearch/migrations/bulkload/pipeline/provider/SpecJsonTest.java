package org.opensearch.migrations.bulkload.pipeline.provider;

import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the absent/null/wrong-type handling the spec parsers rely on. A Jackson {@code NullNode}
 * must read as absent rather than stringifying to {@code "null"}.
 */
class SpecJsonTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Test
    void requiredString_returnsTheValueWhenPresent() {
        var config = NODES.objectNode().put("repoUri", "s3://bucket/path");

        assertThat(SpecJson.requiredString(config, "repoUri"), equalTo("s3://bucket/path"));
    }

    @Test
    void requiredString_rejectsMissingBlankAndExplicitNull() {
        var missing = NODES.objectNode();
        var blank = NODES.objectNode().put("repoUri", "   ");
        var explicitNull = NODES.objectNode();
        explicitNull.putNull("repoUri");

        for (var config : List.of(missing, blank, explicitNull)) {
            var thrown = assertThrows(IllegalArgumentException.class,
                () -> SpecJson.requiredString(config, "repoUri"));
            assertThat(thrown.getMessage(), containsString("repoUri"));
        }
    }

    @Test
    void optionalString_distinguishesPresentFromAbsentAndNullNode() {
        var present = NODES.objectNode().put("endpoint", "http://localhost:4566");
        var absent = NODES.objectNode();
        var explicitNull = NODES.objectNode();
        explicitNull.putNull("endpoint");

        assertThat(SpecJson.optionalString(present, "endpoint"), equalTo("http://localhost:4566"));
        assertThat(SpecJson.optionalString(absent, "endpoint"), nullValue());
        assertThat("a NullNode must not become the string \"null\"",
            SpecJson.optionalString(explicitNull, "endpoint"), nullValue());
    }

    @Test
    void booleanOr_fallsBackWhenAbsentOrNull() {
        var set = NODES.objectNode().put("flag", true);
        var absent = NODES.objectNode();
        var explicitNull = NODES.objectNode();
        explicitNull.putNull("flag");

        assertThat(SpecJson.booleanOr(set, "flag", false), is(true));
        assertThat(SpecJson.booleanOr(absent, "flag", true), is(true));
        assertThat(SpecJson.booleanOr(absent, "flag", false), is(false));
        assertThat(SpecJson.booleanOr(explicitNull, "flag", true), is(true));
    }

    @Test
    void longOr_fallsBackWhenAbsentOrNull() {
        var set = NODES.objectNode().put("maxShardSizeBytes", 4242L);
        var absent = NODES.objectNode();
        var explicitNull = NODES.objectNode();
        explicitNull.putNull("maxShardSizeBytes");

        assertThat(SpecJson.longOr(set, "maxShardSizeBytes", 0L), equalTo(4242L));
        assertThat(SpecJson.longOr(absent, "maxShardSizeBytes", 7L), equalTo(7L));
        assertThat(SpecJson.longOr(explicitNull, "maxShardSizeBytes", 7L), equalTo(7L));
    }

    @Test
    void stringList_readsArraysAndTreatsAnythingElseAsEmpty() {
        var withValues = NODES.objectNode();
        withValues.putArray("indexAllowlist").add("logs").add("metrics");
        var absent = NODES.objectNode();
        var explicitNull = NODES.objectNode();
        explicitNull.putNull("indexAllowlist");
        var notAnArray = NODES.objectNode().put("indexAllowlist", "logs");

        assertThat(SpecJson.stringList(withValues, "indexAllowlist"), contains("logs", "metrics"));
        assertThat(SpecJson.stringList(absent, "indexAllowlist"), empty());
        assertThat(SpecJson.stringList(explicitNull, "indexAllowlist"), empty());
        assertThat("a scalar is not a list", SpecJson.stringList(notAnArray, "indexAllowlist"), empty());
    }

    @Test
    void putIfPresent_writesOnlyNonNullValues() {
        var node = NODES.objectNode();

        SpecJson.putIfPresent(node, "present", "value");
        SpecJson.putIfPresent(node, "absent", null);

        assertThat(node.has("present"), is(true));
        assertThat(node.get("present").asText(), equalTo("value"));
        assertThat("a null value must leave the field absent, not null", node.has("absent"), is(false));
    }
}
