package org.opensearch.migrations.transform.shim;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JavascriptTransformer;
import org.opensearch.migrations.transform.ScriptTransformerProvider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Transformer provider for Solr→OpenSearch query translation.
 *
 * <p>Extends {@link ScriptTransformerProvider} (same base as {@code JsonJSTransformerProvider})
 * with two Solr-specific features:
 * <ul>
 *   <li>Auto-prepends the GraalVM URLSearchParams polyfill to all scripts</li>
 *   <li>Supports {@code solrConfigXmlFile} to auto-parse solrconfig.xml into bindingsObject</li>
 * </ul>
 *
 * <p>Config format (same structure as JsonJSTransformerProvider):
 * <pre>{@code
 * [{"SolrTransformerProvider": {
 *     "initializationScriptFile": "/path/to/request.js",
 *     "bindingsObject": "{}",
 *     "solrConfigXmlFile": "/path/to/solrconfig.xml"
 * }}]
 * }</pre>
 *
 * <p>Registered via ServiceLoader in META-INF/services.
 */
@Slf4j
public class SolrTransformerProvider extends ScriptTransformerProvider {

    public static final String SOLR_CONFIG_XML_FILE_KEY = "solrConfigXmlFile";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * GraalVM URLSearchParams polyfill — required because GraalVM's JS engine
     * doesn't include browser/Node APIs. Prepended to every script.
     */
    public static final String JS_POLYFILL =
        "if (typeof URLSearchParams === 'undefined') {\n" +
        "  globalThis.URLSearchParams = function(qs) {\n" +
        "    this._map = {};\n" +
        "    if (!qs) return;\n" +
        "    qs.split('&').forEach(function(pair) {\n" +
        "      var idx = pair.indexOf('=');\n" +
        "      if (idx < 0) return;\n" +
        "      var k = decodeURIComponent(pair.slice(0, idx));\n" +
        "      var v = decodeURIComponent(pair.slice(idx + 1));\n" +
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
    protected IJsonTransformer buildTransformer(
            String script, Object bindingsObject, Map<String, Object> config) throws IOException {
        // Merge solrConfig from XML file into bindings if specified
        var mergedBindings = mergeSolrConfigXml(bindingsObject, config);
        return new JavascriptTransformer(JS_POLYFILL + script, mergedBindings);
    }

    /**
     * If {@code solrConfigXmlFile} is present in config, parse it and merge
     * the resulting solrConfig into the bindings object.
     */
    @SuppressWarnings("unchecked")
    private Object mergeSolrConfigXml(Object bindingsObject, Map<String, Object> config) throws IOException {
        var xmlFile = (String) config.getOrDefault(SOLR_CONFIG_XML_FILE_KEY, null);
        if (xmlFile == null) {
            return bindingsObject;
        }

        var solrConfig = SolrConfigProvider.fromXmlFile(Path.of(xmlFile));
        if (solrConfig.isEmpty()) {
            return bindingsObject;
        }

        // Merge into bindings — bindings is a Map from parseBindingsObject
        Map<String, Object> merged;
        if (bindingsObject instanceof Map) {
            merged = new LinkedHashMap<>((Map<String, Object>) bindingsObject);
        } else {
            merged = new LinkedHashMap<>();
        }
        merged.put("solrConfig", solrConfig);
        log.info("Merged solrconfig.xml from {} into bindings", xmlFile);
        return merged;
    }
}
