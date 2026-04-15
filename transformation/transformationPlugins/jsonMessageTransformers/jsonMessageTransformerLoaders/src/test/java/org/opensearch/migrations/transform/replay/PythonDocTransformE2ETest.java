package org.opensearch.migrations.transform.replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.TransformationLoader;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * E2E test: typed Python document transformation loaded via SPI.
 * Uses the bundled python/doc_transform.py resource with dataclass-based config.
 */
public class PythonDocTransformE2ETest {

    @Test
    @SuppressWarnings("unchecked")
    public void testDocumentIndexRewriteAndFieldAddition() throws Exception {
        var config = "[{\"JsonPythonTransformerProvider\": {"
            + "\"initializationResourcePath\": \"python/doc_transform.py\","
            + "\"bindingsObject\": \"{\\\"index_rewrites\\\": [{\\\"source_prefix\\\": \\\"logs-2024\\\", "
            + "\\\"target_prefix\\\": \\\"logs-migrated-2024\\\"}], "
            + "\\\"add_fields\\\": {\\\"migrated\\\": true}}\""
            + "}}]";

        var transformer = new TransformationLoader().getTransformerFactoryLoader(config);

        // Simulate a batch of documents as DocumentReindexer would pass them
        var op = new HashMap<String, Object>();
        op.put("_index", "logs-2024.01");
        op.put("_id", "doc1");

        var docBody = new HashMap<String, Object>();
        docBody.put("message", "hello world");
        docBody.put("timestamp", "2024-01-15T00:00:00Z");

        var doc = new HashMap<String, Object>();
        doc.put("operation", op);
        doc.put("document", docBody);

        var batch = new ArrayList<Object>();
        batch.add(doc);

        var result = (List<Map<String, Object>>) transformer.transformJson(batch);

        Assertions.assertEquals(1, result.size());
        var resultDoc = result.get(0);
        var resultOp = (Map<String, Object>) resultDoc.get("operation");
        var resultBody = (Map<String, Object>) resultDoc.get("document");

        Assertions.assertEquals("logs-migrated-2024.01", resultOp.get("_index"),
            "Index prefix should be rewritten");
        Assertions.assertEquals("doc1", resultOp.get("_id"),
            "Document ID should be preserved");
        Assertions.assertEquals(true, resultBody.get("migrated"),
            "migrated field should be added");
        Assertions.assertEquals("hello world", resultBody.get("message"),
            "Original fields should be preserved");
    }
}
