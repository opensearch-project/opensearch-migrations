package org.opensearch.migrations.transform.shim;

import javax.xml.parsers.DocumentBuilderFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads Solr field metadata from a {@code managed-schema.xml} and emits a flat
 * {@code fieldName → fieldTypeClass} map for the JS transformer.
 *
 * <p>For each {@code <field>} element, resolves its {@code type} attribute to the
 * Java class of the corresponding {@code <fieldType>} element and emits:
 * <pre>{@code fieldName → "solr.TextField" | "solr.StrField" | ... }</pre>
 *
 * <p>The JS transformer ({@code fieldRule.ts}) applies the classification:
 * {@code class.includes("TextField")} → analyzed → {@code match} query,
 * otherwise → exact → {@code term} query.
 *
 * <p>Example schema:
 * <pre>{@code
 * <fieldType name="text_general"   class="solr.TextField"      .../>
 * <fieldType name="string"         class="solr.StrField"       .../>
 * <fieldType name="my_custom_text" class="solr.TextField"      .../>
 * <field name="title"       type="text_general"   />
 * <field name="id"          type="string"         />
 * <field name="description" type="my_custom_text" />
 * }</pre>
 *
 * <p>Output:
 * <pre>{@code
 * { "title": "solr.TextField", "id": "solr.StrField", "description": "solr.TextField" }
 * }</pre>
 */
@Slf4j
public class SolrSchemaProvider {

    private SolrSchemaProvider() {}

    /**
     * Parse a Solr {@code managed-schema.xml} and return a flat
     * {@code fieldName → fieldTypeClass} map.
     *
     * <p>Returns an empty map if the path is null, the file does not exist, or parsing fails.
     *
     * @param path path to {@code managed-schema.xml} or {@code managed-schema}
     * @return immutable map of field name to Solr fieldType Java class string
     */
    public static Map<String, String> fromXmlFile(Path path) {
        if (path == null) {
            log.debug("solrSchemaXmlFile not configured, skipping fieldTypes");
            return Map.of();
        }
        if (!Files.exists(path)) {
            log.debug("managed-schema.xml not found at {}, skipping fieldTypes", path);
            return Map.of();
        }
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var doc = factory.newDocumentBuilder().parse(path.toFile());
            var root = doc.getDocumentElement();

            // Step 1: build typeName → class from <fieldType> elements.
            var typeNameToClass = buildTypeClassMap(root);

            // Step 2: walk <field> elements and resolve each to its fieldType class.
            // Fields whose type is not declared in <fieldType> elements are skipped —
            // the JS transformer treats absent fields as unknown and falls back to match.
            var result = new LinkedHashMap<String, String>();
            NodeList fields = root.getElementsByTagName("field");
            for (int i = 0; i < fields.getLength(); i++) {
                if (!(fields.item(i) instanceof Element el)) continue;
                var name = el.getAttribute("name");
                var type = el.getAttribute("type");
                if (!name.isEmpty() && !type.isEmpty()) {
                    var cls = typeNameToClass.get(type);
                    if (cls != null && !cls.isEmpty()) {
                        result.put(name, cls);
                    } else {
                        log.debug("Field '{}' references unknown fieldType '{}', skipping", name, type);
                    }
                }
            }

            log.info("Loaded {} field class mappings from {}", result.size(), path);
            return Map.copyOf(result);
        } catch (Exception e) {
            log.warn("Failed to parse managed-schema.xml at {}", path, e);
            return Map.of();
        }
    }

    /**
     * Build a map of Solr fieldType name → Java class from {@code <fieldType>} elements.
     */
    private static Map<String, String> buildTypeClassMap(Element root) {
        var map = new LinkedHashMap<String, String>();
        NodeList fieldTypes = root.getElementsByTagName("fieldType");
        for (int i = 0; i < fieldTypes.getLength(); i++) {
            if (!(fieldTypes.item(i) instanceof Element el)) continue;
            var name = el.getAttribute("name");
            var cls  = el.getAttribute("class");
            if (!name.isEmpty()) {
                map.put(name, cls);
            }
        }
        return map;
    }
}
