package org.opensearch.migrations.bulkload.solr;

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parses a Solr managed-schema.xml file into the JSON format expected by SolrSchemaConverter.
 * This enables reading schema from backup directories without a live Solr cluster.
 */
@Slf4j
public class SolrSchemaXmlParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SolrSchemaXmlParser() {}

    /**
     * Parse a managed-schema.xml file and return a JSON node matching the Solr Schema API format:
     * { "schema": { "fields": [...], "dynamicFields": [...], "copyFields": [...], "fieldTypes": [...] } }
     */
    public static JsonNode parse(Path schemaXmlPath) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var doc = factory.newDocumentBuilder().parse(Files.newInputStream(schemaXmlPath));
            var root = doc.getDocumentElement();

            var schema = MAPPER.createObjectNode();
            schema.set("fields", parseElements(root.getElementsByTagName("field")));
            schema.set("dynamicFields", parseElements(root.getElementsByTagName("dynamicField")));
            schema.set("copyFields", parseCopyFields(root.getElementsByTagName("copyField")));
            schema.set("fieldTypes", parseFieldTypes(root.getElementsByTagName("fieldType")));

            var result = MAPPER.createObjectNode();
            result.set("schema", schema);
            log.info("Parsed Solr schema from {}: {} fields, {} dynamicFields, {} fieldTypes",
                schemaXmlPath,
                schema.path("fields").size(),
                schema.path("dynamicFields").size(),
                schema.path("fieldTypes").size());
            return result;
        } catch (Exception e) {
            throw new IOException("Failed to parse Solr schema XML: " + schemaXmlPath, e);
        }
    }

    private static ArrayNode parseElements(NodeList nodes) {
        var array = MAPPER.createArrayNode();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element elem)) continue;
            var obj = MAPPER.createObjectNode();
            var attrs = elem.getAttributes();
            for (int j = 0; j < attrs.getLength(); j++) {
                var attr = attrs.item(j);
                obj.put(attr.getNodeName(), attr.getNodeValue());
            }
            array.add(obj);
        }
        return array;
    }

    private static ArrayNode parseCopyFields(NodeList nodes) {
        var array = MAPPER.createArrayNode();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element elem)) continue;
            var obj = MAPPER.createObjectNode();
            obj.put("source", elem.getAttribute("source"));
            obj.put("dest", elem.getAttribute("dest"));
            array.add(obj);
        }
        return array;
    }

    private static ArrayNode parseFieldTypes(NodeList nodes) {
        var array = MAPPER.createArrayNode();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element elem)) continue;
            var obj = MAPPER.createObjectNode();
            var attrs = elem.getAttributes();
            for (int j = 0; j < attrs.getLength(); j++) {
                var attr = attrs.item(j);
                obj.put(attr.getNodeName(), attr.getNodeValue());
            }
            array.add(obj);
        }
        return array;
    }

    /**
     * Find and parse the managed-schema.xml from a Solr backup collection directory.
     * Looks in the latest zk_backup_N/configs/&lt;configName&gt;/managed-schema.xml,
     * where N is the highest revision from successive backups to the same location.
     * Layout (flat vs. two-level) is resolved automatically via
     * {@link SolrBackupLayout#findLatestZkBackup(Path)}.
     */
    public static JsonNode findAndParse(Path collectionDir) {
        var latestZkBackup = SolrBackupLayout.findLatestZkBackup(collectionDir);
        if (latestZkBackup == null) {
            log.warn("No ZK config backup found under {}, using empty schema", collectionDir);
            return MAPPER.createObjectNode();
        }
        var zkBackup = latestZkBackup.resolve("configs");
        if (!Files.isDirectory(zkBackup)) {
            log.warn("No ZK config backup found at {}, using empty schema", zkBackup);
            return MAPPER.createObjectNode();
        }
        try (var configs = Files.list(zkBackup)) {
            var configDir = configs.filter(Files::isDirectory).findFirst().orElse(null);
            if (configDir == null) {
                log.warn("No config directory found in {}", zkBackup);
                return MAPPER.createObjectNode();
            }
            // Solr config evolution: managed-schema.xml (current) → managed-schema (Solr 6+ default)
            // → schema.xml (ClassicIndexSchemaFactory; common in Solr 5/6/7 configs upgraded in place).
            var schemaFile = configDir.resolve("managed-schema.xml");
            if (!Files.exists(schemaFile)) {
                schemaFile = configDir.resolve("managed-schema");
            }
            if (!Files.exists(schemaFile)) {
                schemaFile = configDir.resolve("schema.xml");
            }
            if (!Files.exists(schemaFile)) {
                log.warn("No managed-schema.xml, managed-schema, or schema.xml found in {}", configDir);
                return MAPPER.createObjectNode();
            }
            return parse(schemaFile);
        } catch (IOException e) {
            log.warn("Failed to read schema from {}: {}", zkBackup, e.getMessage());
            return MAPPER.createObjectNode();
        }
    }
}
