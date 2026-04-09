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

        @Parameter(names = {"--help", "-h"}, help = true, description = "Show usage.")
        public boolean help;

        public RequestTransformationParams requestTransformationParams = new RequestTransformationParams();
        public ResponseTransformationParams responseTransformationParams = new ResponseTransformationParams();
    }

    public static void main(String[] args) throws Exception {
        var params = new Parameters();
        var jCommander = JCommander.newBuilder()
            .addObject(params)
            .addObject(params.requestTransformationParams)
            .addObject(params.responseTransformationParams)
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

        Map<String, Target> targets = parseTargets(params);
        Set<String> activeTargets = parseActiveTargets(params, targets);
        List<ValidationRule> validators = parseValidators(params);

        var otelSdk = RootOtelContext.initializeOpenTelemetryWithCollectorOrAsNoop(
            params.otelCollectorEndpoint, "shimProxy", "shim-" + params.listenPort);
        var rootContext = new RootShimProxyContext(otelSdk,
            new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType()));

        var proxy = new ShimProxy(
            params.listenPort, targets, params.primary, activeTargets, validators,
            null, params.insecureBackend, Duration.ofMillis(params.timeoutMs), params.maxContentLength,
            rootContext);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                log.info("Shutdown signal received, stopping proxy...");
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

    /**
     * Create a transformer from a TransformerParams config using TransformationLoader.
     * This is the same mechanism the traffic replayer uses.
     */
    static IJsonTransformer createTransformer(TransformerParams transformerParams) {
        String configStr = TransformerConfigUtils.getTransformerConfig(transformerParams);
        if (configStr == null) {
            return null;
        }
        log.info("Creating transformer from config: {}", configStr);
        return new TransformationLoader().getTransformerFactoryLoader(configStr);
    }

    static Map<String, Target> parseTargets(Parameters params) {
        // Parse base targets: name=uri
        Map<String, URI> uris = new LinkedHashMap<>();
        for (String spec : params.targets) {
            int eq = spec.indexOf('=');
            if (eq <= 0) throw new ParameterException("Invalid --target: " + spec + " (expected name=uri)");
            uris.put(spec.substring(0, eq), URI.create(spec.substring(eq + 1)));
        }

        // Create transformers via TransformationLoader (same as replayer)
        IJsonTransformer requestTransform = createTransformer(params.requestTransformationParams);
        IJsonTransformer responseTransform = createTransformer(params.responseTransformationParams);

        // Determine which target gets transforms
        String transformTargetName = params.transformTarget;
        if (transformTargetName == null) {
            // Default: apply to all non-primary targets
            transformTargetName = uris.keySet().stream()
                .filter(name -> !name.equals(params.primary))
                .findFirst().orElse(null);
        }
        if (transformTargetName != null && !uris.containsKey(transformTargetName)) {
            throw new ParameterException("Unknown target in --transformTarget: " + transformTargetName);
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
            boolean isTransformTarget = name.equals(transformTargetName);
            targets.put(name, new Target(name, entry.getValue(),
                isTransformTarget ? requestTransform : null,
                isTransformTarget ? responseTransform : null,
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
