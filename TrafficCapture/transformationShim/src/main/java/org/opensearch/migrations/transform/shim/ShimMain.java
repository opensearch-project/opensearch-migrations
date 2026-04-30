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

import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.TransformationLoader;
import org.opensearch.migrations.transform.TransformerConfigUtils;
import org.opensearch.migrations.transform.TransformerParams;
import org.opensearch.migrations.transform.shim.netty.BasicAuthSigningHandler;
import org.opensearch.migrations.transform.shim.netty.SigV4SigningHandler;
import org.opensearch.migrations.transform.shim.reporting.FileSystemReportingSink;
import org.opensearch.migrations.transform.shim.reporting.MetricsReceiver;
import org.opensearch.migrations.transform.shim.reporting.ReportingSink;
import org.opensearch.migrations.transform.shim.reporting.SolrMetricsExtractor;
import org.opensearch.migrations.transform.shim.tracing.RootShimProxyContext;
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
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

/**
 * CLI entry point for the multi-target validation shim proxy.
 *
 * <p>Uses the same {@code --transformerConfig} pattern as the traffic replayer.
 * Transforms are created via {@link TransformationLoader} and ServiceLoader-discovered
 * providers (e.g. {@link SolrTransformerProvider}).
 *
 * <p>Example usage:
 * <pre>{@code
 * --listenPort 8080 \
 * --target solr=http://solr:8983 \
 * --target opensearch=https://opensearch:9200 \
 * --primary solr \
 * --transformerConfig '[{"SolrTransformerProvider":{"initializationScriptFile":"/transforms/request.js","bindingsObject":"{}"}}]' \
 * --responseTransformerConfig '[{"SolrTransformerProvider":{"initializationScriptFile":"/transforms/response.js","bindingsObject":"{}"}}]' \
 * --targetAuth opensearch=sigv4:es,us-east-1 \
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

    @Getter
    public static class RequestTransformationParams implements TransformerParams {
        @Override
        public String getTransformerConfigParameterArgPrefix() { return ""; }

        @Parameter(names = {"--transformer-config", "--transformerConfig"},
            description = "Request transformer config JSON array (same format as traffic replayer). "
                + "Example: '[{\"SolrTransformerProvider\":{\"initializationScriptFile\":\"/path/to/request.js\",\"bindingsObject\":\"{}\"}}]'")
        String transformerConfig;

        @Parameter(names = {"--transformer-config-encoded", "--transformerConfigEncoded"},
            description = "Base64-encoded request transformer config.")
        String transformerConfigEncoded;

        @Parameter(names = {"--transformer-config-file", "--transformerConfigFile"},
            description = "Path to JSON file containing request transformer config.")
        String transformerConfigFile;
    }

    @Getter
    public static class ResponseTransformationParams implements TransformerParams {
        private static final String PREFIX = "response-";
        private static final String CAMEL_PREFIX = "response";

        @Override
        public String getTransformerConfigParameterArgPrefix() { return PREFIX; }

        @Parameter(names = {"--" + PREFIX + "transformer-config", "--" + CAMEL_PREFIX + "TransformerConfig"},
            description = "Response transformer config JSON array (same format as request transformer config).")
        String transformerConfig;

        @Parameter(names = {"--" + PREFIX + "transformer-config-encoded", "--" + CAMEL_PREFIX + "TransformerConfigEncoded"},
            description = "Base64-encoded response transformer config.")
        String transformerConfigEncoded;

        @Parameter(names = {"--" + PREFIX + "transformer-config-file", "--" + CAMEL_PREFIX + "TransformerConfigFile"},
            description = "Path to JSON file containing response transformer config.")
        String transformerConfigFile;
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

        @Parameter(names = {"--transformTarget"},
            description = "Name of the target to apply transforms to. Defaults to all non-primary targets.")
        public String transformTarget;

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

        @Parameter(names = {"--maxContentLength"},
            description = "Maximum HTTP content length in bytes (default 10MB).")
        public int maxContentLength = 10 * 1024 * 1024;

        @Parameter(names = {"--healthPort"},
            description = "Port for the health check endpoint. If not set, no health server is started.")
        public int healthPort = -1;

        @Parameter(names = {"--otelCollectorEndpoint"},
            description = "OpenTelemetry Collector endpoint URL (e.g. http://localhost:4317). "
                + "If not set, instrumentation runs in no-op mode.")
        public String otelCollectorEndpoint;

        @Parameter(names = {"--watchTransforms"},
            description = "Watch transform JS files for changes and hot-reload them.")
        public boolean watchTransforms;

        @Parameter(names = {"--help", "-h"}, help = true, description = "Show usage.")
        public boolean help;

        public RequestTransformationParams requestTransformationParams = new RequestTransformationParams();
        public ResponseTransformationParams responseTransformationParams = new ResponseTransformationParams();
        public ReportingParams reportingParams = new ReportingParams();
    }

    /**
     * CLI parameters for the validation reporting framework.
     * Uses a JSON config string, consistent with {@code --transformerConfig}.
     *
     * <p>Example:
     * <pre>{@code
     * --reportingConfig '[{"FileSystemReportingSink":{"outputDir":"/var/shim/reports","bufferSize":2048,"includeRequestBody":true}}]'
     * }</pre>
     *
     * <p>Currently supports {@code FileSystemReportingSink} as the only provider.
     * When {@code --reportingConfig} is omitted, reporting is disabled entirely.
     */
    public static class ReportingParams {
        @Parameter(names = {"--reportingConfig", "--reporting-config"},
            description = "Reporting config JSON array (same format as --transformerConfig). "
                + "Example: '[{\"FileSystemReportingSink\":{\"outputDir\":\"/var/shim/reports\"}}]'")
        public String reportingConfig;
    }

    public static void main(String[] args) throws Exception {
        var params = new Parameters();
        var jCommander = JCommander.newBuilder()
            .addObject(params)
            .addObject(params.requestTransformationParams)
            .addObject(params.responseTransformationParams)
            .addObject(params.reportingParams)
            .build();
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

        var otelSdk = RootOtelContext.initializeOpenTelemetryWithCollectorOrAsNoop(
            params.otelCollectorEndpoint, "shimProxy", "shim-" + params.listenPort);
        var rootContext = new RootShimProxyContext(otelSdk,
            new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType()));

        // Build reporting components if configured
        var reporting = buildReporting(params.reportingParams.reportingConfig);

        var proxy = new ShimProxy(
            params.listenPort, targets, params.primary, activeTargets, validators,
            null, params.insecureBackend, Duration.ofMillis(params.timeoutMs), params.maxContentLength,
            rootContext, reporting.metricsReceiver, reporting.reportingSink);

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
        if (params.healthPort > 0) {
            proxy.startHealthServer(params.healthPort);
        }
        log.info("Shim running on port {}", params.listenPort);
        proxy.waitForClose();
    }

    /** Holds the reporting components built from config. Both fields are null when reporting is disabled. */
    record ReportingComponents(ReportingSink reportingSink, MetricsReceiver metricsReceiver) {
        static final ReportingComponents DISABLED = new ReportingComponents(null, null);
    }

    /** Build reporting components from the config string, or return disabled if null. */
    static ReportingComponents buildReporting(String reportingConfigJson) {
        if (reportingConfigJson == null) {
            return ReportingComponents.DISABLED;
        }
        var parsed = parseReportingConfig(reportingConfigJson);
        var sink = createReportingSink(parsed.getKey(), parsed.getValue());
        var receiver = createMetricsReceiver(parsed.getValue(), sink);
        log.info("Reporting enabled: {}", reportingConfigJson);
        return new ReportingComponents(sink, receiver);
    }

    /**
     * Parse the {@code --reportingConfig} JSON array into a provider name and config map.
     * Expects format: {@code [{"ProviderName": {...}}]}
     */
    @SuppressWarnings("unchecked")
    static Map.Entry<String, Map<String, Object>> parseReportingConfig(String json) {
        try {
            var entries = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, List.class);
            if (entries.isEmpty()) {
                throw new ParameterException("--reportingConfig array must not be empty");
            }
            var first = entries.get(0);
            if (!(first instanceof Map)) {
                throw new ParameterException("--reportingConfig entries must be JSON objects");
            }
            var providerMap = (Map<String, Object>) first;
            if (providerMap.size() != 1) {
                throw new ParameterException(
                    "--reportingConfig entry must have exactly one provider key");
            }
            var providerEntry = providerMap.entrySet().iterator().next();
            if (!(providerEntry.getValue() instanceof Map)) {
                throw new ParameterException(
                    "--reportingConfig provider value must be a JSON object");
            }
            return Map.entry(providerEntry.getKey(), (Map<String, Object>) providerEntry.getValue());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ParameterException("Invalid --reportingConfig JSON: " + e.getMessage());
        }
    }

    /** Create a {@link ReportingSink} for the given provider name and config. */
    static ReportingSink createReportingSink(String providerName, Map<String, Object> config) {
        if (!"FileSystemReportingSink".equals(providerName)) {
            throw new ParameterException("Unknown reporting provider: " + providerName
                + ". Supported: FileSystemReportingSink");
        }
        if (!config.containsKey("outputDir") || !(config.get("outputDir") instanceof String)) {
            throw new ParameterException(
                "--reportingConfig requires \"outputDir\" key with a string value");
        }
        return new FileSystemReportingSink(
            Path.of((String) config.get("outputDir")),
            config.containsKey("bufferSize") ? ((Number) config.get("bufferSize")).intValue() : 1024);
    }

    /** Create a {@link MetricsReceiver} from a parsed config map and a sink. */
    static MetricsReceiver createMetricsReceiver(Map<String, Object> config, ReportingSink sink) {
        return new MetricsReceiver(sink, new SolrMetricsExtractor(),
            Boolean.TRUE.equals(config.get("includeRequestBody")),
            Boolean.TRUE.equals(config.get("includeResponseBody")));
    }

    /**
     * Create a transformer from a TransformerParams config using TransformationLoader.
     * This is the same mechanism the traffic replayer uses.
     * If watch is true, wraps in ReloadableTransformer and registers script file paths.
     */
    static IJsonTransformer createTransformer(TransformerParams transformerParams,
            boolean watch, Map<Path, ReloadableTransformer> watchedTransforms) {
        String configStr = TransformerConfigUtils.getTransformerConfig(transformerParams);
        if (configStr == null || configStr.isBlank()) return null;
        log.info("Creating transformer from config: {}", configStr);
        IJsonTransformer transformer = new TransformationLoader().getTransformerFactoryLoader(configStr);
        if (watch) {
            var bindings = extractBindings(configStr);
            var reloadable = new ReloadableTransformer(() -> transformer, bindings);
            extractScriptFilePaths(configStr).forEach(p -> watchedTransforms.put(p, reloadable));
            return reloadable;
        }
        return transformer;
    }

    /** Extract initializationScriptFile paths from a transformer config JSON string. */
    @SuppressWarnings("unchecked")
    static List<Path> extractScriptFilePaths(String configStr) {
        List<Path> paths = new ArrayList<>();
        try {
            var configs = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(configStr, List.class);
            for (Object entry : configs) {
                if (entry instanceof Map<?, ?> map) {
                    for (Object providerConfig : map.values()) {
                        if (providerConfig instanceof Map<?, ?> pc) {
                            var scriptFile = pc.get("initializationScriptFile");
                            if (scriptFile instanceof String sf) {
                                paths.add(Path.of(sf).toAbsolutePath());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not extract script file paths for watch mode: {}", e.getMessage());
        }
        return paths;
    }

    /** Extract bindings from a transformer config JSON, including solrConfig from XML if configured. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> extractBindings(String configStr) {
        var bindings = new LinkedHashMap<String, Object>();
        try {
            var configs = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(configStr, List.class);
            for (Object entry : configs) {
                if (entry instanceof Map<?, ?> map) {
                    for (Object providerConfig : map.values()) {
                        if (providerConfig instanceof Map<?, ?> pc) {
                            var bindingsObj = pc.get("bindingsObject");
                            if (bindingsObj instanceof String bs && !bs.isBlank()) {
                                bindings.putAll(new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readValue(bs, Map.class));
                            }
                            var xmlFile = pc.get(SolrTransformerProvider.SOLR_CONFIG_XML_FILE_KEY);
                            if (xmlFile instanceof String xf && !xf.isBlank()) {
                                var solrConfig = SolrConfigProvider.fromXmlFile(Path.of(xf));
                                if (!solrConfig.isEmpty()) bindings.put("solrConfig", solrConfig);
                            }
                            var schemaFile = pc.get(SolrTransformerProvider.SOLR_SCHEMA_XML_FILE_KEY);
                            if (schemaFile instanceof String sf && !sf.isBlank()) {
                                var fieldTypes = SolrSchemaProvider.fromXmlFile(Path.of(sf));
                                if (!fieldTypes.isEmpty()) bindings.put("fieldTypes", fieldTypes);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not extract bindings for watch mode: {}", e.getMessage());
        }
        return bindings;
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

        // Resolve which target gets transforms
        String transformTargetName = params.transformTarget;
        if (transformTargetName == null) {
            transformTargetName = uris.keySet().stream()
                .filter(n -> !n.equals(params.primary))
                .findFirst().orElse(null);
        }
        if (transformTargetName != null && !uris.containsKey(transformTargetName)) {
            throw new ParameterException("Unknown target in --transformTarget: " + transformTargetName);
        }

        // Create transformers via TransformationLoader (same as replayer)
        IJsonTransformer reqTransform = createTransformer(
            params.requestTransformationParams, params.watchTransforms, watchedTransforms);
        IJsonTransformer respTransform = createTransformer(
            params.responseTransformationParams, params.watchTransforms, watchedTransforms);

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
            boolean isTransformTarget = name.equals(transformTargetName);
            targets.put(name, new Target(name, entry.getValue(),
                isTransformTarget ? reqTransform : null,
                isTransformTarget ? respTransform : null,
                authHandlers.get(name)));
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
}
