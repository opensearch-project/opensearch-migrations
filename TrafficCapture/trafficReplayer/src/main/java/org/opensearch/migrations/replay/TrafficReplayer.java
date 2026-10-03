package org.opensearch.migrations.replay;

import java.net.URI;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.opensearch.migrations.arguments.ArgLogUtils;
import org.opensearch.migrations.arguments.ArgNameConstants;
import org.opensearch.migrations.jcommander.EnvVarParameterPuller;
import org.opensearch.migrations.jcommander.JsonCommandLineParser;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.http.retries.BulkItemErrorClassifier;
import org.opensearch.migrations.replay.http.retries.OpenSearchDefaultRetry;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.intake.PartitionIntakeState;
import org.opensearch.migrations.replay.kafka.KafkaConsumerProperties;
import org.opensearch.migrations.replay.kafka.KafkaTopicDumper;
import org.opensearch.migrations.replay.lifecycle.RequestReplayOwner;
import org.opensearch.migrations.replay.sink.S3TupleSink;
import org.opensearch.migrations.replay.sink.TupleWriter;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.tracing.OtelCollectorEndpoints;
import org.opensearch.migrations.tracing.RootOtelContext;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.PredicateLoader;
import org.opensearch.migrations.transform.RemovingAuthTransformerFactory;
import org.opensearch.migrations.transform.SigV4AuthTransformerFactory;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.opensearch.migrations.transform.TransformationLoader;
import org.opensearch.migrations.transform.TransformerConfigUtils;
import org.opensearch.migrations.transform.TransformerParams;
import org.opensearch.migrations.utils.ProcessHelpers;
import org.opensearch.migrations.utils.URIHelper;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.ParametersDelegate;
import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

@Slf4j
public class TrafficReplayer {
    public static final String SIGV_4_AUTH_HEADER_SERVICE_REGION_ARG = "--sigv4-auth-header-service-region";
    public static final String REMOVE_AUTH_HEADER_VALUE_ARG = "--remove-auth-header";
    public static final String KAFKA_AUTH_TYPE_NONE = "none";
    public static final String KAFKA_AUTH_TYPE_MSK_IAM = "msk-iam";
    public static final String KAFKA_AUTH_TYPE_SCRAM_SHA_512 = "scram-sha-512";
    private static final String MODE_REPLAY = "replay";

    static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 10000;
    static final int DEFAULT_NUM_CLIENT_THREADS = 0;
    static final int DEFAULT_HEARTBEAT_EXPIRATION_INTERVAL_SECONDS = 30;
    static final int DEFAULT_MAXIMUM_BACKWARD_SKEW_SECONDS = 5;
    static final int DEFAULT_SOURCE_RESPONSE_RETRY_WINDOW_SECONDS = 5;
    static final int DEFAULT_READY_REQUESTS_BUFFER_PER_THREAD = 2;
    static final int DEFAULT_MAXIMUM_RESPONSE_RETRIES = 4;
    static final Duration DEFAULT_KAFKA_POLL_TIMEOUT = Duration.ofSeconds(1);

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
            required = false,
            names = { "--target-uri", "--targetUri" },
            arity = 1,
            description = "URI of the target cluster/domain (required for replay mode)")
        String targetUriString;
        @Parameter(
            required = false,
            names = { "--mode" },
            arity = 1,
            description = "Operating mode: 'replay' (default), 'dump-raw', 'dump-http', or 'dump-both'")
        String mode = MODE_REPLAY;
        @Parameter(
            required = false,
            names = { "--start-offset" },
            arity = 1,
            description = "For dump modes: start reading at this offset on every partition")
        Long startOffset;
        @Parameter(
            required = false,
            names = { "--end-offset" },
            arity = 1,
            description = "For dump modes: stop after passing this offset on every partition")
        Long endOffset;
        @Parameter(
            required = false,
            names = { "--start-time" },
            arity = 1,
            description = "For dump modes: start at the earliest record at or after this epoch-seconds timestamp")
        Long startTime;
        @Parameter(
            required = false,
            names = { "--end-time" },
            arity = 1,
            description = "For dump modes: stop after the first record whose timestamp exceeds this epoch-seconds value")
        Long endTime;
        @Parameter(
            required = false,
            names = { "--preview-bytes-read" },
            arity = 1,
            description = "For dump-raw: max bytes to preview for read observations (default 24)")
        int previewBytesRead = 24;
        @Parameter(
            required = false,
            names = { "--preview-bytes-write" },
            arity = 1,
            description = "For dump-raw: max bytes to preview for write observations (default 24)")
        int previewBytesWrite = 24;
        @Parameter(
            required = false,
            names = {"--insecure" },
            arity = 0, description = "Do not check the server's certificate")
        boolean allowInsecureConnections;
        @Parameter(
                names = {ArgNameConstants.TARGET_USERNAME_ARG_CAMEL_CASE, ArgNameConstants.TARGET_USERNAME_ARG_KEBAB_CASE },
                description = "Username to use for basic auth with the target cluster/domain",
                required = false)
        public String targetUsername;
        @Parameter(
                names = {ArgNameConstants.TARGET_PASSWORD_ARG_CAMEL_CASE, ArgNameConstants.TARGET_PASSWORD_ARG_KEBAB_CASE },
                description = "Password to use for basic auth with the target cluster/domain",
                required = false)
        public String targetPassword;
        @Parameter(
            required = false,
            names = {REMOVE_AUTH_HEADER_VALUE_ARG, "--removeAuthHeader" },
            arity = 0, description = "Remove the authorization header if present and do not replace it with anything.  "
                + "(cannot be used with other auth arguments)")
        boolean removeAuthHeader;
        @Parameter(
            required = false,
            names = { SIGV_4_AUTH_HEADER_SERVICE_REGION_ARG, "--sigv4AuthHeaderServiceRegion" },
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
            names = {"--request-filter-config", "--requestFilterConfig"},
            arity = 1,
            description = "Configuration for request filtering. JSON array with one entry whose key is the "
                + "predicate provider name. Requests failing the predicate are skipped (not sent to target).")
        private String requestFilterConfig;

        @Parameter(
            required = false,
            names = {"--response-post-processor-config", "--responsePostProcessorConfig"},
            arity = 1,
            description = "Configuration for response post-processing. Transforms target responses before "
                + "tuple assembly (e.g., converting OpenSearch responses to Solr format for comparison). "
                + "Same JSON format as --transformerConfig.")
        private String responsePostProcessorConfig;

        @Parameter(
            required = false,
            names = { "--user-agent", "--userAgent" },
            arity = 1,
            description = "For HTTP requests to the target cluster, append this string (after \"; \") to"
            + "the existing user-agent field or if the field wasn't present, simply use this value")
        String userAgent;

        @Parameter(
            required = false,
            names = { "--speedup-factor", "--speedupFactor" },
            arity = 1, description = "Accelerate the replayed communications by this factor.  "
                + "This means that between each interaction will be replayed at this rate faster "
                + "than the original observations, provided that the replayer and target are able to keep up.")
        double speedupFactor = 1.0;
        @Parameter(
            required = false,
            names = { "--maxConcurrentRequests" },
            arity = 1,
            description = "Maximum number of target HTTP requests that can be in flight")
        int maxConcurrentRequests = DEFAULT_MAX_CONCURRENT_REQUESTS;
        @Parameter(
            required = false,
            names = { "--numClientThreads" },
            arity = 1,
            description = "Target Netty event-loop threads. "
                + "A value of 0 uses Netty's default of 2 * available processors.")
        int numClientThreads = DEFAULT_NUM_CLIENT_THREADS;
        @Parameter(
            required = false,
            names = { "--cancellation-grace-ms", "--cancellationGraceMs" },
            arity = 1,
            description = "Milliseconds allowed for generation-revocation cancellation before force")
        long cancellationGraceMs = 1_000;
        @Parameter(
            required = false,
            names = {
                "--heartbeat-expiration-interval-seconds",
                "--heartbeatExpirationIntervalSeconds"
            },
            arity = 1,
            description = "Broker-time heartbeat expiration interval E in seconds")
        int heartbeatExpirationIntervalSeconds =
            DEFAULT_HEARTBEAT_EXPIRATION_INTERVAL_SECONDS;
        @Parameter(
            required = false,
            names = {
                "--maximum-backward-skew-seconds",
                "--maximumBackwardSkewSeconds"
            },
            arity = 1,
            description = "Maximum permitted Kafka broker timestamp regression S in seconds")
        long maximumBackwardSkewSeconds =
            DEFAULT_MAXIMUM_BACKWARD_SKEW_SECONDS;
        @Parameter(
            required = false,
            names = {
                "--source-response-retry-window-seconds",
                "--sourceResponseRetryWindowSeconds"
            },
            arity = 1,
            description = "Broker-time source-response retry window W in seconds")
        int sourceResponseRetryWindowSeconds =
            DEFAULT_SOURCE_RESPONSE_RETRY_WINDOW_SECONDS;
        @Parameter(
            required = false,
            names = {
                "--ready-requests-buffer-per-thread",
                "--readyRequestsBufferPerThread"
            },
            arity = 1,
            description = "Retry-ready request buffer target P per target event-loop thread")
        int readyRequestsBufferPerThread =
            DEFAULT_READY_REQUESTS_BUFFER_PER_THREAD;

        // https://github.com/opensearch-project/opensearch-java/blob/main/java-client/src/main/java/org/opensearch/client/transport/httpclient5/ApacheHttpClient5TransportBuilder.java#L49-L54
        @Parameter(
            required = false,
            names = { "--target-response-timeout", "--targetResponseTimeout",
                "--targetServerResponseTimeoutSeconds" },
            arity = 1,
            description = "Seconds to wait before timing out a replayed request to the target.")
        int targetServerResponseTimeoutSeconds = 150;

        @Parameter(
            required = false,
            names = { "--kafkaBrokers", "--kafka-traffic-brokers", "--kafkaTrafficBrokers" },
            arity = 1,
            description = "Comma-separated list of host and port pairs that are the addresses of the Kafka brokers " +
                "to bootstrap with i.e. 'kafka-1:9092,kafka-2:9092'")
        String kafkaTrafficBrokers;
        @Parameter(
            required = false,
            names = { "--kafkaTopic", "--kafka-traffic-topic", "--kafkaTrafficTopic" },
            arity = 1,
            description = "Topic name used to pull messages from Kafka")
        String kafkaTrafficTopic;
        @Parameter(
            required = false,
            names = { "--kafkaGroupId", "--kafka-traffic-group-id", "--kafkaTrafficGroupId" },
            arity = 1,
            description = "Consumer group id that is used when pulling messages from Kafka")
        String kafkaTrafficGroupId;
        @Parameter(
            required = false,
            names = { "--kafka-traffic-enable-msk-auth", "--kafkaTrafficEnabledMskAuth",
                "--kafkaTrafficEnableMSKAuth" },
            arity = 0,
            description = "Legacy flag that enables MSK IAM auth. Prefer --kafkaAuthType=msk-iam")
        Boolean kafkaTrafficEnableMSKAuth;
        @Parameter(
            required = false,
            names = { "--kafkaPropertyFile", "--kafka-traffic-property-file", "--kafkaTrafficPropertyFile" },
            arity = 1,
            description = "File path for Kafka properties file to use for additional or overriden Kafka properties")
        String kafkaTrafficPropertyFile;
        @Parameter(
            required = false,
            names = { "--kafkaAuthType", "--kafka-traffic-auth-type", "--kafkaTrafficAuthType" },
            arity = 1,
            description = "Kafka client auth mode. Supported values: none, msk-iam, scram-sha-512")
        String kafkaTrafficAuthType;
        @Parameter(
            required = false,
            names = { "--kafkaListenerName", "--kafka-traffic-listener-name", "--kafkaTrafficListenerName" },
            arity = 1,
            description = "Kafka listener name selected by orchestration")
        String kafkaTrafficListenerName;
        @Parameter(
            required = false,
            names = { "--kafkaSecretName", "--kafka-traffic-secret-name", "--kafkaTrafficSecretName" },
            arity = 1,
            description = "Kubernetes Secret containing Kafka client auth material")
        String kafkaTrafficSecretName;
        @Parameter(
            required = false,
            names = { "--kafkaUserName", "--kafka-traffic-user-name", "--kafkaTrafficUserName" },
            arity = 1,
            description = "Kafka user/principal name selected by orchestration")
        String kafkaTrafficUserName;
        @Parameter(
            required = false,
            names = { "--kafkaPassword", "--kafka-traffic-password", "--kafkaTrafficPassword" },
            arity = 1,
            description = "Kafka password for SCRAM auth. Prefer setting via TRAFFIC_REPLAYER_KAFKA_TRAFFIC_PASSWORD env var.")
        String kafkaTrafficPassword;

        @Parameter(
            required = false,
            names = { "--otelTraceCollectorEndpoint", "--otel-trace-collector-endpoint" },
            arity = 1,
            description = "Endpoint for the OpenTelemetry Collector to which traces should be forwarded. " +
                "Omit this option to disable trace export.")
        String otelTraceCollectorEndpoint;

        @Parameter(
            required = false,
            names = { "--otelMetricsCollectorEndpoint", "--otel-metrics-collector-endpoint" },
            arity = 1,
            description = "Endpoint for the OpenTelemetry Collector to which metrics should be forwarded. " +
                "Omit this option to disable metric export.")
        String otelMetricsCollectorEndpoint;

        @Parameter(
            required = false,
            names = { "--tuple-s3-bucket", "--tupleS3Bucket" },
            arity = 1,
            description = "S3 bucket for tuple output. When set, tuples are written directly to S3 via the CRT client.")
        String tupleS3Bucket;

        @Parameter(
            required = false,
            names = { "--tuple-s3-region", "--tupleS3Region" },
            arity = 1,
            description = "AWS region for the tuple S3 bucket. Required when --tuple-s3-bucket is set.")
        String tupleS3Region;

        @Parameter(
            required = false,
            names = { "--tuple-s3-prefix", "--tupleS3Prefix" },
            arity = 1,
            description = "S3 key prefix for tuple objects. Defaults to 'tuples/'.")
        String tupleS3Prefix = "tuples/";

        @Parameter(
            required = false,
            names = { "--tuple-s3-endpoint", "--tupleS3Endpoint" },
            arity = 1,
            description = "Custom S3 endpoint URL (for LocalStack, MinIO, or non-standard S3-compatible services).")
        String tupleS3Endpoint;

        @Parameter(
            required = false,
            names = { "--tuple-max-buffer-seconds", "--tupleMaxBufferSeconds" },
            arity = 1,
            description = "Maximum seconds before rotating/committing a tuple file")
        int tupleMaxBufferSeconds = 60;

        @Parameter(
            required = false,
            names = { "--tuple-max-file-size-mb", "--tupleMaxFileSizeMb" },
            arity = 1,
            description = "Maximum uncompressed size in MB before rotating a tuple file")
        int tupleMaxFileSizeMb = 256;

        @Parameter(
            required = false,
            names = { "--tuple-max-per-file", "--tupleMaxPerFile" },
            arity = 1,
            description = "Maximum number of tuples per S3 object. Set to 1 for one-tuple-per-file mode. "
                + "0 (default) means no count limit — rotation is controlled by size and age thresholds only.")
        int tupleMaxPerFile = 0;

        @Parameter(
            required = false,
            names = { "--non-retryable-doc-exception-types", "--nonRetryableDocExceptionTypes" },
            description = "Optional. Comma-separated list of document-level exception types that should NOT be "
                + "retried during bulk replay. These errors still count as failures but won't be retried since "
                + "they are client/logic errors that will produce the same result on every attempt. "
                + "Defaults to a built-in set including version_conflict_engine_exception, "
                + "mapper_parsing_exception, etc. "
                + "Example: --non-retryable-doc-exception-types version_conflict_engine_exception")
        List<String> nonRetryableDocExceptionTypes;

        @Parameter(
            required = false,
            names = { "--poison-doc-exception-types", "--poisonDocExceptionTypes" },
            description = "Optional, default empty. Comma-separated bulk item exception types that may be "
                + "committed as deliberate skips after retries stop and tuple evidence is durable.")
        List<String> poisonDocExceptionTypes;

        void validateKafkaAuthFlags() {
            if (kafkaTrafficAuthType != null && !kafkaTrafficAuthType.isBlank()) {
                if (Boolean.TRUE.equals(kafkaTrafficEnableMSKAuth)
                    && !KAFKA_AUTH_TYPE_MSK_IAM.equals(kafkaTrafficAuthType)) {
                    throw new ParameterException(
                        "--kafka-traffic-enable-msk-auth is only compatible with --kafkaAuthType=msk-iam"
                    );
                }
                if (!KAFKA_AUTH_TYPE_NONE.equals(kafkaTrafficAuthType)
                    && !KAFKA_AUTH_TYPE_MSK_IAM.equals(kafkaTrafficAuthType)
                    && !KAFKA_AUTH_TYPE_SCRAM_SHA_512.equals(kafkaTrafficAuthType)) {
                    throw new ParameterException("Unsupported --kafkaAuthType value: " + kafkaTrafficAuthType);
                }
            }
        }

        void validateConfiguration() {
            validateKafkaAuthFlags();
            validateMode();
            if (maxConcurrentRequests <= 0) {
                throw new ParameterException("--maxConcurrentRequests must be positive");
            }
            if (numClientThreads < 0) {
                throw new ParameterException("--numClientThreads must not be negative");
            }
            if (cancellationGraceMs < 0) {
                throw new ParameterException("--cancellation-grace-ms must not be negative");
            }
            if (heartbeatExpirationIntervalSeconds <= 0) {
                throw new ParameterException(
                    "--heartbeat-expiration-interval-seconds must be positive"
                );
            }
            if (maximumBackwardSkewSeconds < 0) {
                throw new ParameterException(
                    "--maximum-backward-skew-seconds must not be negative"
                );
            }
            if (sourceResponseRetryWindowSeconds <= 0) {
                throw new ParameterException(
                    "--source-response-retry-window-seconds must be positive"
                );
            }
            if (readyRequestsBufferPerThread < 1) {
                throw new ParameterException(
                    "--ready-requests-buffer-per-thread must be at least 1"
                );
            }
            if (speedupFactor <= 0.0 || !Double.isFinite(speedupFactor)) {
                throw new ParameterException("--speedup-factor must be a finite positive value");
            }
        }

        private void validateMode() {
            if (mode == null
                || !List.of(MODE_REPLAY, MODE_DUMP_RAW, MODE_DUMP_HTTP, MODE_DUMP_BOTH)
                    .contains(mode)) {
                throw new ParameterException("Unsupported --mode value: " + mode);
            }
        }

        boolean isKafkaTrafficEnableMSKAuth() {
            return KAFKA_AUTH_TYPE_MSK_IAM.equals(getEffectiveKafkaAuthType());
        }

        String getEffectiveKafkaAuthType() {
            validateKafkaAuthFlags();
            if (kafkaTrafficAuthType != null && !kafkaTrafficAuthType.isBlank()) {
                return kafkaTrafficAuthType;
            }
            return Boolean.TRUE.equals(kafkaTrafficEnableMSKAuth) ? KAFKA_AUTH_TYPE_MSK_IAM : KAFKA_AUTH_TYPE_NONE;
        }
    }

    @Getter
    public static class RequestTransformationParams implements TransformerParams {
        @Override
        public String getTransformerConfigParameterArgPrefix() {
            return REQUEST_SNAKE_TRANSFORMER_ARG_PREFIX;
        }
        private static final String REQUEST_SNAKE_TRANSFORMER_ARG_PREFIX = "";
        private static final String REQUEST_CAMEL_TRANSFORMER_ARG_PREFIX = "";

        @Parameter(
            required = false,
            names = { "--" + REQUEST_SNAKE_TRANSFORMER_ARG_PREFIX + "transformer-config-encoded",
                "--" + REQUEST_CAMEL_TRANSFORMER_ARG_PREFIX + "transformerConfigEncoded" },
            arity = 1,
            description = "Configuration of message transformers.  The same contents as --transformer-config but " +
                "Base64 encoded so that the configuration is easier to pass as a command line parameter.")
        private String transformerConfigEncoded;

        @Parameter(
            required = false,
            names = {"--" + REQUEST_SNAKE_TRANSFORMER_ARG_PREFIX + "transformer-config",
                "--" + REQUEST_CAMEL_TRANSFORMER_ARG_PREFIX + "transformerConfig",},
            arity = 1,
            description = "Configuration of message transformers.  Either as a string that identifies the "
                + "transformer that should be run (with default settings) or as json to specify options "
                + "as well as multiple transformers to run in sequence.  "
                + "For json, keys are the (simple) names of the loaded transformers and values are the "
                + "configuration passed to each of the transformers.")
        private String transformerConfig;

        @Parameter(
            required = false,
            names = {"--" + REQUEST_SNAKE_TRANSFORMER_ARG_PREFIX + "transformer-config-file",
                "--" + REQUEST_CAMEL_TRANSFORMER_ARG_PREFIX + "transformerConfigFile"},
            arity = 1,
            description = "Path to the JSON configuration file of message transformers.")
        private String transformerConfigFile;
    }

    @Getter
    public static class TupleTransformationParams implements TransformerParams {
        public String getTransformerConfigParameterArgPrefix() {
            return TUPLE_TRANSFORMER_CONFIG_SNAKE_PARAMETER_ARG_PREFIX;
        }
        static final String TUPLE_TRANSFORMER_CONFIG_SNAKE_PARAMETER_ARG_PREFIX = "tuple-";
        static final String TUPLE_TRANSFORMER_CONFIG_CAMEL_PARAMETER_ARG_PREFIX = "tuple";

        @Parameter(
            required = false,
            names = { "--" + TUPLE_TRANSFORMER_CONFIG_SNAKE_PARAMETER_ARG_PREFIX + "transformer-config-base64",
                "--" + TUPLE_TRANSFORMER_CONFIG_CAMEL_PARAMETER_ARG_PREFIX + "TransformerConfigBase64" },
            arity = 1,
            description = "Configuration of tuple transformers.  The same contents as --tuple-transformer-config but " +
                "Base64 encoded so that the configuration is easier to pass as a command line parameter.")
        private String transformerConfigEncoded;

        @Parameter(
            required = false,
            names = { "--" + TUPLE_TRANSFORMER_CONFIG_SNAKE_PARAMETER_ARG_PREFIX + "transformer-config",
                "--" + TUPLE_TRANSFORMER_CONFIG_CAMEL_PARAMETER_ARG_PREFIX + "TransformerConfig" },
            arity = 1,
            description = "Configuration of tuple transformers.  Either as a string that identifies the "
                + "transformer that should be run (with default settings) or as json to specify options "
                + "as well as multiple transformers to run in sequence.  "
                + "For json, keys are the (simple) names of the loaded transformers and values are the "
                + "configuration passed to each of the transformers.")
        private String transformerConfig;

        @Parameter(
            required = false,
            names = { "--" + TUPLE_TRANSFORMER_CONFIG_SNAKE_PARAMETER_ARG_PREFIX + "transformer-config-file",
                "--" + TUPLE_TRANSFORMER_CONFIG_CAMEL_PARAMETER_ARG_PREFIX + "TransformerConfigFile" } ,
            arity = 1,
            description = "Path to the JSON configuration file of tuple transformers.")
        private String transformerConfigFile;
    }

    private static Parameters parseArgs(String[] args) {
        Parameters p = EnvVarParameterPuller.injectFromEnv(new Parameters(), "TRAFFIC_REPLAYER_");
        var parser = JsonCommandLineParser.newBuilder().addObject(p).build();
        try {
            parser.parse(args);
            p.validateConfiguration();
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            System.err.println("Got args: " + String.join("; ", ArgLogUtils.getRedactedArgs(args, ArgNameConstants.CENSORED_ARGS)));
            parser.getJCommander().usage();
            System.exit(2);
            return null;
        }
        return p;
    }

    private static final String MODE_DUMP_RAW = "dump-raw";
    private static final String MODE_DUMP_HTTP = "dump-http";
    private static final String MODE_DUMP_BOTH = "dump-both";

    static boolean isDumpMode(Parameters params) {
        return MODE_DUMP_RAW.equals(params.mode) || MODE_DUMP_HTTP.equals(params.mode) || MODE_DUMP_BOTH.equals(params.mode);
    }

    // Package-private so the deferred-mode contract can be asserted without going through main(), which exits
    // the process on a ParameterException and would take the test JVM with it.
    static void validateDumpModeParams(Parameters params) {
        if (params.kafkaTrafficGroupId != null) {
            throw new ParameterException(
                "--kafka-traffic-group-id must not be specified in dump modes (they use no consumer group)");
        }
    }

    static void validateReplayModeParams(Parameters params) {
        if (params.targetUriString == null || params.targetUriString.isBlank()) {
            throw new ParameterException("--target-uri is required in replay mode");
        }
        validateRequiredKafkaParams(
            params.kafkaTrafficBrokers,
            params.kafkaTrafficTopic,
            params.kafkaTrafficGroupId
        );
    }

    static PartitionIntakeState.BrokerTimeConfiguration brokerTimeConfiguration(
        Parameters params
    ) {
        return new PartitionIntakeState.BrokerTimeConfiguration(
            Math.multiplyExact(
                params.heartbeatExpirationIntervalSeconds,
                1_000L
            ),
            Math.multiplyExact(
                params.maximumBackwardSkewSeconds,
                1_000L
            ),
            Math.multiplyExact(
                params.sourceResponseRetryWindowSeconds,
                1_000L
            )
        );
    }
    /** Runs a dump mode against a Kafka topic. */
    private static void runDumpMode(Parameters params) throws Exception {
        var runner = new KafkaTopicDumper();
        var rootContext = new RootReplayerContext(
            RootOtelContext.initializeOpenTelemetryWithCollectorsOrAsNoop(
                new OtelCollectorEndpoints(params.otelTraceCollectorEndpoint, params.otelMetricsCollectorEndpoint),
                "replay-dump",
                ProcessHelpers.getNodeInstanceName())
        );

        if (params.kafkaTrafficBrokers != null && params.kafkaTrafficTopic != null) {
            runner.runDumpFromKafka(params.mode, params.kafkaTrafficBrokers, params.kafkaTrafficTopic,
                params.getEffectiveKafkaAuthType(), params.kafkaTrafficUserName, params.kafkaTrafficPassword,
                params.kafkaTrafficPropertyFile,
                params.startOffset, params.startTime, params.endOffset, params.endTime,
                params.previewBytesRead, params.previewBytesWrite,
                rootContext);
        } else {
            System.err.println("Dump modes require --kafka-traffic-brokers and --kafka-traffic-topic");
            System.exit(2);
        }
    }
    /**
     * Parse and validate the replay target URI and timing params. On invalid input this prints the
     * error and calls System.exit (matching the prior inline behavior); it returns null only on the
     * exit paths so the caller can stop without duplicating the exit handling.
     */
    private static URI parseAndValidateReplayTarget(Parameters params) {
        URI uri;
        try {
            uri = URIHelper.parseUriWithDefaultPort(params.targetUriString);
        } catch (Exception e) {
            final var msg = "Exception parsing " + params.targetUriString;
            System.err.println(msg);
            System.err.println(e.getMessage());
            log.atError().setCause(e).setMessage("{}").addArgument(msg).log();
            System.exit(3);
            return null;
        }
        return uri;
    }
    private static void runReplayMode(Parameters params) throws Exception {
        validateReplayModeParams(params);
        var targetUri = parseAndValidateReplayTarget(params);
        if (targetUri == null) {
            return;
        }
        var deployed = createDeployedReplayApplication(
            params,
            targetUri,
            brokerTimeConfiguration(params)
        );
        var shutdownHook = deployed.application().createShutdownHook();
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        log.atInfo()
            .setMessage(
                "ReplayerConfig - speedup={} maxConcurrentRequests={} "
                    + "serverResponseTimeout={}s targetUri={} numClientThreads={} "
                    + "heartbeatExpiration={}s maximumBackwardSkew={}s "
                    + "sourceResponseRetryWindow={}s readyRequestsBufferPerThread={}"
            )
            .addArgument(params.speedupFactor)
            .addArgument(params.maxConcurrentRequests)
            .addArgument(params.targetServerResponseTimeoutSeconds)
            .addArgument(targetUri)
            .addArgument(params.numClientThreads)
            .addArgument(params.heartbeatExpirationIntervalSeconds)
            .addArgument(params.maximumBackwardSkewSeconds)
            .addArgument(params.sourceResponseRetryWindowSeconds)
            .addArgument(params.readyRequestsBufferPerThread)
            .log();
        deployed.application().run();
    }

    static Supplier<IJsonTransformer> buildTransformerSupplier(
        TransformationLoader transformationLoader,
        String hostname,
        String userAgent,
        String requestTransformerConfig,
        String requestFilterConfig
    ) {
        Supplier<IJsonTransformer> base = () -> transformationLoader.getTransformerFactoryLoader(
            hostname, userAgent, requestTransformerConfig);
        if (requestFilterConfig == null || requestFilterConfig.isBlank()) {
            return base;
        }
        var requestFilter = new PredicateLoader().getPredicateFactoryLoader(requestFilterConfig);
        log.atInfo().setMessage("Request filter configured").log();
        return () -> new FilteringTransformerWrapper(base.get(), requestFilter);
    }
    static Supplier<IJsonTransformer> buildResponsePostProcessorSupplier(
        TransformationLoader loader,
        String config
    ) {
        if (config == null || config.isBlank()) {
            return null;
        }
        log.atInfo().setMessage("Response post-processor configured").log();
        return () -> loader.getTransformerFactoryLoader(null, null, config);
    }

    static final class TupleSinkResources implements AutoCloseable {
        private final TrafficReplayerTopLevel.ManagedPhysicalTupleSinkFactory<Map<String, Object>>
            factory;
        private final AutoCloseable sharedResource;

        private TupleSinkResources(
            TrafficReplayerTopLevel.ManagedPhysicalTupleSinkFactory<Map<String, Object>> factory,
            AutoCloseable sharedResource
        ) {
            this.factory = factory;
            this.sharedResource = sharedResource;
        }

        TrafficReplayerTopLevel.ManagedPhysicalTupleSinkFactory<Map<String, Object>> factory() {
            return factory;
        }

        @Override
        public void close() throws Exception {
            sharedResource.close();
        }
    }

    static Optional<TupleSinkResources> createS3TupleSinkResourcesIfConfigured(
        Parameters params
    ) {
        if (params.tupleS3Bucket == null || params.tupleS3Bucket.isBlank()) {
            return Optional.empty();
        }
        if (params.tupleS3Region == null || params.tupleS3Region.isBlank()) {
            throw new ParameterException(
                "--tuple-s3-region is required when --tuple-s3-bucket is configured"
            );
        }
        if (params.tupleMaxFileSizeMb <= 0) {
            throw new ParameterException("--tuple-max-file-size-mb must be positive");
        }
        if (params.tupleMaxBufferSeconds <= 0) {
            throw new ParameterException("--tuple-max-buffer-seconds must be positive");
        }
        if (params.tupleMaxPerFile < 0) {
            throw new ParameterException("--tuple-max-per-file must not be negative");
        }

        var credentialsProvider = DefaultCredentialsProvider.builder().build();
        var s3ClientBuilder = S3AsyncClient.builder()
            .region(Region.of(params.tupleS3Region))
            .credentialsProvider(credentialsProvider);
        if (params.tupleS3Endpoint != null && !params.tupleS3Endpoint.isBlank()) {
            s3ClientBuilder
                .endpointOverride(URI.create(params.tupleS3Endpoint))
                .forcePathStyle(true);
        }
        return Optional.of(createS3TupleSinkResources(params, s3ClientBuilder.build()));
    }

    static TupleSinkResources createS3TupleSinkResources(
        Parameters params,
        S3AsyncClient s3Client
    ) {
        var replayerId = ProcessHelpers.getNodeInstanceName();
        var prefix = params.tupleS3Prefix == null ? "" : params.tupleS3Prefix;
        return new TupleSinkResources(
            sinkIndex -> TrafficReplayerTopLevel.deployedTupleSink(
                new S3TupleSink(
                    s3Client,
                    params.tupleS3Bucket,
                    prefix,
                    replayerId,
                    sinkIndex,
                    Math.multiplyExact(params.tupleMaxFileSizeMb, 1024L * 1024L),
                    Duration.ofSeconds(params.tupleMaxBufferSeconds),
                    params.tupleMaxPerFile
                )
            ),
            s3Client
        );
    }

    static final class DeployedReplayLifecycle implements ReplayLifecycle {
        private final TrafficReplayerTopLevel<
            NettyPacketToHttpConsumer.PreparedRequest,
            AggregatedRawResponse,
            Map<String, Object>
        > replayer;
        private final Consumer<String, byte[]> kafkaConsumer;
        private final String kafkaTopic;
        private final NioEventLoopGroup targetEventLoopGroup;
        private final IAuthTransformerFactory authTransformerFactory;
        private final Optional<TupleSinkResources> tupleSinkResources;

        private DeployedReplayLifecycle(
            TrafficReplayerTopLevel<
                NettyPacketToHttpConsumer.PreparedRequest,
                AggregatedRawResponse,
                Map<String, Object>
            > replayer,
            Consumer<String, byte[]> kafkaConsumer,
            String kafkaTopic,
            NioEventLoopGroup targetEventLoopGroup,
            IAuthTransformerFactory authTransformerFactory,
            Optional<TupleSinkResources> tupleSinkResources
        ) {
            this.replayer = replayer;
            this.kafkaConsumer = kafkaConsumer;
            this.kafkaTopic = kafkaTopic;
            this.targetEventLoopGroup = targetEventLoopGroup;
            this.authTransformerFactory = authTransformerFactory;
            this.tupleSinkResources = tupleSinkResources;
        }

        @Override
        public void start() {
            replayer.startIntake();
            kafkaConsumer.subscribe(List.of(kafkaTopic), replayer.rebalanceListener());
        }

        @Override
        public void runSourceOnce() {
            replayer.runSourceOnce();
        }

        @Override
        public void wakeSourceOwner() {
            replayer.wakeSourceOwner();
        }

        @Override
        public void closeOrderly() {
            replayer.close();
        }

        @Override
        // Shutdown preserves every failure while still attempting all remaining resource closes.
        @SuppressWarnings("java:S1181")
        public void closeTargetOwnersAfterOrderly() {
            replayer.allowTargetEventLoopTermination();
            Throwable failure = null;
            try {
                targetEventLoopGroup.shutdownGracefully().syncUninterruptibly();
            } catch (Throwable groupFailure) {
                failure = groupFailure;
            }
            if (authTransformerFactory != null) {
                try {
                    authTransformerFactory.close();
                } catch (Throwable authFailure) {
                    failure = appendFailure(failure, authFailure);
                }
            }
            if (tupleSinkResources.isPresent()) {
                try {
                    tupleSinkResources.orElseThrow().close();
                } catch (Throwable tupleResourceFailure) {
                    failure = appendFailure(failure, tupleResourceFailure);
                }
            }
            if (failure != null) {
                throw new IllegalStateException(
                    "Failed to close deployed replay resources",
                    failure
                );
            }
        }

        TrafficReplayerTopLevel<
            NettyPacketToHttpConsumer.PreparedRequest,
            AggregatedRawResponse,
            Map<String, Object>
        > replayer() {
            return replayer;
        }

        private static Throwable appendFailure(
            Throwable current,
            Throwable additional
        ) {
            if (current == null) {
                return additional;
            }
            if (current != additional) {
                current.addSuppressed(additional);
            }
            return current;
        }
    }

    // The warnings target compiler-generated record members that are existing construction test hooks.
    @SuppressWarnings({"java:S100", "java:S1186"})
    record DeployedReplayApplication(
        SupervisedReplayApplication application,
        ProcessSupervisor supervisor,
        DeployedReplayLifecycle lifecycle,
        RootReplayerContext rootContext
    ) {}

    @FunctionalInterface
    interface ProcessSupervisorFactory {
        ProcessSupervisor create(
            ProcessSupervisor.Metrics metrics,
            ProcessSupervisor.InputStopper inputStopper
        );
    }

    static DeployedReplayApplication createDeployedReplayApplication(
        Parameters params,
        URI targetUri,
        PartitionIntakeState.BrokerTimeConfiguration brokerTimeConfiguration
    ) throws Exception {
        return createDeployedReplayApplication(
            params,
            targetUri,
            brokerTimeConfiguration,
            deployedTupleRetryDelayPolicy(
                () -> ThreadLocalRandom.current().nextDouble()
            )
        );
    }

    static DeployedReplayApplication createDeployedReplayApplication(
        Parameters params,
        URI targetUri,
        PartitionIntakeState.BrokerTimeConfiguration brokerTimeConfiguration,
        Duration tupleRetryDelay
    ) throws Exception {
        return createDeployedReplayApplication(
            params,
            targetUri,
            brokerTimeConfiguration,
            TupleWriter.fixedRetryDelay(tupleRetryDelay)
        );
    }

    // Construction rollback must close resources and rethrow every Throwable without narrowing it.
    @SuppressWarnings("java:S1181")
    static DeployedReplayApplication createDeployedReplayApplication(
        Parameters params,
        URI targetUri,
        PartitionIntakeState.BrokerTimeConfiguration brokerTimeConfiguration,
        TupleWriter.RetryDelayPolicy tupleRetryDelayPolicy
    ) throws Exception {
        var rootContext = new RootReplayerContext(
            RootOtelContext.initializeOpenTelemetryWithCollectorsOrAsNoop(
                new OtelCollectorEndpoints(
                    params.otelTraceCollectorEndpoint,
                    params.otelMetricsCollectorEndpoint
                ),
                MODE_REPLAY,
                ProcessHelpers.getNodeInstanceName()
            )
        );
        var kafkaProperties = KafkaConsumerProperties.buildKafkaProperties(
            params.kafkaTrafficBrokers,
            params.kafkaTrafficGroupId,
            params.getEffectiveKafkaAuthType(),
            params.kafkaTrafficUserName,
            params.kafkaTrafficPassword,
            params.kafkaTrafficPropertyFile
        );
        var consumer = new KafkaConsumer<String, byte[]>(kafkaProperties);
        var targetEventLoopGroup = new NioEventLoopGroup(
            params.numClientThreads,
            new DefaultThreadFactory("targetConnectionPool")
        );
        var authTransformerFactory = buildAuthTransformerFactory(params);
        Optional<TupleSinkResources> tupleSinkResources = Optional.empty();
        try {
            tupleSinkResources = createS3TupleSinkResourcesIfConfigured(params);
            return createDeployedReplayApplication(
                params,
                targetUri,
                brokerTimeConfiguration,
                tupleRetryDelayPolicy,
                rootContext,
                consumer,
                targetEventLoopGroup,
                authTransformerFactory,
                tupleSinkResources,
                Clock.systemUTC(),
                System::nanoTime,
                ProcessSupervisor::new
            );
        } catch (Throwable failure) {
            closeAfterConstructionFailure(
                consumer,
                targetEventLoopGroup,
                authTransformerFactory,
                tupleSinkResources,
                failure
            );
            if (failure instanceof Exception exception) {
                throw exception;
            }
            throw failure;
        }
    }

    static DeployedReplayApplication createDeployedReplayApplication(
        Parameters params,
        URI targetUri,
        PartitionIntakeState.BrokerTimeConfiguration brokerTimeConfiguration,
        Duration tupleRetryDelay,
        RootReplayerContext rootContext,
        Consumer<String, byte[]> consumer,
        NioEventLoopGroup targetEventLoopGroup,
        IAuthTransformerFactory authTransformerFactory,
        Optional<TupleSinkResources> tupleSinkResources,
        Clock clock,
        LongSupplier nanoTime
    ) throws Exception {
        return createDeployedReplayApplication(
            params,
            targetUri,
            brokerTimeConfiguration,
            TupleWriter.fixedRetryDelay(tupleRetryDelay),
            rootContext,
            consumer,
            targetEventLoopGroup,
            authTransformerFactory,
            tupleSinkResources,
            clock,
            nanoTime,
            ProcessSupervisor::new
        );
    }

    // Construction rollback must close resources and rethrow every Throwable without narrowing it.
    @SuppressWarnings("java:S1181")
    static DeployedReplayApplication createDeployedReplayApplication(
        Parameters params,
        URI targetUri,
        PartitionIntakeState.BrokerTimeConfiguration brokerTimeConfiguration,
        TupleWriter.RetryDelayPolicy tupleRetryDelayPolicy,
        RootReplayerContext rootContext,
        Consumer<String, byte[]> consumer,
        NioEventLoopGroup targetEventLoopGroup,
        IAuthTransformerFactory authTransformerFactory,
        Optional<TupleSinkResources> tupleSinkResources,
        Clock clock,
        LongSupplier nanoTime,
        ProcessSupervisorFactory processSupervisorFactory
    ) throws Exception {
        var eventLoops = targetEventLoops(targetEventLoopGroup);
        var sslContext = loadTargetSslContext(
            targetUri,
            params.allowInsecureConnections
        );
        var timeShifter = new TimeShifter(
            params.speedupFactor,
            Duration.ZERO,
            clock
        );
        var transformationLoader = new TransformationLoader();
        var requestTransformerConfig =
            TransformerConfigUtils.getTransformerConfig(
                params.requestTransformationParams
            );
        var tupleTransformerConfig =
            TransformerConfigUtils.getTransformerConfig(
                params.tupleTransformationParams
            );
        var requestTransformerSupplier = buildTransformerSupplier(
            transformationLoader,
            targetUri.getHost(),
            params.userAgent,
            requestTransformerConfig,
            params.requestFilterConfig
        );
        var responsePostProcessorSupplier =
            buildResponsePostProcessorSupplier(
                transformationLoader,
                params.responsePostProcessorConfig
            );
        var resultsToLogsConsumer = new ResultsToLogsConsumer();
        var tupleSinkFactory = tupleSinkResources
            .map(TupleSinkResources::factory)
            .orElseGet(resultsToLogsConsumer::tupleSinkFactory);
        var errorClassifier = params.nonRetryableDocExceptionTypes == null
            ? new BulkItemErrorClassifier()
            : new BulkItemErrorClassifier(
                new java.util.HashSet<>(params.nonRetryableDocExceptionTypes)
            );
        var topLevelReference = new AtomicReference<TrafficReplayerTopLevel<
            NettyPacketToHttpConsumer.PreparedRequest,
            AggregatedRawResponse,
            Map<String, Object>
        >>();
        var supervisor = processSupervisorFactory.create(
            rootContext.getReplayProcessFatalMetrics(),
            signal -> {
                var topLevel = topLevelReference.get();
                if (topLevel != null) {
                    topLevel.stopNewInputForFatal(signal);
                } else {
                    consumer.wakeup();
                }
            }
        );
        var configuration = new TrafficReplayerTopLevel.Configuration<
            NettyPacketToHttpConsumer.PreparedRequest,
            AggregatedRawResponse,
            Map<String, Object>
        >(
            clock,
            nanoTime,
            sourceTime -> {
                timeShifter.setFirstTimestamp(sourceTime);
                return timeShifter.transformSourceTimeToRealTime(sourceTime);
            },
            connectionId -> selectDeployedTargetEventLoop(eventLoops, connectionId),
            eventLoops,
            TrafficReplayerTopLevel.deployedOwnerTaskRunner(),
            (connectionId, connectionContext) ->
                TrafficReplayerTopLevel.deployedRequestPreparer(
                    requestTransformerSupplier,
                    authTransformerFactory,
                    params.speedupFactor
                ),
            new OpenSearchDefaultRetry(errorClassifier),
            (connectionId, eventLoop, connectionContext) ->
                NettyPacketToHttpConsumer.create(
                    connectionId,
                    eventLoop,
                    clock,
                    connectionContext,
                    targetUri,
                    sslContext,
                    Duration.ofSeconds(
                        params.targetServerResponseTimeoutSeconds
                    )
                ),
            resultsToLogsConsumer::createTupleAndReportProgress,
            deployedResourceReleaser(),
            workerIndex -> TrafficReplayerTopLevel.deployedTupleTransformer(
                () -> transformationLoader.getTransformerFactoryLoader(
                    tupleTransformerConfig
                ),
                responsePostProcessorSupplier
            ),
            tupleSinkFactory,
            ignored -> {},
            tupleRetryDelayPolicy,
            brokerTimeConfiguration,
            params.readyRequestsBufferPerThread,
            DEFAULT_MAXIMUM_RESPONSE_RETRIES,
            params.maxConcurrentRequests
        );
        var replayer = new TrafficReplayerTopLevel<>(
            consumer,
            rootContext,
            configuration,
            DEFAULT_KAFKA_POLL_TIMEOUT,
            Duration.ofMillis(params.cancellationGraceMs),
            ignored -> supervisor.captureProtocolViolationDrainExpired(),
            supervisor.failureSink()
        );
        topLevelReference.set(replayer);
        var lifecycle = new DeployedReplayLifecycle(
            replayer,
            consumer,
            params.kafkaTrafficTopic,
            targetEventLoopGroup,
            authTransformerFactory,
            tupleSinkResources
        );
        return new DeployedReplayApplication(
            new SupervisedReplayApplication(lifecycle, supervisor),
            supervisor,
            lifecycle,
            rootContext
        );
    }

    static TupleWriter.RetryDelayPolicy deployedTupleRetryDelayPolicy(
        DoubleSupplier randomFraction
    ) {
        return TupleWriter.randomizedExponentialRetryDelay(
            TupleWriter.DEFAULT_INITIAL_RETRY_DELAY,
            TupleWriter.DEFAULT_MAXIMUM_RETRY_DELAY,
            randomFraction
        );
    }

    private static RequestReplayOwner.ResourceReleaser<
        HttpMessageAndTimestamp.Request,
        NettyPacketToHttpConsumer.PreparedRequest,
        AggregatedRawResponse,
        HttpMessageAndTimestamp.Response
    > deployedResourceReleaser() {
        return new RequestReplayOwner.ResourceReleaser<>() {
            @Override
            public void releaseSourceRequest(
                HttpMessageAndTimestamp.Request sourceRequest
            ) {
                // Source requests have no releasable resources in the deployed representation.
            }

            @Override
            public void releasePreparedRequest(
                NettyPacketToHttpConsumer.PreparedRequest preparedRequest
            ) {
                preparedRequest.close();
            }

            @Override
            public void releaseTargetResponse(
                AggregatedRawResponse targetResponse
            ) {
                // Aggregated target responses have no releasable resources.
            }

            @Override
            public void releaseSourceResponse(
                HttpMessageAndTimestamp.Response sourceResponse
            ) {
                // Source responses have no releasable resources in the deployed representation.
            }
        };
    }

    private static List<EventLoop> targetEventLoops(
        NioEventLoopGroup targetEventLoopGroup
    ) {
        var eventLoops = new ArrayList<EventLoop>();
        targetEventLoopGroup.forEach(executor -> {
            if (!(executor instanceof EventLoop eventLoop)) {
                throw new IllegalStateException(
                    "NioEventLoopGroup contained a non-EventLoop executor"
                );
            }
            eventLoops.add(eventLoop);
        });
        if (eventLoops.isEmpty()) {
            throw new IllegalStateException(
                "target event-loop group created no event loops"
            );
        }
        return List.copyOf(eventLoops);
    }

    static EventLoop selectDeployedTargetEventLoop(
        @NonNull List<EventLoop> eventLoops,
        @NonNull ConnectionProcessingId connectionId
    ) {
        if (eventLoops.isEmpty()) {
            throw new IllegalArgumentException("eventLoops must not be empty");
        }
        return eventLoops.get(
            Math.floorMod(connectionId.hashCode(), eventLoops.size())
        );
    }

    private static SslContext loadTargetSslContext(
        URI targetUri,
        boolean allowInsecureConnections
    ) throws Exception {
        if (!"https".equalsIgnoreCase(targetUri.getScheme())) {
            return null;
        }
        var builder = SslContextBuilder.forClient();
        if (allowInsecureConnections) {
            builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
        }
        return builder.build();
    }

    // Construction cleanup must preserve Errors as suppressed failures on the original Throwable.
    @SuppressWarnings("java:S1181")
    private static void closeAfterConstructionFailure(
        Consumer<String, byte[]> consumer,
        NioEventLoopGroup targetEventLoopGroup,
        IAuthTransformerFactory authTransformerFactory,
        Optional<TupleSinkResources> tupleSinkResources,
        Throwable constructionFailure
    ) {
        try {
            consumer.close();
        } catch (Throwable closeFailure) {
            constructionFailure.addSuppressed(closeFailure);
        }
        try {
            targetEventLoopGroup
                .shutdownGracefully(0, 0, TimeUnit.MILLISECONDS)
                .syncUninterruptibly();
        } catch (Throwable closeFailure) {
            constructionFailure.addSuppressed(closeFailure);
        }
        if (authTransformerFactory != null) {
            try {
                authTransformerFactory.close();
            } catch (Throwable closeFailure) {
                constructionFailure.addSuppressed(closeFailure);
            }
        }
        if (tupleSinkResources.isPresent()) {
            try {
                tupleSinkResources.orElseThrow().close();
            } catch (Throwable closeFailure) {
                constructionFailure.addSuppressed(closeFailure);
            }
        }
    }

    interface ReplayLifecycle {
        void start();

        void runSourceOnce();

        void wakeSourceOwner();

        void closeOrderly();

        void closeTargetOwnersAfterOrderly();
    }

    /**
     * Owns only process sequencing. Mutable Kafka, intake, connection, request, and tuple state remain in
     * their existing G5-G8 owners.
     */
    static final class SupervisedReplayApplication {
        private final ReplayLifecycle lifecycle;
        private final ProcessSupervisor supervisor;
        private final AtomicBoolean orderlyShutdownRequested = new AtomicBoolean();
        private final CompletableFuture<Void> orderlyShutdownFinished = new CompletableFuture<>();

        SupervisedReplayApplication(
            @NonNull ReplayLifecycle lifecycle,
            @NonNull ProcessSupervisor supervisor
        ) {
            this.lifecycle = lifecycle;
            this.supervisor = supervisor;
        }

        void run() {
            try {
                lifecycle.start();
                while (!orderlyShutdownRequested.get()
                    && !supervisor.fatalTerminationStarted()) {
                    lifecycle.runSourceOnce();
                }
                if (supervisor.fatalTerminationStarted()) {
                    finishAfterFatal();
                    return;
                }

                lifecycle.closeOrderly();
                if (supervisor.fatalTerminationStarted()) {
                    finishAfterFatal();
                    return;
                }
                lifecycle.closeTargetOwnersAfterOrderly();
                orderlyShutdownFinished.complete(null);
            } catch (Throwable failure) {
                var error = failure instanceof Error existing
                    ? existing
                    : new Error("Replay application lifecycle failed", failure);
                supervisor.unexpectedOwnerFailure(
                    "replay application",
                    orderlyShutdownRequested.get()
                        ? "orderly shutdown"
                        : "startup or source loop",
                    error
                );
                orderlyShutdownFinished.completeExceptionally(error);
            }
        }

        Thread createShutdownHook() {
            return new Thread(
                this::requestAndAwaitOrderlyShutdown,
                "traffic-replayer-orderly-shutdown"
            );
        }

        void requestAndAwaitOrderlyShutdown() {
            if (supervisor.fatalTerminationStarted()) {
                return;
            }
            orderlyShutdownRequested.set(true);
            lifecycle.wakeSourceOwner();
            try {
                orderlyShutdownFinished.join();
            } catch (CompletionException fatalDuringDrain) {
                if (!supervisor.fatalTerminationStarted()) {
                    throw fatalDuringDrain;
                }
            }
        }

        boolean orderlyShutdownRequested() {
            return orderlyShutdownRequested.get();
        }

        CompletableFuture<Void> orderlyShutdownFinished() {
            return orderlyShutdownFinished;
        }

        private void finishAfterFatal() {
            var failure = supervisor.firstFatalSignal()
                .map(ProcessSupervisor.FatalSignal::failure)
                .orElseGet(() -> new Error("fatal replay termination began without a signal"));
            orderlyShutdownFinished.completeExceptionally(failure);
        }
    }

    /**
     * This method returns a username:password Base64 encoded basic auth header
     * @param username The plaintext username
     * @param password The plaintext password
     * @return Basic Auth header string
     */
    public static String getBasicAuthHeader(String username, String password) {
        String authHeaderString = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(authHeaderString.getBytes(Charset.defaultCharset()));
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
            SIGV_4_AUTH_HEADER_SERVICE_REGION_ARG,
            ArgNameConstants.TARGET_USERNAME_ARG_KEBAB_CASE + " and " + ArgNameConstants.TARGET_PASSWORD_ARG_KEBAB_CASE

        );
    }

    private static IAuthTransformerFactory buildAuthTransformerFactory(Parameters params) {
        long authOptionsSpecified = Stream.of(
            params.removeAuthHeader,
            params.useSigV4ServiceAndRegion != null,
            params.targetUsername != null || params.targetPassword != null
        ).filter(b -> b).count();

        if (authOptionsSpecified > 1) {
            throw new IllegalArgumentException(
                "Cannot specify more than one auth option: " + formatAuthArgFlagsAsString()
            );
        }

        if (params.targetUsername != null || params.targetPassword != null) {
            if (params.targetUsername == null || params.targetPassword == null) {
                throw new ParameterException("Both target username and target password must be specified, when using this basic auth option");
            }
            return new StaticAuthTransformerFactory(getBasicAuthHeader(params.targetUsername, params.targetPassword));
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
                DefaultCredentialsProvider.builder().build(),
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

    public static void main(String[] args) throws Exception {
        var params = parseArgs(args);

        if (isDumpMode(params)) {
            try {
                validateDumpModeParams(params);
            } catch (ParameterException badDumpArgs) {
                // Exit code 2 is the existing convention for argument validation here, alongside 3 and 4 from
                // parseAndValidateReplayTarget. parseArgs has already returned by now, so its handler cannot
                // cover this.
                System.err.println(badDumpArgs.getMessage());
                System.exit(2);
                return;
            }
            runDumpMode(params);
            return;
        }

        try {
            runReplayMode(params);
        } catch (ParameterException badReplayArgs) {
            System.err.println(badReplayArgs.getMessage());
            System.exit(2);
        }
    }
}
