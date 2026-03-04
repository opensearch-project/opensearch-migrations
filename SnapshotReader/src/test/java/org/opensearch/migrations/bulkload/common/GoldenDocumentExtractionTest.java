package org.opensearch.migrations.bulkload.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Validates golden document extraction fixtures are well-formed and non-empty.
 */
@Slf4j
class GoldenDocumentExtractionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GOLDEN_DIR = Path.of("RFS/test-resources/golden");

    @Test
    void es710WithSourceDocsAreWellFormed() throws Exception {
        validateGoldenDocs("es710-wsoft-docs.json");
    }

    @Test
    void es710WithoutSourceDocsAreWellFormed() throws Exception {
        validateGoldenDocs("es710-wosoft-docs.json");
    }

    @Test
    void es68DocsAreWellFormed() throws Exception {
        validateGoldenDocs("es68-docs.json");
    }

    @Test
    void es68MergedDocsAreWellFormed() throws Exception {
        validateGoldenDocs("es68-merged-docs.json");
    }

    @Test
    void es56DocsAreWellFormed() throws Exception {
        validateGoldenDocs("es56-docs.json");
    }

    private void validateGoldenDocs(String filename) throws Exception {
        Path path = GOLDEN_DIR.resolve(filename);
        if (!Files.exists(path)) {
            log.warn("Golden fixture {} not found at {}, skipping", filename, path);
            return;
        }

        var content = Files.readString(path, StandardCharsets.UTF_8);
        var docs = MAPPER.readValue(content, new TypeReference<List<java.util.Map<String, Object>>>() {});

        assertFalse(docs.isEmpty(), filename + " should contain at least one document");
        for (var doc : docs) {
            assertThat(filename + " doc should have _id", doc.get("_id"), notNullValue());
        }
        assertThat(filename + " should have docs", docs.size(), greaterThan(0));
        log.info("{}: {} docs validated", filename, docs.size());
    }
}
