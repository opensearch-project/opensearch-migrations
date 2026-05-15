import {z} from "zod";
import { DEFAULT_RESOURCES, parseK8sQuantity } from "./schemaUtilities";

// ── Schema field metadata ────────────────────────────────────────────
// Downstream dependency names used in checksumFor annotations.
export type ChecksumDependency = 'snapshot' | 'snapshotMigration' | 'replayer';

export interface FieldMeta {
    /** Which downstream dependencies include this field in their checksum. */
    checksumFor?: ChecksumDependency[];
    /** Change restriction category for VAP generation. Omit for 'safe'. */
    changeRestriction?: 'impossible' | 'gated';
}

declare module "zod" {
    interface ZodType {
        checksumFor(...deps: ChecksumDependency[]): this;
        changeRestriction(restriction: 'impossible' | 'gated'): this;
    }
}

z.ZodType.prototype.checksumFor = function(...deps: ChecksumDependency[]) {
    const existing = (this.meta() ?? {}) as FieldMeta;
    return this.meta({ ...existing, checksumFor: deps });
};

z.ZodType.prototype.changeRestriction = function(restriction: 'impossible' | 'gated') {
    const existing = (this.meta() ?? {}) as FieldMeta;
    return this.meta({ ...existing, changeRestriction: restriction });
};

const REQUEST_TRANSFORMER_SUFFIX = " Request transformers modify each captured HTTP request before it is replayed to the target cluster.";
const TUPLE_TRANSFORMER_SUFFIX = " Tuple transformers operate on request-response pairs, enabling stateful transformations that depend on the source cluster's response.";
const METADATA_TRANSFORMER_SUFFIX = " Metadata transformers modify index mappings and settings during migration.";
const DOC_TRANSFORMER_SUFFIX = " Document transformers modify each document during the snapshot backfill (e.g. field renaming, type conversion).";
const EXPERT_FILE_SUFFIX = " [Expert] The file must be mounted into the container by the user (e.g. via Kyverno pod mutation or custom image). Not wired through the workflow by default.";
const JVM_ARGS_DESC = "Additional JVM arguments passed via JDK_JAVA_OPTIONS (e.g. '-Xmx4g -XX:+UseG1GC'). " +
    "If setting -Xmx, ensure the heap size is smaller than the container's memory limit in resources to account for off-heap memory usage.";
const LOGGING_CONFIG_OVERRIDE_DESC = "Name of a Kubernetes ConfigMap containing a custom Log4j2 properties configuration. " +
    "The ConfigMap should have a single key whose value is the Log4j2 properties file content. " +
    "When set, it is mounted into the container and passed via -Dlog4j2.configurationFile. " +
    "See https://logging.apache.org/log4j/2.x/manual/configuration.html#properties for format reference.";
import deepmerge from "deepmerge";

export function getZodKeys<T extends z.ZodRawShape>(schema: z.ZodObject<T>): readonly (keyof T)[] {
    return Object.keys(schema.shape) as (keyof T)[];
}
class SchemaValidationError extends Error {
    constructor(message: string, public path: string[]) {
        super(`${message} at path: ${path.join('.')}`);
        this.name = 'SchemaValidationError';
    }
}

function validateOptionalDefaultConsistency<T extends z.ZodTypeAny>(
    schema: T,
    path: string[] = []
): T {
    if (schema instanceof z.ZodOptional) {
        const innerType = schema.unwrap();
        const hasDefault = innerType instanceof z.ZodDefault;

        // Unwrap to get the actual type
        const actualType = hasDefault ? (innerType as z.ZodDefault<any>).removeDefault() : innerType;

        // Check if the actual underlying type is complex
        const isComplexType = actualType instanceof z.ZodObject ||
            actualType instanceof z.ZodArray ||
            actualType instanceof z.ZodRecord ||
            actualType instanceof z.ZodUnion ||
            actualType instanceof z.ZodDiscriminatedUnion ||
            actualType instanceof z.ZodIntersection ||
            actualType instanceof z.ZodTuple;

        // Only enforce default requirement for scalar values
        if (!isComplexType && !hasDefault) {
            throw new SchemaValidationError(
                'Optional field must have a default value',
                path
            );
        }

        // Recurse on the actual type
        validateOptionalDefaultConsistency(actualType as z.ZodTypeAny, path);
    } else if (schema instanceof z.ZodDefault) {
        const innerType = (schema as z.ZodDefault<any>).removeDefault();

        // Check if the actual underlying type is complex
        const isComplexType = innerType instanceof z.ZodObject ||
            innerType instanceof z.ZodArray ||
            innerType instanceof z.ZodRecord ||
            innerType instanceof z.ZodUnion ||
            innerType instanceof z.ZodDiscriminatedUnion ||
            innerType instanceof z.ZodIntersection ||
            innerType instanceof z.ZodTuple;

        // Only enforce optional requirement for scalar values
        if (!isComplexType) {
            throw new SchemaValidationError(
                'Default value must be followed by .optional() (use .default(value).optional())',
                path
            );
        }

        // Recurse on the inner type
        validateOptionalDefaultConsistency(innerType as z.ZodTypeAny, path);
    } else if (schema instanceof z.ZodObject) {
        const shape = schema.shape;
        for (const [key, value] of Object.entries(shape)) {
            validateOptionalDefaultConsistency(value as z.ZodTypeAny, [...path, key]);
        }
    } else if (schema instanceof z.ZodArray) {
        validateOptionalDefaultConsistency(schema.element as z.ZodTypeAny, [...path, '[array element]']);
    } else if (schema instanceof z.ZodRecord) {
        validateOptionalDefaultConsistency(schema.valueType as z.ZodTypeAny, [...path, '[record value]']);
    } else if (schema instanceof z.ZodUnion) {
        schema.options.forEach((option, index) => {
            validateOptionalDefaultConsistency(option as z.ZodTypeAny, [...path, `[union option ${index}]`]);
        });
    } else if (schema instanceof z.ZodDiscriminatedUnion) {
        Array.from(schema.options.values()).forEach((option, index) => {
            validateOptionalDefaultConsistency(option as z.ZodTypeAny, [...path, `[discriminated union option ${index}]`]);
        });
    } else if (schema instanceof z.ZodIntersection) {
        validateOptionalDefaultConsistency(schema._def.left as z.ZodTypeAny, [...path, '[intersection left]']);
        validateOptionalDefaultConsistency(schema._def.right as z.ZodTypeAny, [...path, '[intersection right]']);
    } else if (schema instanceof z.ZodTuple) {
        const items = schema._def.items;
        items.forEach((item, index) => {
            validateOptionalDefaultConsistency(item as z.ZodTypeAny, [...path, `[tuple ${index}]`]);
        });
    }
    return schema;
}

export const S3_REPO_CONFIG = z.object({
    awsRegion: z.string()
        .describe("AWS region where the S3 bucket resides (e.g. 'us-east-2'). Used for S3 client configuration and snapshot repository registration."),
    endpoint: z.string().regex(/(?:^(http|localstack)s?:\/\/[^/]*\/?$)?/).default("").optional()
        .describe("Override the S3 endpoint URL. Supports http://, https://, localstack://, and localstacks:// schemes. " +
            "LocalStack endpoints are automatically resolved to IP addresses during config transformation."),
    s3RepoPathUri: z.string().regex(/^s3:\/\/[a-z0-9][a-z0-9.-]{1,61}[a-z0-9](\/[a-zA-Z0-9!\-_.*'()/]*)?$/)
        .describe("S3 URI for the snapshot repository in the format 's3://BUCKET_NAME/OPTIONAL_PATH'. " +
            "The bucket must already exist and be accessible from the source cluster."),
    s3RoleArn: z.string().regex(/^(arn:aws:iam::\d{12}:(user|role|group|policy)\/[a-zA-Z0-9+=,.@_-]+)?$/).default("").optional()
        .describe("IAM role ARN that the source cluster will assume to read/write snapshots to S3. " +
            "This is passed to the cluster when registering the snapshot repository. " +
            "Leave empty if the cluster's own IAM role already has S3 access.")
}).describe("Configuration for an S3-backed snapshot repository used by the source cluster.");

export const PORT_NUMBER_PATTERN = "(?:[1-9]\\d{0,3}|[1-5]\\d{4}|6[0-4]\\d{3}|65[0-4]\\d{2}|655[0-2]\\d|6553[0-5])";
export const OPTIONAL_PORT_PATTERN = `(?::${PORT_NUMBER_PATTERN})?`;
export const HOSTNAME_PATTERN = "[^:\\/\\s]+";
export const HTTP_ENDPOINT_PATTERN = `^https?:\\/\\/${HOSTNAME_PATTERN}${OPTIONAL_PORT_PATTERN}(?:\\/)?$`;
export const OPTIONAL_HTTP_ENDPOINT_PATTERN = `^(?:https?:\\/\\/${HOSTNAME_PATTERN}${OPTIONAL_PORT_PATTERN}(?:\\/)?)?$`;

export const GENERIC_JSON_OBJECT = z.record(z.string(), z.any());
export const K8S_NAMING_PATTERN = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$/;

const OTEL_COLLECTOR_ENDPOINT = z.string().default("http://otel-collector:4317").optional()
    .describe("URL for the OpenTelemetry Collector endpoint used for metrics and traces (e.g. 'http://otel-collector:4317').");

export const KAFKA_CLIENT_CONFIG = z.object({
    enableMSKAuth: z.boolean().default(false).optional()
        .describe("Enable SASL/IAM authentication for Amazon MSK. When true, configures the Kafka client with the required SASL properties for IAM-based authentication. Uses the pod's IAM role via EKS Pod Identity."),
    kafkaConnection: z.string()
        .describe("Comma-delimited list of Kafka broker addresses in 'HOSTNAME:PORT' format (e.g. 'broker1:9092,broker2:9092'). " +
            "Required when using an externally managed Kafka cluster.")
        .regex(new RegExp(`^(?:[a-z0-9][-a-z0-9.]*:${PORT_NUMBER_PATTERN}(?:,(?!$)|$))*$`)),
    kafkaTopic: z.string()
        .describe("Default Kafka topic name for this cluster. Can be overridden per-proxy via the capture config's kafkaTopic field.")
        .default(""),
    managedByWorkflow: z.boolean().default(false).optional()
      .describe("Internal flag indicating whether the Kafka cluster is created and resolved by the workflow."),
    listenerName: z.string().default("").optional()
      .describe("Resolved Kafka listener name used by migration applications."),
    authType: z.enum(["none", "scram-sha-512"]).default("none").optional()
      .describe("Resolved Kafka auth mode used by migration applications."),
    secretName: z.string().default("").optional()
      .describe("Resolved Kubernetes secret containing Kafka client credentials."),
    caSecretName: z.string().default("").optional()
      .describe("Resolved Kubernetes secret containing the Kafka cluster CA certificate for TLS trust."),
    kafkaUserName: z.string().default("").optional()
      .describe("Resolved Kafka principal name used by migration applications."),
    topicSpecOverrides: GENERIC_JSON_OBJECT.default({}).optional()
      .describe("Resolved Strimzi KafkaTopic.spec overrides used when the workflow creates the topic resource."),
}).describe("Connection configuration for an externally managed Kafka cluster.");

export const KAFKA_EXISTING_AUTH_CONFIG = z.discriminatedUnion("type", [
    z.object({
        type: z.literal("none"),
    }),
    z.object({
        type: z.literal("scram-sha-512"),
        secretName: z.string().regex(K8S_NAMING_PATTERN),
        caSecretName: z.string().regex(K8S_NAMING_PATTERN),
        kafkaUserName: z.string().regex(K8S_NAMING_PATTERN).optional(),
    }),
]);

export const KAFKA_AUTO_CREATE_AUTH_CONFIG = z.discriminatedUnion("type", [
    z.object({
        type: z.literal("none"),
    }),
    z.object({
        type: z.literal("scram-sha-512"),
    }),
]);

export const DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES = {
    partitions: 1,
    replicas: 3,
    config: {
        "retention.ms": 604800000,
        "segment.bytes": 1073741824,
    }
} as const;

// These defaults are resolved during initialization so workflow templates do not
// need to invent their own Kafka defaults. That keeps default ownership here,
// even though some workflow templates currently unpack pieces of the resolved
// Strimzi objects into explicit fields for Argo rendering safety.
const DEFAULT_AUTO_CREATE_KAFKA = {
    clusterSpecOverrides: {
        kafka: {
            readinessProbe: {
                initialDelaySeconds: 10,
                periodSeconds: 10,
                timeoutSeconds: 5,
                failureThreshold: 12,
            },
            livenessProbe: {
                initialDelaySeconds: 30,
                periodSeconds: 15,
                timeoutSeconds: 5,
                failureThreshold: 8,
            },
            config: {
                // RF=3 + minISR=2 on a 3-broker cluster: survives one broker
                // down (rolling restart, single node loss) without losing
                // writes or quorum. ISR = replicas fully caught up to the
                // leader; acks=all blocks until every ISR member has written,
                // and writes fail (NotEnoughReplicasException) if |ISR|<minISR.
                // auto.create.topics.enable stays false — all migration topics
                // are declared explicitly via KafkaTopic CRs.
                "auto.create.topics.enable": false,
                "offsets.topic.replication.factor": 3,
                "transaction.state.log.replication.factor": 3,
                "transaction.state.log.min.isr": 2,
                "default.replication.factor": 3,
                "min.insync.replicas": 2,
            }
        }
    },
    nodePoolSpecOverrides: {
        // 3-broker, combined (controller+broker) KRaft pool. 3 is the minimum
        // that satisfies RF=3/minISR=2 above.
        replicas: 3,
        roles: ["controller", "broker"],
        storage: {
            type: "persistent-claim",
            // Smoke-test size (raised 1Gi → 2Gi with the 1→3 broker bump;
            // internal topics are now RF=3). Real deployments should override.
            size: "2Gi",
            deleteClaim: true,
        },
        template: {
            pod: {
                // Soft anti-affinity — prefers spreading brokers across nodes
                // but still schedules on single-node dev clusters. Required
                // anti-affinity would wedge broker rescheduling during an EKS
                // node rotation if the new pool temporarily has <3 nodes.
                affinity: {
                    podAntiAffinity: {
                        preferredDuringSchedulingIgnoredDuringExecution: [
                            {
                                weight: 100,
                                podAffinityTerm: {
                                    labelSelector: {
                                        matchExpressions: [
                                            {
                                                key: "strimzi.io/name",
                                                operator: "Exists",
                                            },
                                        ],
                                    },
                                    topologyKey: "kubernetes.io/hostname",
                                },
                            },
                        ],
                    },
                },
            },
        },
    },
    topicSpecOverrides: {
        ...DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES
    },
};

const replaceArrayMerge = (_destinationArray: unknown[], sourceArray: unknown[]) => sourceArray;

export const KAFKA_EXISTING_CLUSTER_CONFIG = z.object({
    enableMSKAuth: z.boolean().default(false).optional(),
    kafkaConnection: z.string()
        .describe("Sequence of <HOSTNAME:PORT> values delimited by ','.")
        .regex(new RegExp(`^(?:[a-z0-9][-a-z0-9.]*:${PORT_NUMBER_PATTERN}(?:,(?!$)|$))*$`)),
    kafkaTopic: z.string().describe("Empty defaults to the name of the target label").default(""),
    auth: KAFKA_EXISTING_AUTH_CONFIG.default({type: "none"}).optional(),
});

export const CPU_QUANTITY = z.string()
    .regex(/^[0-9]+m$/)
    .describe("CPU quantity in Kubernetes millicores (e.g. '100m' = 0.1 CPU, '3500m' = 3.5 CPUs). Must end with 'm'.");

export const MEMORY_QUANTITY = z.string()
    .regex(/^[0-9]+(([EPTGM])i?|Ki|k)$/)
    .describe("Memory quantity in Kubernetes resource format with binary or decimal units (e.g. '512Mi', '2Gi', '4G').");

export const STORAGE_QUANTITY = z.string()
    .regex(/^[0-9]+(([EPTGM])i?|Ki|k)$/)
    .describe("Storage quantity in Kubernetes resource format with binary or decimal units (e.g. '10Gi', '100G').");

export const CONTAINER_RESOURCES = {
    cpu: CPU_QUANTITY.describe("CPU allocation for the container in Kubernetes millicores."),
    memory: MEMORY_QUANTITY.describe("Memory allocation for the container."),
    "ephemeral-storage": STORAGE_QUANTITY.optional()
        .describe("Ephemeral storage allocation for the container. Used for temporary on-disk data such as Lucene index segments during RFS document migration.")
}

export const RESOURCE_REQUIREMENTS = z.object({
    limits: z.object(CONTAINER_RESOURCES).describe("Maximum resource limits for the container. The container will be terminated if it exceeds these limits."),
    requests: z.object(CONTAINER_RESOURCES).describe("Minimum guaranteed resources for the container. Used by the Kubernetes scheduler for pod placement.")
}).describe("Kubernetes compute resource requirements for a container. " +
    "When limits equal requests, the pod gets 'Guaranteed' QoS class and is less likely to be evicted. " +
    "See https://kubernetes.io/docs/concepts/workloads/pods/pod-qos/#guaranteed for details.");

export type ResourceRequirementsType = z.infer<typeof RESOURCE_REQUIREMENTS>;

export const CERT_MANAGER_ISSUER_REF = z.object({
    name: z.string().describe("Name of the cert-manager Issuer or ClusterIssuer resource that will sign the certificate."),
    kind: z.enum(["Issuer", "ClusterIssuer"]).default("ClusterIssuer").optional()
        .describe("Kind of the cert-manager issuer resource. 'ClusterIssuer' is cluster-scoped; 'Issuer' is namespace-scoped."),
    group: z.string().default("cert-manager.io").optional()
        .describe("API group of the issuer. Use 'cert-manager.io' for standard issuers or 'awspca.cert-manager.io' for AWS Private CA issuers."),
}).describe("Reference to a cert-manager issuer that will sign TLS certificates for the proxy.");

export const PROXY_TLS_CONFIG = z.discriminatedUnion("mode", [
    z.object({
        mode: z.literal("certManager")
            .describe("Use cert-manager to automatically provision and renew a TLS certificate."),
        issuerRef: CERT_MANAGER_ISSUER_REF,
        commonName: z.string().optional()
            .describe("Optional common name (CN) for the TLS certificate subject."),
        dnsNames: z.array(z.string()).min(1)
            .describe("DNS Subject Alternative Names for the certificate. Must include the proxy's Kubernetes service DNS name (e.g. 'my-proxy.default.svc.cluster.local')."),
        duration: z.string().default("2160h").optional()
            .describe("Requested certificate validity duration in Go duration format (e.g. '2160h' = 90 days)."),
        renewBefore: z.string().default("360h").optional()
            .describe("How long before certificate expiry to trigger renewal (e.g. '360h' = 15 days)."),
    }).describe("Provision a TLS certificate via cert-manager. A Certificate resource is created and the resulting secret is mounted into the proxy pod."),
    z.object({
        mode: z.literal("existingSecret")
            .describe("Use a pre-existing Kubernetes TLS secret."),
        secretName: z.string()
            .describe("Name of an existing Kubernetes TLS secret containing 'tls.crt' and 'tls.key' entries. The secret is mounted into the proxy pod at /etc/proxy-tls/."),
    }).describe("Use a pre-existing Kubernetes TLS secret for proxy HTTPS termination."),
    z.object({
        mode: z.literal("plaintext")
            .describe("Explicitly disable TLS. The proxy will serve plaintext HTTP. Use this to opt out of the secure-by-default TLS behavior."),
    }).describe("Explicitly disable TLS termination on the capture proxy."),
]).describe("TLS configuration for the capture proxy. When omitted, a self-signed certificate is automatically provisioned via cert-manager. Specify mode 'plaintext' to opt out.");

export const USER_PROXY_WORKFLOW_OPTIONS = z.object({
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
        .describe(LOGGING_CONFIG_OVERRIDE_DESC),
    internetFacing: z.boolean().default(false).optional()
        .describe("When true, the proxy's Kubernetes Service is annotated with 'internet-facing' load balancer scheme, making it accessible from outside the VPC.")
        .changeRestriction('impossible'),
    podReplicas: z.number().default(1).optional()
        .describe("Number of proxy pod replicas in the Kubernetes Deployment. Increase for higher throughput or availability."),
    resources: z.preprocess((v) => deepmerge(DEFAULT_RESOURCES.PROXY, (v ?? {})), RESOURCE_REQUIREMENTS)
        .describe("Kubernetes resource limits and requests for the capture proxy container. " +
            "Partial overrides are deep-merged with the built-in defaults. " +
            "By default, limits equal requests, giving the pod 'Guaranteed' QoS (least likely to be evicted). " +
            "Setting requests lower than limits results in 'Burstable' QoS, allowing the pod to use less resources when idle but burst up to the limit.")
        .default(DEFAULT_RESOURCES.PROXY),
}).describe("Kubernetes deployment-level options for the capture proxy.");

export const USER_PROXY_PROCESS_OPTIONS = z.object({
    otelCollectorEndpoint: OTEL_COLLECTOR_ENDPOINT,
    setHeader: z.array(z.string()).optional()
        .describe("List of static headers to add to proxied requests, each in 'Header-Name: value' format.")
        .checksumFor('snapshot', 'replayer')
        .changeRestriction('gated'),
    destinationConnectionPoolSize: z.number().default(0).optional()
        .describe("Maximum number of persistent connections to the destination (source) cluster. 0 means unlimited connection pooling."),
    destinationConnectionPoolTimeout: z.string()
        .regex(/^[-+]?P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/)
        .default("PT30S").optional()
        .describe("ISO 8601 duration for how long idle connections in the destination pool are kept alive before being closed (e.g. 'PT30S' = 30 seconds, 'PT5M' = 5 minutes)."),
    kafkaClientId: z.string().default("HttpCaptureProxyProducer").optional()
        .describe("Kafka producer client ID used when publishing captured traffic to Kafka. Useful for identifying this proxy in Kafka broker logs and metrics."),
    listenPort: z.number()
        .describe("TCP port the capture proxy listens on for incoming HTTP(S) traffic. This port is exposed via the Kubernetes Service and used to construct the proxy endpoint URL.")
        .checksumFor('snapshot', 'replayer')
        .changeRestriction('impossible'),
    maxTrafficBufferSize: z.number().min(1).max(1048576).default(1048576).optional()
        .describe("Maximum size in bytes for buffering a single HTTP request/response payload before forwarding to Kafka.")
        .changeRestriction('gated'),
    noCapture: z.boolean().default(false).optional()
        .describe("When true, the proxy forwards traffic to the source cluster without capturing it to Kafka. Useful for TLS termination or routing without traffic recording.")
        .checksumFor('snapshot', 'replayer')
        .changeRestriction('gated'),
    numThreads: z.number().default(1).optional()
        .describe("Number of Netty worker threads for the proxy to handle concurrent connections."),
    tls: PROXY_TLS_CONFIG.optional()
        .describe("TLS certificate configuration for HTTPS termination at the proxy. When configured, the proxy serves HTTPS and the TLS secret is mounted at /etc/proxy-tls/.")
        .changeRestriction('gated'),
    enableMSKAuth: z.boolean().default(false).optional()
        .describe("Enable SASL/IAM authentication for the proxy's Kafka producer when connecting to Amazon MSK. Uses the pod's IAM role via EKS Pod Identity.")
        .changeRestriction('gated'),
    suppressCaptureForHeaderMatch: z.array(z.string()).default([]).optional()
        .describe("List of header patterns. Requests matching any of these header patterns will be forwarded but not captured to Kafka.")
        .checksumFor('snapshot', 'replayer')
        .changeRestriction('gated'),
    suppressCaptureForMethod: z.string().default("").optional()
        .describe("HTTP method to suppress from capture (e.g. 'HEAD'). Requests with this method are forwarded but not recorded.")
        .checksumFor('snapshot', 'replayer')
        .changeRestriction('gated'),
    suppressCaptureForUriPath: z.string().default("").optional()
        .describe("URI path pattern to suppress from capture. Requests matching this path are forwarded but not recorded.")
        .checksumFor('snapshot', 'replayer')
        .changeRestriction('gated'),
    suppressMethodAndPath: z.string().default("").optional()
        .describe("Combined method and path pattern for capture suppression in 'METHOD /path' format.")
        .checksumFor('snapshot', 'replayer')
        .changeRestriction('gated'),
}).describe("Process-level configuration options for the capture proxy application. These are passed as command-line arguments to the proxy container.");

export const USER_PROXY_WORKFLOW_OPTION_KEYS = getZodKeys(USER_PROXY_WORKFLOW_OPTIONS);
export const USER_PROXY_PROCESS_OPTION_KEYS = getZodKeys(USER_PROXY_PROCESS_OPTIONS);

export const USER_PROXY_OPTIONS = z.object({
    ...USER_PROXY_WORKFLOW_OPTIONS.shape,
    ...USER_PROXY_PROCESS_OPTIONS.shape,
}).describe("Process-level and deployment-level configuration options for the capture proxy.");

export const USER_REPLAYER_WORKFLOW_OPTIONS = z.object({
    jvmArgs: z.string().default("").optional()
        .describe(JVM_ARGS_DESC),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
        .describe(LOGGING_CONFIG_OVERRIDE_DESC),
    podReplicas: z.number().default(1).optional()
        .describe("Number of replayer pod replicas in the Kubernetes Deployment. Each replica independently consumes from Kafka and replays traffic to the target."),
    resources: z.preprocess((v) => deepmerge(DEFAULT_RESOURCES.REPLAYER, (v ?? {})), RESOURCE_REQUIREMENTS)
        .describe("Kubernetes resource limits and requests for the replayer container. " +
            "Partial overrides are deep-merged with the built-in defaults. " +
            "By default, limits equal requests, giving the pod 'Guaranteed' QoS (least likely to be evicted). " +
            "Setting requests lower than limits results in 'Burstable' QoS, allowing the pod to use less resources when idle but burst up to the limit."),
}).describe("Kubernetes deployment-level options for the traffic replayer.");

export const USER_REPLAYER_PROCESS_OPTIONS = z.object({
    kafkaTrafficEnableMSKAuth: z.boolean().default(false).optional()
        .describe("Enable SASL/IAM authentication for the replayer's Kafka consumer when connecting to Amazon MSK. Uses the pod's IAM role via EKS Pod Identity.")
        .changeRestriction('impossible'),
    kafkaTrafficPropertyFile: z.string().optional()
        .describe("[Expert] Path to a Java properties file with additional or overridden Kafka consumer configuration. The file must be mounted into the container by the user (e.g. via Kyverno pod mutation or custom image). Not wired through the workflow by default.")
        .changeRestriction('impossible'),
    lookaheadTimeSeconds: z.number().default(400).optional()
        .describe("Number of seconds of captured traffic to buffer ahead of the current replay position. Must be strictly greater than observedPacketConnectionTimeout. Larger values improve throughput but increase memory usage."),
    maxConcurrentRequests: z.number().default(10000).optional()
        .describe("Maximum number of HTTP requests that can be in-flight simultaneously to the target cluster. Limits concurrency to prevent overwhelming the target."),
    numClientThreads: z.number().default(0).optional()
        .describe("Number of threads used to send replayed requests to the target. 0 uses the Netty event loop (typically number of available processors)."),
    nonRetryableDocExceptionTypes: z.array(z.string()).optional()
        .describe("List of document-level exception types that should not be retried during bulk replay. " +
            "These errors still count as failures in the output but are not retried because they are " +
            "deterministic client or mapping errors that will produce the same result on every attempt. " +
            "When omitted, defaults to a built-in set including version_conflict_engine_exception, " +
            "mapper_parsing_exception, strict_dynamic_mapping_exception, and others. " +
            "Set explicitly to override the defaults entirely (not additive). " +
            "Common values: version_conflict_engine_exception, mapper_parsing_exception, " +
            "illegal_argument_exception, resource_already_exists_exception."),
    observedPacketConnectionTimeout: z.number().default(360).optional()
        .describe("Seconds of inactivity on a captured connection before assuming it was terminated in the original traffic stream. Must be strictly less than lookaheadTimeSeconds."),
    otelCollectorEndpoint: OTEL_COLLECTOR_ENDPOINT,
    quiescentPeriodMs: z.number().default(5000).optional()
        .describe("Milliseconds to delay the first request on a resumed connection after a Kafka partition reassignment. Prevents request bursts during rebalancing."),
    removeAuthHeader: z.boolean().default(false).optional()
        .describe("Remove the Authorization header from replayed requests without replacing it. Useful when the target uses a different auth mechanism (e.g. SigV4) configured separately.")
        .changeRestriction('gated'),
    speedupFactor: z.number().default(1.1).optional()
        .describe("Multiplier to accelerate replay timing relative to the original captured traffic. 1.0 = real-time, 2.0 = double speed."),
    targetServerResponseTimeoutSeconds: z.number().default(150).optional()
        .describe("Maximum seconds to wait for a response from the target cluster before timing out a replayed request."),
    transformerConfig: z.string().optional()
        .describe("Inline request transformer configuration as a JSON string." + REQUEST_TRANSFORMER_SUFFIX)
        .changeRestriction('gated'),
    transformerConfigEncoded: z.string().optional()
        .describe("Base64-encoded request transformer configuration, for configurations that would be cumbersome to otherwise encode in a JSON field." + REQUEST_TRANSFORMER_SUFFIX)
        .changeRestriction('gated'),
    transformerConfigFile: z.string().optional()
        .describe("Path to a JSON file containing request transformer configuration." + REQUEST_TRANSFORMER_SUFFIX + EXPERT_FILE_SUFFIX)
        .changeRestriction('gated'),
    tupleTransformerConfig: z.string().optional()
        .describe("Inline tuple transformer configuration as a JSON string." + TUPLE_TRANSFORMER_SUFFIX)
        .changeRestriction('gated'),
    tupleTransformerConfigBase64: z.string().optional()
        .describe("Base64-encoded tuple transformer configuration." + TUPLE_TRANSFORMER_SUFFIX)
        .changeRestriction('gated'),
    tupleTransformerConfigFile: z.string().optional()
        .describe("Path to a JSON file containing tuple transformer configuration." + TUPLE_TRANSFORMER_SUFFIX + EXPERT_FILE_SUFFIX)
        .changeRestriction('gated'),
    tupleMaxBufferSeconds: z.number().default(60).optional()
        .describe("Maximum seconds before rotating/uploading a tuple file to S3.")
        .changeRestriction('gated'),
    tupleMaxFileSizeMb: z.number().default(256).optional()
        .describe("Maximum uncompressed size in MB before rotating a tuple file to S3.")
        .changeRestriction('gated'),
    userAgent: z.string().optional()
        .describe("String appended to the User-Agent header on all replayed requests to the target cluster. Useful for identifying replayed traffic in target cluster logs."),
}).describe("Process-level configuration options for the traffic replayer application. These control how captured traffic is read from Kafka and replayed to the target cluster.");

export const USER_REPLAYER_WORKFLOW_OPTION_KEYS = getZodKeys(USER_REPLAYER_WORKFLOW_OPTIONS);
export const USER_REPLAYER_PROCESS_OPTION_KEYS = getZodKeys(USER_REPLAYER_PROCESS_OPTIONS);

export const USER_REPLAYER_OPTIONS = z.object({
    ...USER_REPLAYER_WORKFLOW_OPTIONS.shape,
    ...USER_REPLAYER_PROCESS_OPTIONS.shape,
}).superRefine((data, ctx) => {
    if (data.lookaheadTimeSeconds !== undefined && data.observedPacketConnectionTimeout !== undefined
        && data.lookaheadTimeSeconds <= data.observedPacketConnectionTimeout) {
        ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: `lookaheadTimeSeconds (${data.lookaheadTimeSeconds}) must be strictly greater than observedPacketConnectionTimeout (${data.observedPacketConnectionTimeout})`,
            path: ['lookaheadTimeSeconds']
        });
    }
});

// Note: noWait is not included here as it is hardcoded to true in the workflow.
// The workflow manages snapshot completion polling separately via checkSnapshotStatus.
export const USER_CREATE_SNAPSHOT_WORKFLOW_OPTIONS = z.object({
    snapshotPrefix: z.string().default("").optional()
        .describe("Prefix for auto-generated snapshot names. When set, the snapshot name is '<snapshotPrefix>_<uniqueId>'. When empty, defaults to '<sourceLabel>_<uniqueId>'."),
    jvmArgs: z.string().default("").optional()
        .describe(JVM_ARGS_DESC),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
        .describe(LOGGING_CONFIG_OVERRIDE_DESC)
}).describe("Workflow-level options for snapshot creation, controlling naming and JVM configuration.");

export const USER_CREATE_SNAPSHOT_PROCESS_OPTIONS = z.object({
    indexAllowlist: z.array(z.string()).default([]).optional()
        .describe("Filters which indices are captured at the snapshot layer — evaluated by the source cluster when the snapshot is created. " +
            "Entries use the cluster's native multi-index expression syntax (the same format accepted by the _snapshot API's 'indices' field): " +
            "exact names (e.g. 'logs-2024-01'), wildcards (e.g. 'logs-*'), and exclusions via a leading '-' (e.g. '-*-archive'). " +
            "The entries are joined with commas and sent verbatim as the 'indices' field of the snapshot creation request; this is NOT a regex. " +
            "Only the matching indices end up in the snapshot, so downstream stages have no way to recover indices excluded here. " +
            "To further narrow which indices are migrated after the snapshot is taken (including with 'regex:' patterns), use the metadata or RFS indexAllowlist, " +
            "which filter client-side on the snapshot contents. " +
            "An empty list includes all indices."),
    maxSnapshotRateMbPerNode: z.number().default(0).optional()
        .describe("Maximum snapshot throughput in MB/s per data node. 0 means no rate limiting. Use to reduce I/O impact on the source cluster during snapshot creation."),
    compressionEnabled: z.boolean().default(false).optional()
        .describe("[Expert] Enables metadata compression for the snapshot. Must be set to false for Elasticsearch 1.x sources, as compressed snapshot metadata is not supported by the snapshot reader for that version."),
    includeGlobalState: z.boolean().default(true).optional()
        .describe("[Expert] Includes cluster global state (persistent settings, templates, etc.) in the snapshot. " +
            "Only disable if metadata migration encounters template processing issues that cannot be resolved via an allowlist."),
}).describe("Process-level options for the CreateSnapshot command, controlling which indices are snapshotted and rate limiting.");

export const USER_CREATE_SNAPSHOT_WORKFLOW_OPTION_KEYS = getZodKeys(USER_CREATE_SNAPSHOT_WORKFLOW_OPTIONS);
export const USER_CREATE_SNAPSHOT_PROCESS_OPTION_KEYS = getZodKeys(USER_CREATE_SNAPSHOT_PROCESS_OPTIONS);

export const USER_CREATE_SNAPSHOT_OPTIONS = z.object({
    ...USER_CREATE_SNAPSHOT_WORKFLOW_OPTIONS.shape,
    ...USER_CREATE_SNAPSHOT_PROCESS_OPTIONS.shape,
});

export const USER_METADATA_WORKFLOW_OPTIONS = z.object({
    jvmArgs: z.string().default("").optional()
        .describe(JVM_ARGS_DESC),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
        .describe(LOGGING_CONFIG_OVERRIDE_DESC),
    skipEvaluateApproval: z.boolean().default(false).optional()
        .describe("When true, skips the manual approval gate after the metadata evaluation step. The evaluation step analyzes what metadata changes would be applied without making changes."),
    skipMigrateApproval: z.boolean().default(false).optional()
        .describe("When true, skips the manual approval gate after the metadata migration step. The migration step applies the evaluated metadata changes to the target cluster.")
}).describe("Workflow-level options for metadata migration, controlling JVM settings and approval gates.");

export const USER_METADATA_PROCESS_OPTIONS = z.object({
    componentTemplateAllowlist: z.array(z.string()).default([]).optional()
        .describe("List of component template names to include in the metadata migration. " +
            "Each entry is either an exact name or a regex pattern prefixed with 'regex:'. " +
            "An empty list includes all non-system component templates."),
    indexAllowlist: z.array(z.string()).default([]).optional()
        .describe("Filters which indices are migrated at the metadata stage — evaluated client-side on the snapshot contents after the snapshot has been taken. " +
            "Each entry is either an exact index name (e.g. 'my-index') or a regex pattern prefixed with 'regex:' (e.g. 'regex:logs-.*'). " +
            "Applies only among indices already captured in the snapshot; to exclude an index from the snapshot itself, use the CreateSnapshot indexAllowlist. " +
            "An empty list includes all non-system indices."),
    indexTemplateAllowlist: z.array(z.string()).default([]).optional()
        .describe("List of index template names to include in the metadata migration. " +
            "Each entry is either an exact name or a regex pattern prefixed with 'regex:'. " +
            "An empty list includes all non-system index templates."),

    allowLooseVersionMatching: z.boolean().default(true).optional()
        .describe("[Expert] Allows migration between clusters with non-exact version compatibility (e.g. ES 7.x to OS 2.x). " +
            "Only disable if metadata has parsing issues on snapshots that require strict version matching."),
    clusterAwarenessAttributes: z.number().default(1).optional()
        .describe("Number of shard allocation awareness attributes to preserve during metadata migration. Controls how index settings related to cluster topology are handled."),
    multiTypeBehavior: z.enum(["NONE", "UNION", "SPLIT"]).default("NONE").optional()
        .describe("Strategy for handling Elasticsearch multi-type indices (ES 5.x and earlier). " +
            "'NONE': fail if multi-type indices are encountered. " +
            "'UNION': merge all types into a single mapping. " +
            "'SPLIT': create separate indices for each type."),
    otelCollectorEndpoint: OTEL_COLLECTOR_ENDPOINT,
    output: z.enum(["HUMAN_READABLE", "JSON"]).default("HUMAN_READABLE").optional()
        .describe("Output format for the metadata migration evaluation report. 'HUMAN_READABLE' for formatted text, 'JSON' for machine-parseable output."),
    transformerConfigBase64: z.string().default("").optional()
        .describe("Base64-encoded JSON transformer configuration." + METADATA_TRANSFORMER_SUFFIX),
    transformerConfig: z.string().optional()
        .describe("Inline JSON transformer configuration. Keys are transformer names and values are their configuration." + METADATA_TRANSFORMER_SUFFIX),
    transformerConfigFile: z.string().optional()
        .describe("Path to a JSON file containing transformer configuration." + METADATA_TRANSFORMER_SUFFIX + EXPERT_FILE_SUFFIX),
    enableSourcelessMigrations: z.boolean().default(false).optional()
        .describe("Enable migration of indices that have _source disabled or partially filtered (includes/excludes). " +
            "When enabled, document backfill will reconstruct documents from stored fields and doc_values. " +
            "Without this flag, metadata migration will fail if any selected index has _source disabled or partially filtered."),
    useRecoverySource: z.boolean().default(false).optional()
        .describe("When enabled, treat the _recovery_source stored field (present in ES 7+ / OpenSearch snapshots " +
            "with soft-deletes) as _source. This field is transient and may not be present for all documents, " +
            "so results can be inconsistent. Use only when reconstruction from doc_values and stored fields is insufficient."),
}).describe("Process-level options for the metadata migration command, controlling which metadata is migrated and how it is transformed.");

export const USER_METADATA_WORKFLOW_OPTION_KEYS = getZodKeys(USER_METADATA_WORKFLOW_OPTIONS);
export const USER_METADATA_PROCESS_OPTION_KEYS = getZodKeys(USER_METADATA_PROCESS_OPTIONS);

export const USER_METADATA_OPTIONS = z.object({
    ...USER_METADATA_WORKFLOW_OPTIONS.shape,
    ...USER_METADATA_PROCESS_OPTIONS.shape,
});

export const USER_RFS_WORKFLOW_OPTIONS = z.object({
    podReplicas: z.number().default(1).optional()
        .describe("Number of RFS worker pod replicas. Each replica independently acquires and processes snapshot shards in parallel —" + 
            " throughput scales linearly up to the total number of source shards."),
    jvmArgs: z.string().default("").optional()
        .describe(JVM_ARGS_DESC),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
        .describe(LOGGING_CONFIG_OVERRIDE_DESC),
    skipApproval: z.boolean().default(false).optional()
        .describe("When true, skips the manual approval gate after the document backfill completes. Useful for automated pipelines where human approval is not needed."),
    useTargetClusterForWorkCoordination: z.boolean().default(false)
        .describe("[Expert] When true, uses the target OpenSearch cluster for RFS work coordination (lease management and shard assignment). " +
            "When false (default), a dedicated single-node OpenSearch coordinator cluster is automatically deployed within the Kubernetes cluster, used for the lifetime of the migration, then torn down on completion. " +
            "Using a dedicated coordinator avoids adding coordination overhead to the target cluster."),
    resources: z.preprocess((v) => deepmerge(DEFAULT_RESOURCES.RFS, (v ?? {})), RESOURCE_REQUIREMENTS)
        .pipe(RESOURCE_REQUIREMENTS.extend({
            requests: RESOURCE_REQUIREMENTS.shape.requests.extend({
                "ephemeral-storage":
                    RESOURCE_REQUIREMENTS.shape.requests.shape["ephemeral-storage"].describe(
                        "Ephemeral storage for RFS temporary Lucene segments. If omitted, automatically computed as ceil(2.5 * maxShardSizeBytes) to ensure sufficient space for the largest shard."
                    ),
            }),
        }))
        .describe("Kubernetes resource limits and requests for the RFS container. " +
            "Partial overrides are deep-merged with the built-in defaults. " +
            "By default, limits equal requests, giving the pod 'Guaranteed' QoS (least likely to be evicted). " +
            "Setting requests lower than limits results in 'Burstable' QoS. " +
            "Ephemeral storage is auto-calculated from maxShardSizeBytes if not specified."),
}).describe("Kubernetes deployment-level options for the Reindex From Snapshot (RFS) document backfill.");

export const USER_RFS_PROCESS_OPTIONS = z.object({
    indexAllowlist: z.array(z.string()).default([]).optional()
        .describe("Filters which indices are migrated by the document backfill (RFS) — evaluated client-side on the snapshot contents after the snapshot has been taken. " +
            "Each entry is either an exact index name or a regex pattern prefixed with 'regex:' (e.g. 'regex:logs-.*'). " +
            "Applies only among indices already captured in the snapshot; to exclude an index from the snapshot itself, use the CreateSnapshot indexAllowlist. " +
            "An empty list includes all non-system indices from the snapshot.")
        .checksumFor('replayer')
        .changeRestriction('impossible'),
    allowLooseVersionMatching: z.boolean().default(true).optional()
        .describe("[Expert] Allows document migration between clusters with non-exact version compatibility. " +
            "Only disable if snapshot parsing issues require strict version matching.")
        .checksumFor('replayer')
        .changeRestriction('impossible'),
    docTransformerConfigBase64: z.string().default("").optional()
        .describe("Base64-encoded JSON transformer configuration." + DOC_TRANSFORMER_SUFFIX)
        .checksumFor('replayer')
        .changeRestriction('impossible'),
    docTransformerConfig: z.string().optional()
        .describe("Inline JSON transformer configuration. Keys are transformer names and values are their configuration." + DOC_TRANSFORMER_SUFFIX)
        .checksumFor('replayer')
        .changeRestriction('impossible'),
    docTransformerConfigFile: z.string().optional()
        .describe("Path to a JSON file containing transformer configuration." + DOC_TRANSFORMER_SUFFIX + EXPERT_FILE_SUFFIX)
        .checksumFor('replayer')
        .changeRestriction('impossible'),
    documentsPerBulkRequest: z.number().default(0x7fffffff).optional()
        .describe("Maximum number of documents per bulk indexing request to the target cluster. Lower values reduce per-request latency but increase overhead."),
    documentsSizePerBulkRequest: z.number().default(10*1024*1024).optional()
        .describe("Maximum aggregate document size in bytes per bulk indexing request. Individual documents larger than this limit are sent as single-document requests."),
    initialLeaseDuration: z.string()
        .regex(/^[-+]?P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/)
        .default("PT1H").optional()
        .describe("[Expert] ISO 8601 duration for the initial work item lease in the coordination store (e.g. 'PT1H' = 1 hour, 'PT10M' = 10 minutes). " +
            "If a worker fails to complete a shard within this duration, the lease expires and another worker can pick it up, doubling the lease duration on each retry. " +
            "Increase for very large shards (>200GB) to reduce the number of re-downloads per shard needed to complete the migration.")
        .changeRestriction('gated'),
    maxConnections: z.number().default(10).optional()
        .describe("Maximum number of concurrent HTTP connections from each RFS worker to the target cluster for bulk indexing.")
        .changeRestriction('gated'),
    maxShardSizeBytes: z.number().default(80*1024*1024*1024).optional()
        .describe("Expected maximum shard size in bytes. Used to auto-calculate ephemeral storage requirements as ceil(2.5 * maxShardSizeBytes). Set this to match your largest shard to ensure sufficient disk space for Lucene segment processing.")
        .changeRestriction('gated'),
    otelCollectorEndpoint: OTEL_COLLECTOR_ENDPOINT,
    serverGeneratedIds: z.enum(["AUTO", "ALWAYS", "NEVER"]).default("AUTO").optional()
        .describe("Controls document ID generation on the target. " +
            "'AUTO': auto-detect serverless TIMESERIES/VECTOR collections and enable server-generated IDs. " +
            "'ALWAYS': always use server-generated IDs (discards source IDs). " +
            "'NEVER': always preserve source document IDs (may fail on serverless TIMESERIES/VECTOR collections)."),
    allowedDocExceptionTypes: z.array(z.string()).default([]).optional()
        .describe("List of document-level exception types to treat as successful operations during bulk migration. " +
            "Documents that fail with these errors are not retried and not counted as failures — they are silently accepted. " +
            "Use this for idempotent migrations where certain errors are expected and harmless. " +
            "For example, set to ['version_conflict_engine_exception'] when migrating into a data stream " +
            "where backing index writes may conflict with existing documents. " +
            "Defaults to empty (all errors are treated as failures). " +
            "See BulkDocErrorTypes for common OpenSearch exception type strings."),
    coordinatorRetryMaxRetries: z.number().default(7).optional()
        .describe("[Expert] Maximum number of retries when marking work items as completed on the coordinator."),
    coordinatorRetryInitialDelayMs: z.number().default(1000).optional()
        .describe("[Expert] Initial delay in milliseconds for coordinator completion retries. Doubles with each attempt up to coordinatorRetryMaxDelayMs."),
    coordinatorRetryMaxDelayMs: z.number().default(64000).optional()
        .describe("[Expert] Maximum delay in milliseconds for any single coordinator completion retry."),
    enableSourcelessMigrations: z.boolean().default(false).optional()
        .describe("Enable migration of indices that have _source disabled or partially filtered (includes/excludes). " +
            "When enabled, documents are reconstructed from stored fields and doc_values instead of _source. " +
            "Without this flag, migration of sourceless indices will fail with an error.")
        .checksumFor('replayer')
        .changeRestriction('impossible'),
    useRecoverySource: z.boolean().default(false).optional()
        .describe("When enabled, treat the _recovery_source stored field (present in ES 7+ / OpenSearch snapshots " +
            "with soft-deletes) as _source. This field is transient and may not be present for all documents, " +
            "so results can be inconsistent. Use only when reconstruction from doc_values and stored fields is insufficient.")
        .checksumFor('replayer')
        .changeRestriction('impossible'),
}).describe("Process-level options for the RFS document backfill command, controlling indexing behavior, concurrency, and transformations.");

export const USER_RFS_WORKFLOW_OPTION_KEYS = getZodKeys(USER_RFS_WORKFLOW_OPTIONS);
export const USER_RFS_PROCESS_OPTION_KEYS = getZodKeys(USER_RFS_PROCESS_OPTIONS);

export const USER_RFS_OPTIONS = z.object({
    ...USER_RFS_WORKFLOW_OPTIONS.shape,
    ...USER_RFS_PROCESS_OPTIONS.shape,
})
    .transform((data) => {
        const requestEphemeral = data.resources?.requests?.["ephemeral-storage"];
        const userRequestEphemeralStorageBytes = requestEphemeral
            ? parseK8sQuantity(requestEphemeral)
            : undefined;
        const limitsEphemeral = data.resources?.limits?.["ephemeral-storage"];
        const userLimitsEphemeralStorageBytes = limitsEphemeral
            ? parseK8sQuantity(limitsEphemeral)
            : undefined;
        const minimumEphemeralBytes = Math.ceil(2.5 * (data.maxShardSizeBytes??0))

        if (userRequestEphemeralStorageBytes != undefined && userRequestEphemeralStorageBytes < minimumEphemeralBytes) {
            throw new Error(
                `Resource requests for RFS storage of ${userRequestEphemeralStorageBytes} is too low to support maxShardSizeBytes value of ${data.maxShardSizeBytes}.
            Increase to at least ${minimumEphemeralBytes} to support the migration or decrease maxShardSizeBytes`)
        }

        if (userLimitsEphemeralStorageBytes != undefined && userLimitsEphemeralStorageBytes < minimumEphemeralBytes) {
            throw new Error(
                `Resource limits for RFS storage of ${userLimitsEphemeralStorageBytes} is too low to support maxShardSizeBytes value of ${data.maxShardSizeBytes}.
            Increase to at least ${minimumEphemeralBytes} to support the migration or decrease maxShardSizeBytes`)
        }

        // pick the larger of (user, minimum); if neither present fall back to minimum
        const reqBytes = Math.max(userRequestEphemeralStorageBytes ?? 0, minimumEphemeralBytes);
        // ensure limits >= requests; if user set a higher limit, keep it
        const limBytes = Math.max(userLimitsEphemeralStorageBytes ?? 0, reqBytes);

        return deepmerge(
            data,
            {
                resources: {
                    requests: { "ephemeral-storage": Math.ceil(reqBytes / 1024) + "Ki" },
                    limits: { "ephemeral-storage": Math.ceil(limBytes / 1024) + "Ki" },
                },
            },
        );
});

export const KAFKA_CLUSTER_CREATION_CONFIG = z.preprocess(
    (value) => deepmerge(DEFAULT_AUTO_CREATE_KAFKA, (value ?? {}), {arrayMerge: replaceArrayMerge}),
    z.object({
        auth: KAFKA_AUTO_CREATE_AUTH_CONFIG.optional()
            .describe("Workflow-owned Kafka client auth for auto-created Strimzi clusters. "
                + "If omitted, transform-time resolution currently defaults workflow-managed Kafka to "
                + "`scram-sha-512` as the secure-by-default policy. This default is intentionally "
                + "resolved outside the deep-merged Strimzi object defaults so auth policy can change "
                + "without being hidden inside the structural Kafka/NodePool/Topic default merge."),
        // Intended contract: users provide Strimzi-shaped partial Kafka.spec values and
        // initialization deep-merges them with the baseline defaults above.
        //
        // Current limitation: some workflow templates still render selected nested
        // Strimzi fields explicitly instead of passing the merged subtree through
        // verbatim, because Argo resource-manifest rendering has proven unreliable
        // for certain whole-object injections. That means new Strimzi fields may
        // require workflow-template updates before they can flow end to end.
        //
        // Future direction: move Strimzi resource application onto a rendering/apply
        // path that can preserve full merged subtrees without unpacking them into
        // explicit Argo parameters, restoring better fidelity as Strimzi evolves.
        clusterSpecOverrides: GENERIC_JSON_OBJECT.optional()
            .describe("Optional overrides merged into the generated Strimzi Kafka.spec. " +
                "Workflow-managed fields such as resource names, required listeners, and workflow-owned auth settings may be overwritten by the workflow."),
        nodePoolSpecOverrides: GENERIC_JSON_OBJECT.optional()
            .describe("Optional overrides merged into the generated Strimzi KafkaNodePool.spec. " +
                "Workflow-managed fields such as cluster labels may be overwritten by the workflow."),
        topicSpecOverrides: GENERIC_JSON_OBJECT.optional()
            .describe("Optional overrides merged into generated Strimzi KafkaTopic.spec values for workflow-created topics."),
    }).describe("Workflow-managed Strimzi Kafka cluster creation. Structural defaults for broker config, node pool, and topic settings are deep-merged here, while the auth default is resolved separately during transform-time policy application.")
);

export const KAFKA_CLUSTER_CONFIG = z.union([
    z.object({autoCreate: KAFKA_CLUSTER_CREATION_CONFIG})
        .describe("Auto-create a new Strimzi Kafka cluster with the specified configuration. " +
            "The cluster bootstrap service is available at '<clusterName>-kafka-bootstrap.<namespace>:9092'."),
    z.object({existing: KAFKA_EXISTING_CLUSTER_CONFIG })
        .describe("Use an existing Kafka cluster by providing connection details.")
]).describe("Kafka cluster configuration: either auto-create a new Strimzi cluster or connect to an existing one.");

export const HTTP_AUTH_BASIC = z.object({
    basic: z.object({
        secretName: z.string().regex(K8S_NAMING_PATTERN)
            .describe("Name of a Kubernetes Secret containing 'username' and 'password' keys for HTTP Basic authentication.")
    })
}).describe("HTTP Basic authentication using credentials from a Kubernetes Secret.");

export const HTTP_AUTH_SIGV4 = z.object({
    sigv4: z.object({
        region: z.string()
            .describe("AWS region for SigV4 request signing (e.g. 'us-east-1')."),
        service: z.string().default("es").optional()
            .describe("AWS service name for SigV4 signing. Use 'es' for Amazon OpenSearch Service or 'aoss' for OpenSearch Serverless."),
    })
}).describe("AWS SigV4 request signing authentication. Uses the pod's IAM role credentials via EKS Pod Identity.");

export const HTTP_AUTH_MTLS = z.object({
    mtls: z.object({
        caCert: z.string()
            .describe("PEM-encoded CA certificate or path to CA certificate file for verifying the server's TLS certificate."),
        clientSecretName: z.string()
            .describe("Name of a Kubernetes TLS Secret containing the client certificate and private key for mutual TLS authentication.")
    })
}).describe("Mutual TLS (mTLS) authentication using client certificates.");

export const CLUSTER_VERSION_STRING = z.string().regex(/^(?:ES [125678]|OS [123]|SOLR [6789])(?:\.[0-9]+)+$/)
    .describe("Cluster version string in '<ENGINE> <VERSION>' format. Supported engines: 'ES' (Elasticsearch) versions 1, 2, 5, 6, 7, 8; 'OS' (OpenSearch) versions 1, 2, 3; 'SOLR' (Apache Solr) versions 6, 7, 8, 9. Examples: 'ES 7.10.2', 'OS 2.11.0', 'SOLR 9.7.0', 'SOLR 6.6.6'.");

export const CLUSTER_CONFIG = z.object({
    endpoint:  z.string().regex(new RegExp(OPTIONAL_HTTP_ENDPOINT_PATTERN)).default("").optional()
        .describe("HTTP(S) endpoint URL for the cluster (e.g. 'https://my-cluster:9200/'). Leave empty if the cluster is not directly accessible or will be accessed through a proxy."),
    allowInsecure: z.boolean().default(false).optional()
        .describe("When true, disables TLS certificate verification when connecting to the cluster. Use only for development or self-signed certificates."),
    authConfig: z.union([HTTP_AUTH_BASIC, HTTP_AUTH_SIGV4, HTTP_AUTH_MTLS]).optional()
        .describe("Authentication configuration for connecting to the cluster. Supports HTTP Basic (Kubernetes Secret), AWS SigV4, or mutual TLS."),
}).describe("Base connection configuration for an Elasticsearch or OpenSearch cluster.");

export const TARGET_CLUSTER_CONFIG = CLUSTER_CONFIG.extend({
    endpoint:  z.string().regex(new RegExp(HTTP_ENDPOINT_PATTERN))
        .describe("HTTP(S) endpoint URL for the target cluster (e.g. 'https://target-cluster:9200/'). Required for target clusters."),
}).describe("Connection configuration for a target OpenSearch cluster. Extends the base cluster config with a required endpoint.");

export const SOURCE_CLUSTER_REPOS_RECORD =
    z.record(z.string(), S3_REPO_CONFIG)
    .describe("Map of snapshot repository names to their S3 configurations. Keys are the repository names as registered in the source cluster.");

export const CAPTURE_CONFIG = z.object({
    kafka: z.string().regex(K8S_NAMING_PATTERN).default("default").optional()
        .describe("Label of the Kafka cluster to use for captured traffic. Must match a key in kafkaClusterConfiguration."),
    kafkaTopic: z.string().regex(K8S_NAMING_PATTERN).default("").optional()
        .describe("Kafka topic name for captured traffic. If empty, defaults to the proxy name (the key in the proxies record)."),
    source: z.string()
        .describe("Name of the source cluster this proxy sits in front of. Must match a key in sourceClusters."),
    proxyConfig: USER_PROXY_OPTIONS
        .describe("Configuration for the capture proxy deployment and process options.")
}).describe("Configuration for a single capture proxy instance, including its Kafka topic and source cluster binding.");

export const SNAPSHOT_MIGRATION_FILTER = z.object({
    source: z.string()
        .describe("Name of the source cluster. Must match a key in sourceClusters."),
    snapshot: z.string()
        .describe("Name of the snapshot. Must match a key in the source cluster's snapshotInfo.snapshots.")
}).describe("Reference to a specific snapshot from a specific source cluster, used to express dependencies.");

export const REPLAYER_CONFIG = z.object({
    fromProxy: z.string()
        .describe("Name of the capture proxy to replay traffic from. Must match a key in traffic.proxies."),
    toTarget: z.string()
        .describe("Name of the target cluster to replay traffic to. Must match a key in targetClusters."),
    dependsOnSnapshotMigrations: z.array(SNAPSHOT_MIGRATION_FILTER).default([]).optional()
        .describe("List of snapshot migrations that must complete before this replayer starts. Ensures data consistency when replaying traffic that depends on backfilled data."),
    replayerConfig: USER_REPLAYER_OPTIONS.optional()
        .describe("Optional replayer configuration overrides. If omitted, replayer runs with schema defaults.")
}).describe("Configuration for a single traffic replayer instance, binding a proxy's captured traffic to a target cluster.");

export const TRAFFIC_CONFIG = z.object({
    proxies: z.record(z.string().regex(K8S_NAMING_PATTERN), CAPTURE_CONFIG)
        .describe("Map of proxy names to their capture configurations. Keys become the Kubernetes Service names and must be valid DNS labels."),
    replayers: z.record(z.string(), REPLAYER_CONFIG)
        .describe("Map of replayer names to their replay configurations. Each replayer consumes from a proxy's Kafka topic and replays to a target cluster.")
}).superRefine((data, ctx) => {
    for (const [name, rc] of Object.entries(data.replayers)) {
        if (!(rc.fromProxy in data.proxies)) {
            ctx.addIssue({
                code: z.ZodIssueCode.custom,
                message: `Replayer '${name}' references unknown proxy '${rc.fromProxy}'. Available: ${Object.keys(data.proxies).join(', ')}`,
                path: ['replayers', name, 'fromProxy']
            });
        }
    }
});

export const EXTERNALLY_MANAGED_SNAPSHOT = z.object({
    externallyManagedSnapshotName: z.string()
        .describe("Name of a pre-existing snapshot in the source cluster's repository. The workflow will use this snapshot directly without creating a new one.")
}).describe("Reference to a snapshot that was created outside of this migration workflow.");

export const GENERATE_SNAPSHOT = z.object({
    createSnapshotConfig: USER_CREATE_SNAPSHOT_OPTIONS
        .describe("Configuration for creating a new snapshot of the source cluster."),
}).describe("Configuration to create a new snapshot of the source cluster as part of the migration workflow.");

export const SNAPSHOT_NAME_CONFIG = z.union([
    EXTERNALLY_MANAGED_SNAPSHOT, GENERATE_SNAPSHOT
]).describe("Snapshot source: either reference an existing snapshot or configure creation of a new one.");

export const NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG = z.object({
    config: SNAPSHOT_NAME_CONFIG
        .describe("Snapshot configuration: either an externally managed snapshot name or settings to create a new snapshot."),
    repoName: z.string()
        .describe("Name of the S3 snapshot repository. Must match a key in the source cluster's snapshotInfo.repos.")
}).describe("A snapshot configuration bound to a specific repository.");

export const SNAPSHOT_CONFIGS_MAP = z.record(
    z.string(),
    NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG
).describe("Map of snapshot names to their configurations. Keys are used as labels and in snapshot name generation.");

export const SNAPSHOT_INFO = z.object({
    repos: SOURCE_CLUSTER_REPOS_RECORD.optional()
        .describe("S3 snapshot repositories registered with the source cluster."),
    snapshots: SNAPSHOT_CONFIGS_MAP
        .describe("Snapshots to use or create for this source cluster."),
    serializeSnapshotCreation: z.boolean().optional()
        .describe("Controls whether snapshot creations for this source run one-at-a-time or in parallel. " +
            "When true, all snapshot creations share a single semaphore so only one runs at any given time; " +
            "when false, each snapshot can run in parallel with others for this source. " +
            "When omitted, defaults to true for legacy sources (ES 1-7, OS 1) and false for modern sources (ES 8+, OS 2+). " +
            "Set explicitly to override the version-based default. " +
            "Common reason to force true on a modern source: the cluster only supports one snapshot at a time for the indices being captured " +
            "(for example, OpenSearch UltraWarm indices, which only allow a single index per snapshot and cannot be snapshotted concurrently).")
}).describe("Snapshot repository and snapshot configuration for a source cluster.");

const AWS_MANAGED_ENDPOINT_PATTERN = /(?:\.es\.amazonaws\.com|\.aos\.[a-z0-9-]+\.on\.aws)(?::\d+)?(?:\/)?$/i;

export const SOURCE_CLUSTER_CONFIG = CLUSTER_CONFIG.extend({
    version: CLUSTER_VERSION_STRING,
    snapshotInfo: SNAPSHOT_INFO.optional()
        .describe("Snapshot repository and snapshot configurations for this source cluster. Required if any snapshot-based migrations reference this source.")
}).describe("Connection and snapshot configuration for a source Elasticsearch or OpenSearch cluster.").superRefine((data, ctx) => {
    const repos = data.snapshotInfo?.repos;
    const snapshots = data.snapshotInfo?.snapshots ?? {};
    for (const [snapName, snapConfig] of Object.entries(snapshots)) {
        const repoName = snapConfig.repoName;
        if (repoName) {
            if (!repos) {
                ctx.addIssue({
                    code: z.ZodIssueCode.custom,
                    message: `Snapshot '${snapName}' references repoName '${repoName}' but no repos are defined`,
                    path: ['snapshotInfo', 'snapshots', snapName, 'repoName']
                });
            } else if (!(repoName in repos)) {
                ctx.addIssue({
                    code: z.ZodIssueCode.custom,
                    message: `Snapshot '${snapName}' references unknown repoName '${repoName}'. Available: ${Object.keys(repos).join(', ')}`,
                    path: ['snapshotInfo', 'snapshots', snapName, 'repoName']
                });
            }
        }
    }

    // AWS managed clusters require SigV4 auth when triggering snapshot creation
    if (data.endpoint && AWS_MANAGED_ENDPOINT_PATTERN.test(data.endpoint)) {
        const hasCreateSnapshot = Object.values(snapshots).some(s => "createSnapshotConfig" in s.config);
        if (hasCreateSnapshot && (!data.authConfig || !HTTP_AUTH_SIGV4.safeParse(data.authConfig).success)) {
            ctx.addIssue({
                code: z.ZodIssueCode.custom,
                message: "SigV4 auth is required for Amazon OpenSearch domains when the workflow creates snapshot",
                path: ['authConfig']
            });
        }
    }

    // SigV4 auth + createSnapshotConfig requires s3RoleArn on the referenced repo
    if (data.authConfig && HTTP_AUTH_SIGV4.safeParse(data.authConfig).success) {
        for (const [snapName, snapConfig] of Object.entries(snapshots)) {
            if ("createSnapshotConfig" in snapConfig.config) {
                const repo = repos?.[snapConfig.repoName];
                if (repo && !repo.s3RoleArn) {
                    ctx.addIssue({
                        code: z.ZodIssueCode.custom,
                        message: `Snapshot '${snapName}' uses SigV4 auth with createSnapshotConfig but repo '${snapConfig.repoName}' is missing s3RoleArn`,
                        path: ['snapshotInfo', 'repos', snapConfig.repoName, 's3RoleArn']
                    });
                }
            }
        }
    }
});

export const NORMALIZED_COMPLETE_SNAPSHOT_CONFIG = z.object({
    snapshotName: z.string()
        .describe("Resolved name of the snapshot to use for migration.")
}).describe("A fully resolved snapshot configuration with a concrete snapshot name.");

export const USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG = z.object({
    label: z.string().regex(/^[a-zA-Z][a-zA-Z0-9]*/).default("").optional()
        .describe("Unique label for this migration within its snapshot group. Auto-generated as 'migration-<index>' if not specified. Must start with a letter and contain only alphanumeric characters."),
    metadataMigrationConfig: USER_METADATA_OPTIONS.optional()
        .describe("Configuration for migrating index metadata (mappings, settings, templates) from the snapshot to the target. Omit to skip metadata migration."),
    documentBackfillConfig: USER_RFS_OPTIONS.optional()
        .describe("Configuration for backfilling documents from the snapshot to the target using Reindex From Snapshot. Omit to skip document backfill."),
}).describe("Configuration for a single migration pass from a snapshot. At least one of metadataMigrationConfig or documentBackfillConfig must be provided.").refine(data =>
        data.metadataMigrationConfig !== undefined ||
        data.documentBackfillConfig !== undefined,
    {message: "At least one of metadataMigrationConfig or documentBackfillConfig must be provided"});

export const SNAPSHOT_MIGRATION_CONFIG_ARRAY =
    z.array(USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG)
    .describe("List of migrations to execute for a single snapshot. " +
        " Each migration must configure metadata migration, document backfill, or both." +
        " These migrations will execute concurrently as dependent snapshots finish.");

export const PER_SNAPSHOT_MIGRATION_CONFIG_RECORD =
    z.record(z.string().regex(/^[a-zA-Z][a-zA-Z0-9]*/),
        SNAPSHOT_MIGRATION_CONFIG_ARRAY.min(1))
    .describe("Map of snapshot names to their migration configurations. Keys must match snapshot names defined in the source cluster's snapshotInfo.snapshots.");

export const NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG = z.object({
    skipApprovals : z.boolean().default(false).optional()
        .describe("When true, skips all manual approval gates for migrations in this configuration block."),
    fromSource: z.string()
        .describe("Label of the source cluster to migrate from. Must match a key in sourceClusters."),
    toTarget: z.string()
        .describe("Label of the target cluster to migrate to. Must match a key in targetClusters."),
    perSnapshotConfig: PER_SNAPSHOT_MIGRATION_CONFIG_RECORD
        .describe("Per-snapshot migration configurations. Each entry maps a snapshot name to one or more migration passes (metadata + document backfill)."),
}).describe("A snapshot-based migration configuration binding a source cluster to a target cluster with per-snapshot migration settings.").superRefine((data, ctx) => {
    if (!data.perSnapshotConfig) return;
    for (const [snapName, migrations] of Object.entries(data.perSnapshotConfig)) {
        const labels = migrations.map(m => m.label).filter(Boolean);
        if (labels.length !== new Set(labels).size) {
            ctx.addIssue({
                code: z.ZodIssueCode.custom,
                message: `Duplicate labels in perSnapshotConfig['${snapName}']`,
                path: ['perSnapshotConfig', snapName]
            });
        }
    }
});

export const KAFKA_CLUSTERS_MAP = z.record(z.string().regex(K8S_NAMING_PATTERN), KAFKA_CLUSTER_CONFIG)
    .describe("Map of Kafka cluster names to their configurations. Keys become Kubernetes resource names and must be valid DNS labels. If empty and proxies are configured, a 'default' auto-created cluster is used.");
export const SOURCE_CLUSTERS_MAP = z.record(z.string(), SOURCE_CLUSTER_CONFIG)
    .describe("Map of source cluster names to their configurations. Keys are used as labels throughout the migration workflow.");
export const TARGET_CLUSTERS_MAP = z.record(z.string(), TARGET_CLUSTER_CONFIG)
    .describe("Map of target cluster names to their configurations. Keys are used as labels and must be referenced by snapshotMigrationConfigs and traffic replayers.");

export const OVERALL_MIGRATION_CONFIG = //validateOptionalDefaultConsistency
(
    z.object({
        skipApprovals : z.boolean().default(false).optional()
            .describe("Global flag to skip all manual approval gates across the entire migration. When true, overrides all per-component skipApproval settings."),
        kafkaClusterConfiguration: KAFKA_CLUSTERS_MAP.default({}).optional()
            .describe("Kafka cluster configurations. If empty and traffic capture is configured, a default ephemeral Kafka cluster is auto-created for each referenced cluster label. " +
                "Each entry defines a Kafka cluster (auto-created or external) referenced by proxies via 'kafka'."),
        sourceClusters: SOURCE_CLUSTERS_MAP
            .describe("Source Elasticsearch or OpenSearch clusters to migrate from."),
        targetClusters: TARGET_CLUSTERS_MAP
            .describe("Target OpenSearch clusters to migrate to."),
        snapshotMigrationConfigs: z.array(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG)
            .describe("List of snapshot-based migration configurations. Each entry binds a source cluster to a target cluster and defines which snapshots to migrate with what settings."),
        traffic: TRAFFIC_CONFIG
            .describe("Traffic capture and replay configuration. Proxies capture live traffic from source clusters to Kafka, and replayers consume from Kafka to replay against target clusters. " +
                "All top-level items are independent, but replayers can declare dependencies on snapshot migrations to ensure data consistency.")
            .optional()
    }).describe("Top-level migration configuration defining source clusters, target clusters, snapshot migrations, and optional traffic capture/replay.").superRefine((data, ctx) => {
        for (let i = 0; i < data.snapshotMigrationConfigs.length; i++) {
            const mc = data.snapshotMigrationConfigs[i];

            if (!(mc.fromSource in data.sourceClusters)) {
                ctx.addIssue({
                    code: z.ZodIssueCode.custom,
                    message: `snapshotMigrationConfigs[${i}] references unknown source '${mc.fromSource}'. Available: ${Object.keys(data.sourceClusters).join(', ')}`,
                    path: ['snapshotMigrationConfigs', i, 'fromSource']
                });
            }

            if (!(mc.toTarget in data.targetClusters)) {
                ctx.addIssue({
                    code: z.ZodIssueCode.custom,
                    message: `snapshotMigrationConfigs[${i}] references unknown target '${mc.toTarget}'. Available: ${Object.keys(data.targetClusters).join(', ')}`,
                    path: ['snapshotMigrationConfigs', i, 'toTarget']
                });
            }

            if (mc.perSnapshotConfig) {
                const sourceCluster = data.sourceClusters[mc.fromSource];
                const availableSnapshots = sourceCluster?.snapshotInfo?.snapshots ?? {};
                for (const snapName of Object.keys(mc.perSnapshotConfig)) {
                    if (!(snapName in availableSnapshots)) {
                        ctx.addIssue({
                            code: z.ZodIssueCode.custom,
                            message: `perSnapshotConfig references unknown snapshot '${snapName}' in source '${mc.fromSource}'. Available: ${Object.keys(availableSnapshots).join(', ') || '(none)'}`,
                            path: ['snapshotMigrationConfigs', i, 'perSnapshotConfig', snapName]
                        });
                    }
                }
            }
        }

        if (data.traffic) {
            for (const [proxyName, proxyConfig] of Object.entries(data.traffic.proxies)) {
                if (!(proxyConfig.source in data.sourceClusters)) {
                    ctx.addIssue({
                        code: z.ZodIssueCode.custom,
                        message: `Proxy '${proxyName}' references unknown source '${proxyConfig.source}'. Available: ${Object.keys(data.sourceClusters).join(', ')}`,
                        path: ['traffic', 'proxies', proxyName, 'source']
                    });
                }
                const kafkaRef = proxyConfig.kafka;
                const kafkaClusters = data.kafkaClusterConfiguration ?? {};
                if (kafkaRef && Object.keys(kafkaClusters).length > 0 && !(kafkaRef in kafkaClusters)) {
                    ctx.addIssue({
                        code: z.ZodIssueCode.custom,
                        message: `Proxy '${proxyName}' references unknown kafka cluster '${kafkaRef}'. Available: ${Object.keys(kafkaClusters).join(', ')}`,
                        path: ['traffic', 'proxies', proxyName, 'kafka']
                    });
                }
            }

            for (const [replayerName, rc] of Object.entries(data.traffic.replayers)) {
                if (!(rc.toTarget in data.targetClusters)) {
                    ctx.addIssue({
                        code: z.ZodIssueCode.custom,
                        message: `Replayer '${replayerName}' references unknown target '${rc.toTarget}'. Available: ${Object.keys(data.targetClusters).join(', ')}`,
                        path: ['traffic', 'replayers', replayerName, 'toTarget']
                    });
                }

                for (let j = 0; j < (rc.dependsOnSnapshotMigrations?.length ?? 0); j++) {
                    const dep = rc.dependsOnSnapshotMigrations![j];
                    if (!(dep.source in data.sourceClusters)) {
                        ctx.addIssue({
                            code: z.ZodIssueCode.custom,
                            message: `Replayer '${replayerName}' dependsOnSnapshotMigrations[${j}] references unknown source '${dep.source}'. Available: ${Object.keys(data.sourceClusters).join(', ')}`,
                            path: ['traffic', 'replayers', replayerName, 'dependsOnSnapshotMigrations', j, 'source']
                        });
                    }
                }

                // When the target has sigv4 or basic auth, the workflow auto-derives the replayer's auth
                // at deploy time. Setting removeAuthHeader alongside that causes a dual-auth startup crash.
                const targetCluster = data.targetClusters[rc.toTarget];
                if (targetCluster && rc.replayerConfig && rc.replayerConfig.removeAuthHeader === true) {
                    const targetHasSigv4 = targetCluster.authConfig && HTTP_AUTH_SIGV4.safeParse(targetCluster.authConfig).success;
                    const targetHasBasicAuth = targetCluster.authConfig && HTTP_AUTH_BASIC.safeParse(targetCluster.authConfig).success;
                    if (targetHasSigv4 || targetHasBasicAuth) {
                        const targetAuthType = targetHasSigv4 ? 'sigv4' : 'basic';
                        ctx.addIssue({
                            code: z.ZodIssueCode.custom,
                            message: `Replayer '${replayerName}' sets 'removeAuthHeader' but target '${rc.toTarget}' uses ${targetAuthType} auth. ` +
                                `The replayer cannot use both — the target's auth is applied automatically. Remove 'removeAuthHeader' from replayerConfig.`,
                            path: ['traffic', 'replayers', replayerName, 'replayerConfig', 'removeAuthHeader']
                        });
                    }
                }
            }
        }
    })
);
