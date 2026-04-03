package org.opensearch.migrations.transform.shim;

import javax.xml.parsers.DocumentBuilderFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parses Solr's solrconfig.xml to extract requestHandler defaults and invariants.
 *
 * Output format (JSON-compatible Map):
 * <pre>
 * {
 *   "/select": {
 *     "defaults": { "df": "title", "rows": "10" },
 *     "invariants": { "facet.field": "cat" }
 *   }
 * }
 * </pre>
 *
 * Used by all modes (shim, replayer, standalone) via bindingsObject injection.
 */
@Slf4j
public class SolrConfigProvider {

    private SolrConfigProvider() {}

    /**
     * Parse solrconfig.xml and extract requestHandler defaults/invariants.
     * Returns empty map if path is null, file doesn't exist, or parsing fails.
     */
    public static Map<String, Object> fromXmlFile(Path path) {
        if (path == null || !Files.exists(path)) {
            log.debug("solrconfig.xml not found at {}, skipping defaults", path);
            return Map.of();
        }
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(path.toFile());
            return parseRequestHandlers(doc);
        } catch (Exception e) {
            log.warn("Failed to parse solrconfig.xml at {}: {}", path, e.getMessage());
            return Map.of();
        }
    }

    private static Map<String, Object> parseRequestHandlers(Document doc) {
        Map<String, Object> result = new LinkedHashMap<>();
        NodeList handlers = doc.getElementsByTagName("requestHandler");
        for (int i = 0; i < handlers.getLength(); i++) {
            Element handler = (Element) handlers.item(i);
            String name = handler.getAttribute("name");
            if (name.isEmpty()) continue;

            Map<String, Object> handlerConfig = parseLstEntries(handler);
            if (!handlerConfig.isEmpty()) result.put(name, handlerConfig);
        }
        return result;
    }

    /** Single-pass extraction of defaults, invariants, and appends from <lst> children. */
    private static Map<String, Object> parseLstEntries(Element parent) {
        Map<String, String> defaults = new LinkedHashMap<>();
        Map<String, String> invariants = new LinkedHashMap<>();
        Map<String, String> appends = new LinkedHashMap<>();

        NodeList lsts = parent.getElementsByTagName("lst");
        for (int i = 0; i < lsts.getLength(); i++) {
            Element lst = (Element) lsts.item(i);
            String lstName = lst.getAttribute("name");
            Map<String, String> target = switch (lstName) {
                case "defaults" -> defaults;
                case "invariants" -> invariants;
                case "appends" -> appends;
                default -> {
                    log.debug("Skipping unknown lst name '{}' in requestHandler", lstName);
                    yield null;
                }
            };
            if (target == null) continue;
            for (var child = lst.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element el) {
                    String paramName = el.getAttribute("name");
                    String paramValue = el.getTextContent().trim();
                    if (!paramName.isEmpty()) target.put(paramName, paramValue);
                }
            }
        }

        Map<String, Object> handlerConfig = new LinkedHashMap<>();
        if (!defaults.isEmpty()) handlerConfig.put("defaults", defaults);
        if (!invariants.isEmpty()) handlerConfig.put("invariants", invariants);
        if (!appends.isEmpty()) handlerConfig.put("appends", appends);
        return handlerConfig;
    }
}
