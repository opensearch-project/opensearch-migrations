package org.opensearch.migrations.bulkload.version_es_7_10;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class SnapshotRepoData_ES_7_10Test {

    private final String basicSnapshotJson = "    {\n" + //
        "      \"snapshots\" : [ {\n" + //
        "        \"name\" : \"snapshot-1\",\n" + //
        "        \"uuid\" : \"DToXzBoXTha6u2KRsQZ0Yw\",\n" + //
        "        \"state\" : 1,\n" + //
        "        \"index_metadata_lookup\" : {\n" + //
        "          \"VUr1hXkwQOOeWNBnvZ7pmA\" : \"4RVV4jgXRNqALWe9TxkoYA-_na_-1-2-1\"\n" + //
        "        },\n" + //
        "        \"version\" : \"7.10.2\"\n" + //
        "      } ],\n" + //
        "      \"indices\" : {\n" + //
        "        \"my-index\" : {\n" + //
        "          \"id\" : \"VUr1hXkwQOOeWNBnvZ7pmA\",\n" + //
        "          \"snapshots\" : [ \"DToXzBoXTha6u2KRsQZ0Yw\" ],\n" + //
        "          \"shard_generations\" : [ \"VcuUZNqoR8SilY8H3VY-fQ\" ]\n" + //
        "        }\n" + //
        "      },\n" + //
        "      \"min_version\" : \"7.9.0\",\n" + //
        "      \"index_metadata_identifiers\" : {\n" + //
        "        \"4RVV4jgXRNqALWe9TxkoYA-_na_-1-2-1\" : \"-I-dMZABiRFG_R03ChN0\"\n" + //
        "      }\n" + //
        "    }";

    @Test
    void testFromRepoFile_default() {
        // Setup
        final var jsonInFile = createTempFile(basicSnapshotJson);

        // Act
        final var result = SnapshotRepoData_ES_7_10.fromRepoFile(jsonInFile);

        // Verify
        assertThat(result.getMinVersion(), equalTo("7.9.0"));
        assertThat(result.getIndices().size(), equalTo(1));
    }

    @Test
    void testFromRepoFile_extraFields() {
        // Setup
        var jsonWithExtraFields = insertAtLine(basicSnapshotJson, "\"foo\":\"bar\",", 2);
        final var jsonInFile = createTempFile(jsonWithExtraFields);

        // Act
        final var result = SnapshotRepoData_ES_7_10.fromRepoFile(jsonInFile);

        // Verify
        assertThat(result.getMinVersion(), equalTo("7.9.0"));
        assertThat(result.getIndices().size(), equalTo(1));
    }

    private String insertAtLine(final String source, final String toAdd, final int lineNumber) {
        final var lines = source.split("\n");
        final StringBuilder sb = new StringBuilder();
        for (var i = 0; i < lines.length; i++) {
            if (lineNumber == i) {
                sb.append(toAdd);
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private Path createTempFile(final String contents) {
        try {
            final var file = File.createTempFile("repoFile", ".txt");
            try (final var fos = new PrintWriter(new FileOutputStream(file))) {
                fos.append(contents);
            }
            file.deleteOnExit();
            return Path.of(file.getAbsolutePath());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
