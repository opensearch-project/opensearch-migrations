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
    void findAndParseFallsBackToSchemaXml(@TempDir Path tmp) throws IOException {
        // Solr 6/7 configs upgraded from Solr 5 with ClassicIndexSchemaFactory
        // ship only schema.xml — neither managed-schema.xml nor managed-schema is present.
        var configDir = tmp.resolve("zk_backup_0/configs/myconfig");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("schema.xml"), SCHEMA_XML);

        var result = SolrSchemaXmlParser.findAndParse(tmp);
        assertThat(result.get("schema").get("fields").size(), equalTo(2));
    }

    @Test
    void findAndParsePrefersManagedSchemaXmlOverFallbacks(@TempDir Path tmp) throws IOException {
        // When all three exist, managed-schema.xml wins.
        var configDir = tmp.resolve("zk_backup_0/configs/myconfig");
        Files.createDirectories(configDir);
        var preferredSchema = SCHEMA_XML; // 2 fields
        var legacySchema = """
            <?xml version="1.0" encoding="UTF-8"?>
            <schema name="legacy" version="1.5">
              <field name="only_one" type="string" stored="true"/>
              <fieldType name="string" class="solr.StrField"/>
            </schema>
            """;
        Files.writeString(configDir.resolve("managed-schema.xml"), preferredSchema);
        Files.writeString(configDir.resolve("managed-schema"), legacySchema);
        Files.writeString(configDir.resolve("schema.xml"), legacySchema);

        var result = SolrSchemaXmlParser.findAndParse(tmp);
        assertThat(result.get("schema").get("fields").size(), equalTo(2));
    }

    @Test
    void findAndParseReturnsEmptyWhenNoSchema(@TempDir Path tmp) {
        var result = SolrSchemaXmlParser.findAndParse(tmp);
        assertThat(result.size(), equalTo(0));
    }

    @Test
    void findAndParseReturnsEmptyWhenZkBackupHasNoConfigsDir(@TempDir Path tmp) throws IOException {
        // zk_backup_0/ exists but no configs/ subdirectory inside it.
        Files.createDirectories(tmp.resolve("zk_backup_0"));

        var result = SolrSchemaXmlParser.findAndParse(tmp);
        assertThat(result.size(), equalTo(0));
    }

    @Test
    void findAndParseReturnsEmptyWhenConfigsDirIsEmpty(@TempDir Path tmp) throws IOException {
        // zk_backup_0/configs/ exists but contains no config subdirectory.
        Files.createDirectories(tmp.resolve("zk_backup_0/configs"));

        var result = SolrSchemaXmlParser.findAndParse(tmp);
        assertThat(result.size(), equalTo(0));
    }

    @Test
    void findAndParseReturnsEmptyWhenConfigDirHasNoSchemaFiles(@TempDir Path tmp) throws IOException {
        // zk_backup_0/configs/myconfig/ exists but has no schema files of any kind.
        var configDir = tmp.resolve("zk_backup_0/configs/myconfig");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("solrconfig.xml"), "<config/>");

        var result = SolrSchemaXmlParser.findAndParse(tmp);
        assertThat(result.size(), equalTo(0));
    }

    @Test
    void findAndParsePrefersBareManagedSchemaOverSchemaXml(@TempDir Path tmp) throws IOException {
        // managed-schema (no .xml) should win over schema.xml when both exist
        // (no managed-schema.xml present).
        var configDir = tmp.resolve("zk_backup_0/configs/myconfig");
        Files.createDirectories(configDir);
        var preferredSchema = SCHEMA_XML; // 2 fields
        var legacySchema = """
            <?xml version="1.0" encoding="UTF-8"?>
            <schema name="legacy" version="1.5">
              <field name="only_one" type="string" stored="true"/>
              <fieldType name="string" class="solr.StrField"/>
            </schema>
            """;
        Files.writeString(configDir.resolve("managed-schema"), preferredSchema);
        Files.writeString(configDir.resolve("schema.xml"), legacySchema);

        var result = SolrSchemaXmlParser.findAndParse(tmp);
        assertThat(result.get("schema").get("fields").size(), equalTo(2));
    }
}
