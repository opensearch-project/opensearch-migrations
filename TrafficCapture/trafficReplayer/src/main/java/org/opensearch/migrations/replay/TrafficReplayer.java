package org.opensearch.migrations.replay;

import java.lang.ref.WeakReference;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.jcommander.NoSplitter;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamLimiter;
import org.opensearch.migrations.replay.util.ActiveContextMonitor;
import org.opensearch.migrations.replay.util.OrderedWorkerTracker;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.RemovingAuthTransformerFactory;
import org.opensearch.migrations.transform.SigV4AuthTransformerFactory;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.opensearch.migrations.transform.TransformationLoader;
import org.opensearch.migrations.transform.TransformerConfigUtils;
import org.opensearch.migrations.transform.TransformerParams;
import org.opensearch.migrations.utils.ProcessHelpers;
import org.opensearch.migrations.utils.TrackedFutureJsonFormatter;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.ParametersDelegate;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import software.amazon.awssdk.arns.Arn;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;

@Slf4j
public class TrafficReplayer {
    private static final String ALL_ACTIVE_CONTEXTS_MONITOR_LOGGER = "AllActiveWorkMonitor";

    public static final String SIGV_4_AUTH_HEADER_SERVICE_REGION_ARG = "--sigv4-auth-header-service-region";
    public static final String AUTH_HEADER_VALUE_ARG = "--auth-header-value";
    public static final String REMOVE_AUTH_HEADER_VALUE_ARG = "--remove-auth-header";
    public static final String AWS_AUTH_HEADER_USER_AND_SECRET_ARG = "--auth-header-user-and-secret";
    public static final String PACKET_TIMEOUT_SECONDS_PARAMETER_NAME = "--packet-timeout-seconds";

    public static final String LOOKAHEAD_TIME_WINDOW_PARAMETER_NAME = "--lookahead-time-window";
    private static final long ACTIVE_WORK_MONITOR_CADENCE_MS = 30 * 1000L;

    public static class DualException extends Exception {
        public final Throwable originalCause;
        public final Throwable immediateCause;

        public DualException(Throwable originalCause, Throwable immediateCause) {
            this(null, originalCause, immediateCause);
        }

        // use one of these two so that anybody handling this as any other exception can get
        // at least one of the root errors
        public DualException(String message, Throwable originalCause, Throwable immediateCause) {
            super(message, Optional.ofNullable(originalCause).orElse(immediateCause));
            this.originalCause = originalCause;
            this.immediateCause = immediateCause;
        }
    }

    public static class TerminationException extends DualException {
        public TerminationException(Throwable originalCause, Throwable immediateCause) {
            super(originalCause, immediateCause);
        }
    }

    public static boolean validateRequiredKafkaParams(String brokers, String topic, String groupId) {
        if (brokers == null && topic == null && groupId == null) {
            return false;
        }
        if (brokers == null || topic == null || groupId == null) {
            throw new ParameterException(
                "To enable a Kafka traffic source, the following parameters are required "
                    + "[--kafka-traffic-brokers, --kafka-traffic-topic, --kafka-traffic-group-id]"
            );
        }
        return true;
    }

    public static class Parameters {
        @Parameter(
            required = true,
            arity = 1,
            description = "URI of the target cluster/domain")
        String targetUriString;
        @Parameter(
            required = false,
            names = {"--insecure" },
            arity = 0, description = "Do not check the server's certificate")
        boolean allowInsecureConnections;

        @Parameter(
            required = false,
            names = {REMOVE_AUTH_HEADER_VALUE_ARG },
            arity = 0, description = "Remove the authorization header if present and do not replace it with anything.  "
                + "(cannot be used with other auth arguments)")
        boolean removeAuthHeader;
        @Parameter(
            required = false,
            names = { AUTH_HEADER_VALUE_ARG },
            arity = 1, description = "Static value to use for the \"authorization\" header of each request "
                + "(cannot be used with other auth arguments)")
        String authHeaderValue;
        @Parameter(
            required = false, names = {
            AWS_AUTH_HEADER_USER_AND_SECRET_ARG },
            splitter = NoSplitter.class,
            arity = 2,
            description = "<USERNAME> <SECRET_ARN> pair to specify "
                + "\"authorization\" header value for each request.  "
                + "The USERNAME specifies the plaintext user and the SECRET_ARN specifies the ARN or "
                + "Secret name from AWS Secrets Manager to retrieve the password from for the password section"
                + "(cannot be used with other auth arguments)")
        List<String> awsAuthHeaderUserAndSecret;
        @Parameter(
            required = false,
            names = { SIGV_4_AUTH_HEADER_SERVICE_REGION_ARG },
            arity = 1,
            description = "Use AWS SigV4 to sign each request with the specified service name and region.  "
                + "(e.g. es,us-east-1)  "
                + "DefaultCredentialsProvider is used to resolve credentials.  "
                + "(cannot be used with other auth arguments)")
        String useSigV4ServiceAndRegion;

        @ParametersDelegate
        private RequestTransformationParams requestTransformationParams = new RequestTransformationParams();

        @ParametersDelegate
        private TupleTransformationParams tupleTransformationParams = new TupleTransformationParams();

        @Parameter(
            required = false,
            names = "--user-agent",
            arity = 1,
            description = "For HTTP requests to the target cluster, append this string (after \"; \") to"
            + "the existing user-agent field or if the field wasn't present, simply use this value")
        String userAgent;

        @Parameter(
            required = false,
            names = { "-i", "--input" },
            arity = 1,
            description = "input file to read the request/response traces for the source cluster")
        String inputFilename;
        @Parameter(
            required = false,
            names = {"-t", PACKET_TIMEOUT_SECONDS_PARAMETER_NAME },
            arity = 1,
            description = "assume that connections were terminated after this many "
                + "seconds of inactivity observed in the captured stream")
        int observedPacketConnectionTimeout = 70;
        @Parameter(
            required = false,
            names = { "--speedup-factor" },
            arity = 1, description = "Accelerate the replayed communications by this factor.  "
                + "This means that between each interaction will be replayed at this rate faster "
                + "than the original observations, provided that the replayer and target are able to keep up.")
        double speedupFactor = 1.0;
        @Parameter(
            required = false,
            names = { LOOKAHEAD_TIME_WINDOW_PARAMETER_NAME },
            arity = 1,
            description = "Number of seconds of data that will be buffered.")
        int lookaheadTimeSeconds = 300;
        @Parameter(
            required = false,
            names = { "--max-concurrent-requests" },
            arity = 1,
            description = "Maximum number of requests at a time that can be outstanding")
        int maxConcurrentRequests = 1024;
        @Parameter(
            required = false,
            names = { "--num-client-threads" },
            arity = 1,
            description = "Number of threads to use to send requests from.")
        int numClientThreads = 0;

        // https://github.com/opensearch-project/opensearch-java/blob/main/java-client/src/main/java/org/opensearch/client/transport/httpclient5/ApacheHttpClient5TransportBuilder.java#L49-L54
        @Parameter(
            required = false,
            names = { "--target-response-timeout" },
            arity = 1,
            description = "Seconds to wait before timing out a replayed request to the target.")
        int targetServerResponseTimeoutSeconds = 30;

        @Parameter(
            required = false,
            names = { "--kafka-traffic-brokers" },
            arity = 1,
            description = "Comma-separated list of host and port pairs that are the addresses of the Kafka brokers " +
                "to bootstrap with i.e. 'kafka-1:9092,kafka-2:9092'")
        String kafkaTrafficBrokers;
        @Parameter(
            required = false,
            names = { "--kafka-traffic-topic" },
            arity = 1,
            description = "Topic name used to pull messages from Kafka")
        String kafkaTrafficTopic;
        @Parameter(
            required = false,
            names = { "--kafka-traffic-group-id" },
            arity = 1,
            description = "Consumer group id that is used when pulling messages from Kafka")
        String kafkaTrafficGroupId;
        @Parameter(
            required = false,
            names = { "--kafka-traffic-enable-msk-auth" },
            arity = 0,
            description = "Enables SASL properties required for connecting to MSK with IAM auth")
        boolean kafkaTrafficEnableMSKAuth;
        @Parameter(
            required = false,
            names = { "--kafka-traffic-property-file" },
            arity = 1,
            description = "File path for Kafka properties file to use for additional or overriden Kafka properties")
        String kafkaTrafficPropertyFile;

        @Parameter(
            required = false,
            names = { "--otelCollectorEndpoint" },
            arity = 1,
            description = "Endpoint (host:port) for the OpenTelemetry Collector to which metrics logs should be"
                + "forwarded. If no value is provided, metrics will not be forwarded.")
        String otelCollectorEndpoint;
    }

    @Getter
    public static class RequestTransformationParams implements TransformerParams {
        @Override
        public String getTransformerConfigParameterArgPrefix() {
            return REQUEST_TRANSFORMER_ARG_PREFIX;
        }
        private final static String REQUEST_TRANSFORMER_ARG_PREFIX = "";

        @Parameter(
            required = false,
            names = "--" + REQUEST_TRANSFORMER_ARG_PREFIX + "transformer-config-encoded",
            arity = 1,
            description = "Configuration of message transformers.  The same contents as --transformer-config but " +
                "Base64 encoded so that the configuration is easier to pass as a command line parameter.")
        private String transformerConfigEncoded;

        @Parameter(
            required = false,
            names = "--" + REQUEST_TRANSFORMER_ARG_PREFIX + "transformer-config",
            arity = 1,
            description = "Configuration of message transformers.  Either as a string that identifies the "
                + "transformer that should be run (with default settings) or as json to specify options "
                + "as well as multiple transformers to run in sequence.  "
                + "For json, keys are the (simple) names of the loaded transformers and values are the "
                + "configuration passed to each of the transformers.")
        private String transformerConfig;

        @Parameter(
            required = false,
            names = "--" + REQUEST_TRANSFORMER_ARG_PREFIX + "transformer-config-file",
            arity = 1,
            description = "Path to the JSON configuration file of message transformers.")
        private String transformerConfigFile;
    }

    @Getter
    public static class TupleTransformationParams implements TransformerParams {
        public String getTransformerConfigParameterArgPrefix() {
            return TUPLE_TRANSFORMER_CONFIG_PARAMETER_ARG_PREFIX;
        }
        final static String TUPLE_TRANSFORMER_CONFIG_PARAMETER_ARG_PREFIX = "tuple-";

        @Parameter(
            required = false,
            names = "--" + TUPLE_TRANSFORMER_CONFIG_PARAMETER_ARG_PREFIX + "transformer-config-base64",
            arity = 1,
            description = "Configuration of tuple transformers.  The same contents as --tuple-transformer-config but " +
                "Base64 encoded so that the configuration is easier to pass as a command line parameter.")
        private String transformerConfigEncoded;

        @Parameter(
            required = false,
            names = "--" + TUPLE_TRANSFORMER_CONFIG_PARAMETER_ARG_PREFIX + "transformer-config",
            arity = 1,
            description = "Configuration of tuple transformers.  Either as a string that identifies the "
                + "transformer that should be run (with default settings) or as json to specify options "
                + "as well as multiple transformers to run in sequence.  "
                + "For json, keys are the (simple) names of the loaded transformers and values are the "
                + "configuration passed to each of the transformers.")
        private String transformerConfig;

        @Parameter(
            required = false,
            names = "--" + TUPLE_TRANSFORMER_CONFIG_PARAMETER_ARG_PREFIX + "transformer-config-file",
            arity = 1,
            description = "Path to the JSON configuration file of tuple transformers.")
        private String transformerConfigFile;
    }


    private static Parameters parseArgs(String[] args) {
        Parameters p = new Parameters();
        JCommander jCommander = new JCommander(p);
        try {
            jCommander.parse(args);
            return p;
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            System.err.println("Got args: " + String.join("; ", args));
            jCommander.usage();
            System.exit(2);
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        System.err.println("Got args: " + String.join("; ", args));
        final var workerId = ProcessHelpers.getNodeInstanceName();
        log.info("Starting Traffic Replayer with id=" + workerId);

        var activeContextLogger = LoggerFactory.getLogger(ALL_ACTIVE_CONTEXTS_MONITOR_LOGGER);
        var params = parseArgs(args);
        URI uri;
        try {
            uri = new URI(params.targetUriString);
        } catch (Exception e) {
            final var msg = "Exception parsing " + params.targetUriString;
            System.err.println(msg);
            System.err.println(e.getMessage());
            log.atError().setCause(e).setMessage("{}").addArgument(msg).log();
            System.exit(3);
            return;
        }
        if (params.lookaheadTimeSeconds <= params.observedPacketConnectionTimeout) {
            String msg = LOOKAHEAD_TIME_WINDOW_PARAMETER_NAME
                + "("
                + params.lookaheadTimeSeconds
                + ") must be > "
                + PACKET_TIMEOUT_SECONDS_PARAMETER_NAME
                + "("
                + params.observedPacketConnectionTimeout
                + ")";
            System.err.println(msg);
            log.error(msg);
            System.exit(4);
            return;
        }
        var globalContextTracker = new ActiveContextTracker();
        var perContextTracker = new ActiveContextTrackerByActivityType();
        var scheduledExecutorService = Executors.newScheduledThreadPool(
            1,
            new DefaultThreadFactory("activeWorkMonitorThread")
        );
        var contextTrackers = new CompositeContextTracker(globalContextTracker, perContextTracker);
        var topContext = new RootReplayerContext(
            RootOtelContext.initializeOpenTelemetryWithCollectorOrAsNoop(params.otelCollectorEndpoint,
                "replay",
                ProcessHelpers.getNodeInstanceName()),
            contextTrackers
        );

        ActiveContextMonitor activeContextMonitor = null;
        try (
            var blockingTrafficSource = TrafficCaptureSourceFactory.createTrafficCaptureSource(
                topContext,
                params,
                Duration.ofSeconds(params.lookaheadTimeSeconds)
            );
            var authTransformer = buildAuthTransformerFactory(params);
            var trafficStreamLimiter = new TrafficStreamLimiter(params.maxConcurrentRequests)
        ) {
            var timeShifter = new TimeShifter(params.speedupFactor);
            var serverTimeout = Duration.ofSeconds(params.targetServerResponseTimeoutSeconds);

            String requestTransformerConfig = TransformerConfigUtils.getTransformerConfig(params.requestTransformationParams);
            if (requestTransformerConfig != null) {
                log.atInfo().setMessage("Request Transformations config string: {}")
                    .addArgument(requestTransformerConfig).log();
            }

            String tupleTransformerConfig = TransformerConfigUtils.getTransformerConfig(params.tupleTransformationParams);
            if (requestTransformerConfig != null) {
                log.atInfo().setMessage("Tuple Transformations config string: {}")
                    .addArgument(tupleTransformerConfig).log();
            }

            final var orderedRequestTracker = new OrderedWorkerTracker<Void>();
            final var hostname = uri.getHost();

            var tr = new TrafficReplayerTopLevel(
                topContext,
                uri,
                authTransformer,
                new TransformationLoader().getTransformerFactoryLoader(hostname, params.userAgent, requestTransformerConfig),
                TrafficReplayerTopLevel.makeNettyPacketConsumerConnectionPool(
                    uri,
                    params.allowInsecureConnections,
                    params.numClientThreads
                ),
                trafficStreamLimiter,
                orderedRequestTracker
            );
            activeContextMonitor = new ActiveContextMonitor(
                globalContextTracker,
                perContextTracker,
                orderedRequestTracker,
                64,
                cf -> TrackedFutureJsonFormatter.format(cf, TrafficReplayerTopLevel::formatWorkItem),
                activeContextLogger
            );
            ActiveContextMonitor finalActiveContextMonitor = activeContextMonitor;
            scheduledExecutorService.scheduleAtFixedRate(() -> {
                activeContextLogger.atInfo().setMessage("Total requests outstanding at {}: {}")
                    .addArgument(Instant::now)
                    .addArgument(tr.requestWorkTracker::size)
                    .log();
                finalActiveContextMonitor.run();
            }, ACTIVE_WORK_MONITOR_CADENCE_MS, ACTIVE_WORK_MONITOR_CADENCE_MS, TimeUnit.MILLISECONDS);

            setupShutdownHookForReplayer(tr);
            var tupleWriter = new TupleParserChainConsumer(new ResultsToLogsConsumer(null, null,
                new TransformationLoader().getTransformerFactoryLoader(tupleTransformerConfig)));
            tr.setupRunAndWaitForReplayWithShutdownChecks(
                Duration.ofSeconds(params.observedPacketConnectionTimeout),
                serverTimeout,
                blockingTrafficSource,
                timeShifter,
                tupleWriter
            );
            log.info("Done processing TrafficStreams");
        } finally {
            scheduledExecutorService.shutdown();
            if (activeContextMonitor != null) {
                var acmLevel = globalContextTracker.getActiveScopesByAge().findAny().isPresent()
                    ? Level.ERROR
                    : Level.INFO;
                activeContextLogger.atLevel(acmLevel).setMessage("Outstanding work after shutdown...").log();
                activeContextMonitor.run();
                activeContextLogger.atLevel(acmLevel).setMessage("[end of run]]").log();
            }
        }
    }

    private static void setupShutdownHookForReplayer(TrafficReplayerTopLevel tr) {
        var weakTrafficReplayer = new WeakReference<>(tr);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // both Log4J and the java builtin loggers add shutdown hooks.
            // The API for addShutdownHook says that those hooks registered will run in an undetermined order.
            // Hence, the reason that this code logs via slf4j logging AND stderr.
            Optional.of("Running TrafficReplayer Shutdown.  "
                    + "The logging facilities may also be shutting down concurrently, "
                    + "resulting in missing logs messages.")
                .ifPresent(beforeMsg -> {
                    log.atWarn().setMessage(beforeMsg).log();
                    System.err.println(beforeMsg);
                });
            Optional.ofNullable(weakTrafficReplayer.get()).ifPresent(o -> o.shutdown(null));
            Optional.of("Done shutting down TrafficReplayer (due to Runtime shutdown).  "
                    + "Logs may be missing for events that have happened after the Shutdown event was received.")
                .ifPresent(afterMsg -> {
                    log.atWarn().setMessage(afterMsg).log();
                    System.err.println(afterMsg);
                });
        }));
    }

    /**
     * Java doesn't have a notion of constexpr like C++ does, so this cannot be used within the
     * parameters' annotation descriptions, but it's still useful to break the documentation
     * aspect out from the core logic below.
     */
    private static String formatAuthArgFlagsAsString() {
        return String.join(
            ", ",
            REMOVE_AUTH_HEADER_VALUE_ARG,
            AUTH_HEADER_VALUE_ARG,
            AWS_AUTH_HEADER_USER_AND_SECRET_ARG,
            SIGV_4_AUTH_HEADER_SERVICE_REGION_ARG
        );
    }

    private static IAuthTransformerFactory buildAuthTransformerFactory(Parameters params) {
        if (params.removeAuthHeader
            && params.authHeaderValue != null
            && params.useSigV4ServiceAndRegion != null
            && params.awsAuthHeaderUserAndSecret != null) {
            throw new IllegalArgumentException(
                "Cannot specify more than one auth option: " + formatAuthArgFlagsAsString()
            );
        }

        var authHeaderValue = params.authHeaderValue;
        if (params.awsAuthHeaderUserAndSecret != null) {
            if (params.awsAuthHeaderUserAndSecret.size() != 2) {
                throw new ParameterException(
                    AWS_AUTH_HEADER_USER_AND_SECRET_ARG + " must specify two arguments, <USERNAME> <SECRET_ARN>"
                );
            }
            var secretArnStr = params.awsAuthHeaderUserAndSecret.get(1);
            var regionOp = Arn.fromString(secretArnStr).region();
            if (regionOp.isEmpty()) {
                throw new ParameterException(
                    AWS_AUTH_HEADER_USER_AND_SECRET_ARG
                        + " must specify two arguments, <USERNAME> <SECRET_ARN>, and SECRET_ARN must specify a region"
                );
            }
            try (
                var credentialsProvider = DefaultCredentialsProvider.create();
                AWSAuthService awsAuthService = new AWSAuthService(credentialsProvider, Region.of(regionOp.get()))
            ) {
                authHeaderValue = awsAuthService.getBasicAuthHeaderFromSecret(
                    params.awsAuthHeaderUserAndSecret.get(0),
                    secretArnStr
                );
            }
        }

        if (authHeaderValue != null) {
            return new StaticAuthTransformerFactory(authHeaderValue);
        } else if (params.useSigV4ServiceAndRegion != null) {
            var serviceAndRegion = params.useSigV4ServiceAndRegion.split(",");
            if (serviceAndRegion.length != 2) {
                throw new IllegalArgumentException(
                    "Format for "
                        + SIGV_4_AUTH_HEADER_SERVICE_REGION_ARG
                        + " must be "
                        + "'SERVICE_NAME,REGION', such as 'es,us-east-1'"
                );
            }
            String serviceName = serviceAndRegion[0];
            String region = serviceAndRegion[1];

            return new SigV4AuthTransformerFactory(
                DefaultCredentialsProvider.create(),
                serviceName,
                region,
                "https",
                Clock::systemUTC
            );
        } else if (params.removeAuthHeader) {
            return RemovingAuthTransformerFactory.instance;
        } else {
            return null; // default is to do nothing to auth headers
        }
    }
}
