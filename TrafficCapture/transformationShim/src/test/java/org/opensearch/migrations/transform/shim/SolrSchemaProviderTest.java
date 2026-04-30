package org.opensearch.migrations.transform.shim;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolrSchemaProviderTest {

    @TempDir
    Path tempDir;

    // ─── Basic field resolution ───────────────────────────────────────────────

    @Test
    void resolvesWellKnownTextFieldToTextFieldClass() throws Exception {
        var xml = schemaXml(
            "<fieldType name='text_general' class='solr.TextField'/>",
            "<field name='title' type='text_general'/>"
        );

        var result = SolrSchemaProvider.fromXmlFile(xml);

        assertEquals("solr.TextField", result.get("title"));
    }

    @Test
    void resolvesStringFieldToStrFieldClass() throws Exception {
        var xml = schemaXml(
            "<fieldType name='string' class='solr.StrField'/>",
            "<field name='id' type='string'/>"
        );

        var result = SolrSchemaProvider.fromXmlFile(xml);

        assertEquals("solr.StrField", result.get("id"));
    }

    @Test
    void resolvesNumericFieldToIntPointFieldClass() throws Exception {
        var xml = schemaXml(
            "<fieldType name='pint' class='solr.IntPointField'/>",
            "<field name='price' type='pint'/>"
        );

        var result = SolrSchemaProvider.fromXmlFile(xml);

        assertEquals("solr.IntPointField", result.get("price"));
    }

    @Test
    void resolvesDateFieldToDatePointFieldClass() throws Exception {
        var xml = schemaXml(
            "<fieldType name='pdate' class='solr.DatePointField'/>",
            "<field name='created' type='pdate'/>"
        );

        var result = SolrSchemaProvider.fromXmlFile(xml);

        assertEquals("solr.DatePointField", result.get("created"));
    }

    // ─── Custom fieldType resolution ──────────────────────────────────────────

    @Test
    void resolvesCustomTextTypeViaClass() throws Exception {
        // Custom type name that doesn't contain "text" — class is the ground truth
        var xml = schemaXml(
            "<fieldType name='my_custom_text' class='solr.TextField'/>",
            "<field name='description' type='my_custom_text'/>"
        );

        var result = SolrSchemaProvider.fromXmlFile(xml);

        assertEquals("solr.TextField", result.get("description"));
    }

    @Test
    void resolvesCustomExactTypeWithMisleadingName() throws Exception {
        // Type named "text_acs" but backed by IntPointField — class wins
        var xml = schemaXml(
            "<fieldType name='text_acs' class='solr.IntPointField'/>",
            "<field name='score' type='text_acs'/>"
        );

        var result = SolrSchemaProvider.fromXmlFile(xml);

        assertEquals("solr.IntPointField", result.get("score"));
    }

    @Test
    void resolvesFullyQualifiedClassName() throws Exception {
        var xml = schemaXml(
            "<fieldType name='mytext' class='org.apache.solr.schema.TextField'/>",
            "<field name='body' type='mytext'/>"
        );

        var result = SolrSchemaProvider.fromXmlFile(xml);

        assertEquals("org.apache.solr.schema.TextField", result.get("body"));
    }

    // ─── Multiple fields ──────────────────────────────────────────────────────

    @Test
    void resolvesMultipleFieldsWithDifferentTypes() throws Exception {
        var xml = schemaXml(
            "<fieldType name='string'       class='solr.StrField'/>",
            "<fieldType name='text_general' class='solr.TextField'/>",
            "<fieldType name='pint'         class='solr.IntPointField'/>",
            "<field name='id'       type='string'/>",
            "<field name='title'    type='text_general'/>",
            "<field name='quantity' type='pint'/>"
        );

        var result = SolrSchemaProvider.fromXmlFile(xml);

        assertEquals(3, result.size());
        assertEquals("solr.StrField",      result.get("id"));
        assertEquals("solr.TextField",     result.get("title"));
        assertEquals("solr.IntPointField", result.get("quantity"));
    }

    // ─── Empty class attribute ────────────────────────────────────────────────

    @Test
    void skipsFieldWhenFieldTypeHasNoClass() throws Exception {
        // fieldType with no class attribute — field is skipped since we can't classify it.
        // Falls back to match query in the JS transformer (safe default).
        var xml = schemaXml(
            "<fieldType name='mystery'/>",
            "<field name='x' type='mystery'/>"
        );

        var result = SolrSchemaProvider.fromXmlFile(xml);

        assertFalse(result.containsKey("x"));
    }

    @Test
    void skipsFieldWhenFieldTypeNotFoundInSchema() throws Exception {
        // Field references a type not declared in <fieldType> elements — skipped entirely.
        // The JS transformer treats absent fields as unknown and falls back to match query,
        // which is safer than storing "" which would incorrectly trigger term query.
        var xml = schemaXml(
            "<field name='x' type='undeclared_type'/>"
        );

        var result = SolrSchemaProvider.fromXmlFile(xml);

        // Field is absent — not stored with empty class
        assertFalse(result.containsKey("x"));
    }

    // ─── Error handling ───────────────────────────────────────────────────────

    @Test
    void returnsEmptyForMissingFile() {
        var result = SolrSchemaProvider.fromXmlFile(Path.of("/nonexistent/managed-schema.xml"));
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForNullPath() {
        var result = SolrSchemaProvider.fromXmlFile(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyForMalformedXml() throws Exception {
        var xml = tempDir.resolve("bad.xml");
        Files.writeString(xml, "not xml at all");

        var result = SolrSchemaProvider.fromXmlFile(xml);
        assertTrue(result.isEmpty());
    }

    @Test
    void skipsFieldsWithEmptyNameOrType() throws Exception {
        var xml = schemaXml(
            "<fieldType name='string' class='solr.StrField'/>",
            "<field name='' type='string'/>",   // empty name — skipped
            "<field name='id' type=''/>"         // empty type — skipped
        );

        var result = SolrSchemaProvider.fromXmlFile(xml);

        assertTrue(result.isEmpty());
    }

    // ─── Result is immutable ──────────────────────────────────────────────────

    @Test
    void resultMapIsImmutable() throws Exception {
        var xml = schemaXml(
            "<fieldType name='string' class='solr.StrField'/>",
            "<field name='id' type='string'/>"
        );

        var result = SolrSchemaProvider.fromXmlFile(xml);

        assertFalse(result.isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> result.put("extra", "value")
        );
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Write a minimal managed-schema.xml with the given fieldType and field elements
     * and return the path.
     */
    private Path schemaXml(String... elements) throws Exception {
        var xml = tempDir.resolve("managed-schema.xml");
        var body = String.join("\n", elements);
        Files.writeString(xml, """
            <?xml version="1.0" encoding="UTF-8" ?>
            <schema name="test" version="1.6">
            """ + body + """
            </schema>
            """);
        return xml;
    }
}
