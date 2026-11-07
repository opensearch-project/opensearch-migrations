package org.opensearch.migrations.bulkload.pipeline.provider;

import java.util.List;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DeltaMode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pins the strict binding every provider spec relies on. */
class SpecJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REQUIRED = "\"repoUri\":\"file:///s\",\"snapshotName\":\"nightly\"";

    private static EsSnapshotSourceSpec bindEs(String fields) throws Exception {
        return SpecJson.bind(JSON.readTree("{" + REQUIRED + fields + "}"), EsSnapshotSourceSpec.class);
    }

    @Test
    void absentAndExplicitNullFieldsTakeTheirDefaults() throws Exception {
        var absent = bindEs("");
        var explicitNull = bindEs(",\"version\":null,\"endpoint\":null,\"indexAllowlist\":null,"
            + "\"maxShardSizeBytes\":null,\"emitDocType\":null,\"deltaMode\":null");

        assertThat(explicitNull, equalTo(absent));
        assertThat(absent.version(), nullValue());
        assertThat("a null must not become the string \"null\"", explicitNull.endpoint(), nullValue());
        assertThat(absent.indexAllowlist(), empty());
        assertThat(absent.maxShardSizeBytes(), equalTo(0L));
        assertThat(absent.emitDocType(), equalTo(EmitDocTypeMode.AUTO));
    }

    @Test
    void bindsTypedFields() throws Exception {
        var spec = bindEs(",\"version\":\"ES 7.10.2\",\"deltaMode\":\"UPDATES_ONLY\",\"emitDocType\":\"ON\","
            + "\"maxShardSizeBytes\":4242,\"indexAllowlist\":[\"logs\"]");

        assertThat(spec.version(), equalTo(Version.fromString("ES 7.10.2")));
        assertThat(spec.deltaMode(), equalTo(DeltaMode.UPDATES_ONLY));
        assertThat(spec.emitDocType(), equalTo(EmitDocTypeMode.ON));
        assertThat(spec.maxShardSizeBytes(), equalTo(4242L));
        assertThat(spec.indexAllowlist(), equalTo(List.of("logs")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        ",\"indexAllowList\":[\"logs\"]",
        ",\"indexAllowlist\":\"logs\"",
        ",\"indexAllowlist\":[1]",
        ",\"emitDocType\":\"true\"",
        ",\"emitDocType\":1",
        ",\"maxShardSizeBytes\":\"4242\"",
        ",\"maxShardSizeBytes\":1.5",
        ",\"endpoint\":1",
        ",\"endpoint\":true",
        ",\"deltaMode\":\"NOT_A_MODE\"",
        ",\"deltaMode\":0",
        ",\"version\":\"not a version\"",
        ",\"version\":7"
    })
    void rejectsUnknownFieldsAndWrongTypes(String fields) {
        var thrown = assertThrows(IllegalArgumentException.class, () -> bindEs(fields));

        assertThat(thrown.getMessage(), containsString("Document source config"));
    }

    @Test
    void anUnknownFieldIsNamedInTheError() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> bindEs(",\"indexAllowList\":[]"));

        assertThat(thrown.getMessage(), containsString("indexAllowList"));
    }

    @Test
    void rejectsBlankAndNullRequiredFields() throws Exception {
        for (var config : List.of("{\"repoUri\":\"  \",\"snapshotName\":\"n\"}",
                "{\"repoUri\":null,\"snapshotName\":\"n\"}", "{\"snapshotName\":\"n\"}")) {
            JsonNode node = JSON.readTree(config);
            var thrown = assertThrows(IllegalArgumentException.class,
                () -> SpecJson.bind(node, EsSnapshotSourceSpec.class));
            assertThat(thrown.getMessage(), containsString("repoUri"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"text\"", "1", "null"})
    void rejectsAConfigThatIsNotAnObject(String config) throws Exception {
        var node = JSON.readTree(config);

        assertThrows(IllegalArgumentException.class, () -> SpecJson.bind(node, EsSnapshotSourceSpec.class));
    }

    @Test
    void rejectsAMissingConfig() {
        assertThrows(IllegalArgumentException.class, () -> SpecJson.bind(null, EsSnapshotSourceSpec.class));
    }

    @Test
    void toJsonOmitsNullsAndKeepsEmptyLists() throws Exception {
        var node = bindEs("").toJson();

        assertThat(node.has("endpoint"), is(false));
        assertThat(node.has("version"), is(false));
        assertThat(node.get("indexAllowlist").isArray(), is(true));
    }
}
