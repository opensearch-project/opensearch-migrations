package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class SolrSchemaXmlParserTest {

    private static final String SCHEMA_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <schema name="test" version="1.6">
          <field name="id" type="string" stored="true" required="true"/>
          <field name="title" type="text_general" stored="true" multiValued="false"/>
          <dynamicField name="*_s" type="string" stored="true"/>
          <copyField source="title" dest="text_all"/>
          <fieldType name="string" class="solr.StrField"/>
          <fieldType name="text_general" class="solr.TextField" positionIncrementGap="100"/>
        </schema>
        """;

    @Test
    void parsePopulatesAllSchemaArrays(@TempDir Path tmp) throws IOException {
        var schemaFile = tmp.resolve("managed-schema.xml");
        Files.writeString(schemaFile, SCHEMA_XML);

        var result = SolrSchemaXmlParser.parse(schemaFile);
        var schema = result.get("schema");

        assertThat(schema.get("fields").size(), equalTo(2));
        assertThat(schema.get("dynamicFields").size(), equalTo(1));
        assertThat(schema.get("copyFields").size(), equalTo(1));
        assertThat(schema.get("fieldTypes").size(), equalTo(2));
    }

    @Test
    void parsePreservesFieldAttributes(@TempDir Path tmp) throws IOException {
        var schemaFile = tmp.resolve("managed-schema.xml");
        Files.writeString(schemaFile, SCHEMA_XML);

        var result = SolrSchemaXmlParser.parse(schemaFile);
        var fields = result.get("schema").get("fields");

        var idField = fields.get(0);
        assertThat(idField.get("name").asText(), equalTo("id"));
        assertThat(idField.get("type").asText(), equalTo("string"));
        assertThat(idField.get("stored").asText(), equalTo("true"));
        assertThat(idField.get("required").asText(), equalTo("true"));

        var titleField = fields.get(1);
        assertThat(titleField.get("name").asText(), equalTo("title"));
        assertThat(titleField.get("multiValued").asText(), equalTo("false"));

        var copyField = result.get("schema").get("copyFields").get(0);
        assertThat(copyField.get("source").asText(), equalTo("title"));
        assertThat(copyField.get("dest").asText(), equalTo("text_all"));

        var fieldType = result.get("schema").get("fieldTypes").get(1);
        assertThat(fieldType.get("name").asText(), equalTo("text_general"));
        assertThat(fieldType.get("class").asText(), equalTo("solr.TextField"));
    }

    @Test
    void findAndParseLocatesSchemaXml(@TempDir Path tmp) throws IOException {
        var configDir = tmp.resolve("zk_backup_0/configs/myconfig");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("managed-schema.xml"), SCHEMA_XML);

        var result = SolrSchemaXmlParser.findAndParse(tmp);
        assertThat(result.get("schema").get("fields").size(), equalTo(2));
    }

    @Test
    void findAndParseFallsBackToManagedSchema(@TempDir Path tmp) throws IOException {
        var configDir = tmp.resolve("zk_backup_0/configs/myconfig");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("managed-schema"), SCHEMA_XML);

        var result = SolrSchemaXmlParser.findAndParse(tmp);
        assertThat(result.get("schema").get("fields").size(), equalTo(2));
    }

    @Test
    void findAndParseReturnsEmptyWhenNoSchema(@TempDir Path tmp) {
        var result = SolrSchemaXmlParser.findAndParse(tmp);
        assertThat(result.size(), equalTo(0));
    }
}
