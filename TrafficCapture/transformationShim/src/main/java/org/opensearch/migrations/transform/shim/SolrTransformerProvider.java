package org.opensearch.migrations.transform.shim;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JavascriptTransformer;
import org.opensearch.migrations.transform.ScriptTransformerProvider;

import lombok.extern.slf4j.Slf4j;

/**
 * Solr-specific transformer provider — extends {@link ScriptTransformerProvider}
 * (same base class as {@code JsonJSTransformerProvider}).
 *
 * <p>Differences from {@code JsonJSTransformerProvider}:
 * <ul>
 *   <li>Auto-prepends the GraalVM {@code URLSearchParams} polyfill to all scripts</li>
 *   <li>Supports {@code solrConfigXmlFile} config key to auto-parse solrconfig.xml into bindings</li>
 *   <li>Supports {@code solrSchemaXmlFile} config key to derive field type metadata from
 *       managed-schema.xml into bindings, enabling term vs match query selection</li>
 * </ul>
 *
 * <p>Registered via {@code META-INF/services/org.opensearch.migrations.transform.IJsonTransformerProvider}
 * and discovered by {@link org.opensearch.migrations.transform.TransformationLoader} at runtime.
 *
 * <p>Config format:
 * <pre>{@code
 * {"SolrTransformerProvider": {
 *   "initializationScriptFile": "/path/to/request.js",
 *   "bindingsObject": "{}",
 *   "solrConfigXmlFile": "/path/to/solrconfig.xml",
 *   "solrSchemaXmlFile": "/path/to/managed-schema.xml"
 * }}
 * }</pre>
 */
@Slf4j
public class SolrTransformerProvider extends ScriptTransformerProvider {

    public static final String SOLR_CONFIG_XML_FILE_KEY = "solrConfigXmlFile";
    public static final String SOLR_SCHEMA_XML_FILE_KEY = "solrSchemaXmlFile";

    /**
     * Minimal URLSearchParams polyfill for GraalVM — the engine does not provide
     * the Web API. Supports get, has, set, append, getAll, forEach, keys, values,
     * entries, delete, toString.
     */
    public static final String JS_POLYFILL =
        "if (typeof URLSearchParams === 'undefined') {\n" +
        "  globalThis.URLSearchParams = function(qs) {\n" +
        "    this._map = {};\n" +
        "    if (!qs) return;\n" +
        "    qs.split('&').forEach(function(pair) {\n" +
        "      var idx = pair.indexOf('=');\n" +
        "      if (idx < 0) return;\n" +
        "      var k = decodeURIComponent(pair.slice(0, idx).replace(/\\+/g, ' '));\n" +
        "      var v = decodeURIComponent(pair.slice(idx + 1).replace(/\\+/g, ' '));\n" +
        "      if (!this._map[k]) this._map[k] = [];\n" +
        "      this._map[k].push(v);\n" +
        "    }.bind(this));\n" +
        "  };\n" +
        "  URLSearchParams.prototype.get = function(k) { return this._map[k] ? this._map[k][0] : null; };\n" +
        "  URLSearchParams.prototype.has = function(k) { return k in this._map; };\n" +
        "  URLSearchParams.prototype.set = function(k, v) { this._map[k] = [String(v)]; };\n" +
        "  URLSearchParams.prototype.append = function(k, v) { if (!this._map[k]) this._map[k] = []; this._map[k].push(String(v)); };\n" +
        "  URLSearchParams.prototype.getAll = function(k) { return this._map[k] || []; };\n" +
        "  URLSearchParams.prototype.forEach = function(cb) { for (var k in this._map) { this._map[k].forEach(function(v) { cb(v, k); }); } };\n" +
        "  URLSearchParams.prototype.keys = function() { return Object.keys(this._map); };\n" +
        "  URLSearchParams.prototype.values = function() { var r = []; for (var k in this._map) { this._map[k].forEach(function(v) { r.push(v); }); } return r; };\n" +
        "  URLSearchParams.prototype.entries = function() { var r = []; for (var k in this._map) { this._map[k].forEach(function(v) { r.push([k, v]); }); } return r; };\n" +
        "  URLSearchParams.prototype.delete = function(k) { delete this._map[k]; };\n" +
        "  URLSearchParams.prototype.toString = function() { var r = []; for (var k in this._map) { this._map[k].forEach(function(v) { r.push(encodeURIComponent(k) + '=' + encodeURIComponent(v)); }); } return r.join('&'); };\n" +
        "}\n";

    @Override
    protected String getLanguageName() {
        return "JavaScript";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected IJsonTransformer buildTransformer(
            String script, Object bindingsObject, Map<String, Object> config) throws IOException {
        var mergedBindings = mergeBindings(bindingsObject, config);
        return new JavascriptTransformer(JS_POLYFILL + script, mergedBindings);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeBindings(Object bindingsObject, Map<String, Object> config)
            throws IOException {
        var bindings = bindingsObject instanceof Map
            ? new LinkedHashMap<>((Map<String, Object>) bindingsObject)
            : new LinkedHashMap<String, Object>();

        var xmlFile = (String) config.get(SOLR_CONFIG_XML_FILE_KEY);
        if (xmlFile != null && !xmlFile.isBlank()) {
            var solrConfig = SolrConfigProvider.fromXmlFile(Path.of(xmlFile));
            if (!solrConfig.isEmpty()) {
                bindings.put("solrConfig", solrConfig);
                log.info("Loaded solrConfig from {}", xmlFile);
            }
        }

        var schemaFile = (String) config.get(SOLR_SCHEMA_XML_FILE_KEY);
        if (schemaFile != null && !schemaFile.isBlank()) {
            var fieldTypes = SolrSchemaProvider.fromXmlFile(Path.of(schemaFile));
            if (!fieldTypes.isEmpty()) {
                bindings.put("fieldTypes", fieldTypes);
                log.info("Loaded {} fieldTypes from {}", fieldTypes.size(), schemaFile);
            }
        }
        return bindings;
    }

}
