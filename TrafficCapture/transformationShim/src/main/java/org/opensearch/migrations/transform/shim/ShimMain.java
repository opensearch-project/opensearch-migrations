package org.opensearch.migrations.transform.shim;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JavascriptTransformer;
import org.opensearch.migrations.transform.shim.netty.BasicAuthSigningHandler;
import org.opensearch.migrations.transform.shim.netty.SigV4SigningHandler;
import org.opensearch.migrations.transform.shim.validation.DocCountValidator;
import org.opensearch.migrations.transform.shim.validation.DocIdValidator;
import org.opensearch.migrations.transform.shim.validation.FieldIgnoringEquality;
import org.opensearch.migrations.transform.shim.validation.JavascriptValidator;
import org.opensearch.migrations.transform.shim.validation.Target;
import org.opensearch.migrations.transform.shim.validation.ValidationRule;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.converters.IParameterSplitter;
import io.netty.channel.ChannelHandler;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

/**
 * CLI entry point for the multi-target validation shim proxy.
 *
 * <p>Example usage:
 * <pre>{@code
 * --listenPort 8080 \
 * --target solr=http://solr:8983 \
 * --target opensearch=https://opensearch:9200 \
 * --targetTransform opensearch=request:req.js,response:resp.js \
 * --targetAuth opensearch=sigv4:es,us-east-1 \
 * --primary solr \
 * --validator field-equality:solr,opensearch:ignore=responseHeader.QTime \
 * --timeout 5000
 * }</pre>
 */
@Slf4j
public class ShimMain {

    /** Prevents JCommander from splitting parameter values on commas. */
    public static class NoSplitter implements IParameterSplitter {
        @Override
        public List<String> split(String value) {
            return List.of(value);
        }
    }

    public static class Parameters {
        @Parameter(required = true, names = {"--listenPort"},
            description = "Port for the validation shim proxy to listen on.")
        public int listenPort;

        @Parameter(required = true, names = {"--target"},
            description = "Named target: name=uri (e.g. solr=http://solr:8983). Repeatable.")
        public List<String> targets = new ArrayList<>();

        @Parameter(required = true, names = {"--primary"},
            description = "Name of the target whose response is returned to the client.")
        public String primary;

        @Parameter(names = {"--active"},
            description = "Comma-separated list of active targets. Defaults to all defined targets.")
        public String active;

        @Parameter(names = {"--targetTransform"}, splitter = NoSplitter.class,
            description = "Per-target transforms: name=request:file.js,response:file.js. Repeatable.")
        public List<String> targetTransforms = new ArrayList<>();

        @Parameter(names = {"--targetAuth"}, splitter = NoSplitter.class,
            description = "Per-target auth: name=sigv4:service,region | name=basic:user:pass | "
                + "name=header:value | name=none. Repeatable.")
        public List<String> targetAuths = new ArrayList<>();

        @Parameter(names = {"--validator"}, splitter = NoSplitter.class,
            description = "Validator spec. Repeatable. Formats:\n"
                + "  field-equality:targetA,targetB:ignore=path1,path2\n"
                + "  doc-count:targetA,targetB:assert=a<=b\n"
                + "  doc-ids:targetA,targetB[:ordered]\n"
                + "  js:targetA,targetB:script=file.js")
        public List<String> validators = new ArrayList<>();

        @Parameter(names = {"--insecureBackend"},
            description = "Trust all backend TLS certificates.")
        public boolean insecureBackend;

        @Parameter(names = {"--timeout"},
            description = "Secondary target timeout in milliseconds.")
        public long timeoutMs = 30000;

        @Parameter(names = {"--watchTransforms"},
            description = "Watch transform JS files for changes and hot-reload them.")
        public boolean watchTransforms;

        @Parameter(names = {"--help", "-h"}, help = true, description = "Show usage.")
        public boolean help;
    }

    public static void main(String[] args) throws Exception {
        var params = new Parameters();
        var jCommander = JCommander.newBuilder().addObject(params).build();
        jCommander.setProgramName("Shim");
        try {
            jCommander.parse(args);
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            jCommander.usage();
            System.exit(1);
        }
        if (params.help) {
            jCommander.usage();
            return;
        }

        Map<Path, ReloadableTransformer> watchedTransforms = new LinkedHashMap<>();
        Map<String, Target> targets = parseTargets(params, watchedTransforms);
        Set<String> activeTargets = parseActiveTargets(params, targets);
        List<ValidationRule> validators = parseValidators(params);

        var proxy = new ShimProxy(
            params.listenPort, targets, params.primary, activeTargets, validators,
            null, params.insecureBackend, Duration.ofMillis(params.timeoutMs));

        TransformFileWatcher watcher = null;
        if (params.watchTransforms && !watchedTransforms.isEmpty()) {
            watcher = new TransformFileWatcher(watchedTransforms);
            var watcherThread = new Thread(watcher, "transform-file-watcher");
            watcherThread.setDaemon(true);
            watcherThread.start();
        }

        final TransformFileWatcher watcherRef = watcher;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                log.info("Shutdown signal received, stopping proxy...");
                if (watcherRef != null) watcherRef.close();
                proxy.stop();
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }));

        proxy.start();
        log.info("Shim running on port {}", params.listenPort);
        proxy.waitForClose();
    }

    static Map<String, Target> parseTargets(Parameters params,
            Map<Path, ReloadableTransformer> watchedTransforms) throws IOException {
        // Parse base targets: name=uri
        Map<String, URI> uris = new LinkedHashMap<>();
        for (String spec : params.targets) {
            int eq = spec.indexOf('=');
            if (eq <= 0) throw new ParameterException("Invalid --target: " + spec + " (expected name=uri)");
            uris.put(spec.substring(0, eq), URI.create(spec.substring(eq + 1)));
        }

        // Parse per-target transforms
        Map<String, IJsonTransformer> reqTransforms = new LinkedHashMap<>();
        Map<String, IJsonTransformer> respTransforms = new LinkedHashMap<>();
        for (String spec : params.targetTransforms) {
            int eq = spec.indexOf('=');
            if (eq <= 0) throw new ParameterException("Invalid --targetTransform: " + spec);
            String name = spec.substring(0, eq);
            if (!uris.containsKey(name)) throw new ParameterException("Unknown target in --targetTransform: " + name);
            for (String part : spec.substring(eq + 1).split(",")) {
                if (part.startsWith("request:")) {
                    reqTransforms.put(name, loadTransformer(
                        part.substring("request:".length()), params.watchTransforms, watchedTransforms));
                } else if (part.startsWith("response:")) {
                    respTransforms.put(name, loadTransformer(
                        part.substring("response:".length()), params.watchTransforms, watchedTransforms));
                } else {
                    throw new ParameterException("Invalid transform part: " + part + " (expected request:file or response:file)");
                }
            }
        }

        // Parse per-target auth
        Map<String, Supplier<ChannelHandler>> authHandlers = new LinkedHashMap<>();
        for (String spec : params.targetAuths) {
            int eq = spec.indexOf('=');
            if (eq <= 0) throw new ParameterException("Invalid --targetAuth: " + spec);
            String name = spec.substring(0, eq);
            if (!uris.containsKey(name)) throw new ParameterException("Unknown target in --targetAuth: " + name);
            authHandlers.put(name, parseAuthSpec(spec.substring(eq + 1)));
        }

        // Assemble targets
        Map<String, Target> targets = new LinkedHashMap<>();
        for (var entry : uris.entrySet()) {
            String name = entry.getKey();
            targets.put(name, new Target(name, entry.getValue(),
                reqTransforms.get(name), respTransforms.get(name), authHandlers.get(name)));
        }
        return targets;
    }

    static Set<String> parseActiveTargets(Parameters params, Map<String, Target> targets) {
        if (params.active == null || params.active.isEmpty()) return new LinkedHashSet<>(targets.keySet());
        Set<String> active = new LinkedHashSet<>();
        for (String name : params.active.split(",")) {
            name = name.trim();
            if (!targets.containsKey(name)) throw new ParameterException("Unknown target in --active: " + name);
            active.add(name);
        }
        return active;
    }

    static List<ValidationRule> parseValidators(Parameters params) throws IOException {
        List<ValidationRule> rules = new ArrayList<>();
        for (String spec : params.validators) {
            rules.add(parseValidatorSpec(spec));
        }
        return rules;
    }

    /**
     * Parse a validator spec string. Formats:
     * <ul>
     *   <li>field-equality:targetA,targetB:ignore=path1,path2</li>
     *   <li>doc-count:targetA,targetB:assert=a&lt;=b</li>
     *   <li>doc-ids:targetA,targetB[:ordered]</li>
     *   <li>js:targetA,targetB:script=file.js</li>
     * </ul>
     */
    static ValidationRule parseValidatorSpec(String spec) throws IOException {
        String[] parts = spec.split(":", 3);
        if (parts.length < 2) throw new ParameterException("Invalid --validator: " + spec);
        String type = parts[0];
        String[] targetNames = parts[1].split(",");
        if (targetNames.length < 2) throw new ParameterException("Validator needs at least 2 targets: " + spec);
        String options = parts.length > 2 ? parts[2] : "";

        return switch (type) {
            case "field-equality" -> {
                Set<String> ignored = new LinkedHashSet<>();
                if (options.startsWith("ignore=")) {
                    for (String path : options.substring("ignore=".length()).split(",")) {
                        ignored.add(path.trim());
                    }
                }
                yield new ValidationRule("field-equality", List.of(targetNames),
                    new FieldIgnoringEquality(targetNames[0], targetNames[1], ignored));
            }
            case "doc-count" -> {
                DocCountValidator.Comparison cmp = parseComparison(options, targetNames[0], targetNames[1]);
                yield new ValidationRule("doc-count", List.of(targetNames),
                    new DocCountValidator(targetNames[0], targetNames[1], cmp));
            }
            case "doc-ids" -> {
                boolean ordered = "ordered".equals(options);
                yield new ValidationRule("doc-ids", List.of(targetNames),
                    new DocIdValidator(targetNames[0], targetNames[1], ordered));
            }
            case "js" -> {
                if (!options.startsWith("script=")) {
                    throw new ParameterException("js validator requires script=file.js: " + spec);
                }
                String script = Files.readString(Path.of(options.substring("script=".length())));
                yield new ValidationRule("js", List.of(targetNames),
                    new JavascriptValidator("js(" + targetNames[0] + "," + targetNames[1] + ")", script));
            }
            default -> throw new ParameterException("Unknown validator type: " + type);
        };
    }

    private static DocCountValidator.Comparison parseComparison(String options, String a, String b) {
        if (!options.startsWith("assert=")) {
            throw new ParameterException("doc-count validator requires assert= option");
        }
        String assertion = options.substring("assert=".length());
        if (assertion.equals(a + "<=" + b)) return DocCountValidator.Comparison.A_LESS_OR_EQUAL;
        if (assertion.equals(a + ">=" + b)) return DocCountValidator.Comparison.A_GREATER_OR_EQUAL;
        if (assertion.equals(a + "==" + b)) return DocCountValidator.Comparison.EQUAL;
        throw new ParameterException("Invalid doc-count assertion: " + assertion
            + " (expected " + a + "<=|>=|==" + b + ")");
    }

    private static Supplier<ChannelHandler> parseAuthSpec(String authSpec) {
        if ("none".equals(authSpec)) return null;
        if (authSpec.startsWith("sigv4:")) {
            String[] sr = authSpec.substring("sigv4:".length()).split(",", 2);
            if (sr.length != 2) throw new ParameterException("sigv4 auth requires service,region: " + authSpec);
            var credentials = DefaultCredentialsProvider.create();
            String service = sr[0].trim();
            String region = sr[1].trim();
            return () -> new SigV4SigningHandler(credentials, service, region, "https");
        }
        if (authSpec.startsWith("basic:")) {
            String[] up = authSpec.substring("basic:".length()).split(":", 2);
            if (up.length != 2) throw new ParameterException("basic auth requires user:pass: " + authSpec);
            String headerValue = "Basic " + Base64.getEncoder().encodeToString(
                (up[0] + ":" + up[1]).getBytes(StandardCharsets.UTF_8));
            return () -> new BasicAuthSigningHandler(headerValue);
        }
        if (authSpec.startsWith("header:")) {
            String headerValue = authSpec.substring("header:".length());
            return () -> new BasicAuthSigningHandler(headerValue);
        }
        throw new ParameterException("Unknown auth type: " + authSpec);
    }

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
        "  URLSearchParams.prototype.getAll = function(k) { return this._map[k] || []; };\n" +
        "  URLSearchParams.prototype.forEach = function(cb) { for (var k in this._map) { this._map[k].forEach(function(v) { cb(v, k); }); } };\n" +
        "}\n";

    private static IJsonTransformer loadTransformer(String pathStr, boolean watch,
            Map<Path, ReloadableTransformer> watchedTransforms) throws IOException {
        Path path = Path.of(pathStr).toAbsolutePath();
        String script = JS_POLYFILL + Files.readString(path);
        if (watch) {
            var reloadable = new ReloadableTransformer(
                () -> new JavascriptTransformer(script, new java.util.LinkedHashMap<>()));
            watchedTransforms.put(path, reloadable);
            return reloadable;
        }
        return new JavascriptTransformer(script, new java.util.LinkedHashMap<>());
    }
}
