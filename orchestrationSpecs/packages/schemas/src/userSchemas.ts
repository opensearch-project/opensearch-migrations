import {z} from "zod";
import { DEFAULT_RESOURCES, parseK8sQuantity } from "./schemaUtilities";
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

export const KAFKA_CLIENT_CONFIG = z.object({
    enableMSKAuth: z.boolean().default(false).optional()
        .describe("Enable SASL/IAM authentication for Amazon MSK. When true, configures the Kafka client with the required SASL properties for IAM-based authentication."),
    kafkaConnection: z.string()
        .describe("Comma-delimited list of Kafka broker addresses in 'HOSTNAME:PORT' format (e.g. 'broker1:9092,broker2:9092'). " +
            "If empty, a Kafka cluster is automatically created and this value is populated.")
        .regex(new RegExp(`^(?:[a-z0-9][-a-z0-9.]*:${PORT_NUMBER_PATTERN}(?:,(?!$)|$))*$`)),
    kafkaTopic: z.string()
        .describe("Kafka topic name for captured traffic. If empty, defaults to the name of the associated proxy or target label.")
        .default(""),
}).describe("Client connection configuration for an existing Kafka cluster.");

export const K8S_NAMING_PATTERN = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$/;

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
]).describe("TLS configuration for the capture proxy. Determines how the proxy obtains its TLS certificate for HTTPS termination.");

export const USER_PROXY_WORKFLOW_OPTIONS = z.object({
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
        .describe("Name of a Kubernetes ConfigMap containing a custom Log4j configuration. When set, the ConfigMap is mounted into the container to override logging behavior."),
    internetFacing: z.boolean().default(false).optional()
        .describe("When true, the proxy's Kubernetes Service is annotated with 'internet-facing' load balancer scheme, making it accessible from outside the VPC."),
    podReplicas: z.number().default(1).optional()
        .describe("Number of proxy pod replicas in the Kubernetes Deployment. Increase for higher throughput or availability."),
    resources: z.preprocess((v) => deepmerge(DEFAULT_RESOURCES.PROXY, (v ?? {})), RESOURCE_REQUIREMENTS)
        .describe("Kubernetes resource limits and requests for the capture proxy container. Partial overrides are deep-merged with the built-in defaults (Guaranteed QoS).")
        .default(DEFAULT_RESOURCES.PROXY),
}).describe("Kubernetes deployment-level options for the capture proxy.");

export const USER_PROXY_PROCESS_OPTIONS = z.object({
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317").optional()
        .describe("URL for the OpenTelemetry Collector to which the proxy sends metrics and traces."),
    setHeader: z.array(z.string()).optional()
        .describe("List of static headers to add to proxied requests, each in 'Header-Name: value' format."),
    destinationConnectionPoolSize: z.number().default(0).optional()
        .describe("Maximum number of persistent connections to the destination (source) cluster. 0 means unlimited connection pooling."),
    destinationConnectionPoolTimeout: z.string()
        .regex(/^[-+]?P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/)
        .default("PT30S").optional()
        .describe("ISO 8601 duration for how long idle connections in the destination pool are kept alive before being closed (e.g. 'PT30S' = 30 seconds, 'PT5M' = 5 minutes)."),
    kafkaClientId: z.string().default("HttpCaptureProxyProducer").optional()
        .describe("Kafka producer client ID used when publishing captured traffic to Kafka. Useful for identifying this proxy in Kafka broker logs and metrics."),
    listenPort: z.number()
        .describe("TCP port the capture proxy listens on for incoming HTTP(S) traffic. This port is exposed via the Kubernetes Service and used to construct the proxy endpoint URL."),
    maxTrafficBufferSize: z.number().default(1048576).optional()
        .describe("Maximum size in bytes for buffering a single HTTP request/response payload before forwarding to Kafka."),
    noCapture: z.boolean().default(false).optional()
        .describe("When true, the proxy forwards traffic to the source cluster without capturing it to Kafka. Useful for TLS termination or routing without traffic recording."),
    numThreads: z.number().default(1).optional()
        .describe("Number of Netty worker threads for the proxy to handle concurrent connections."),
    sslConfigFile: z.string().optional()
        .describe("Path to a YAML file with OpenSearch security SSL configuration (plugins.security.ssl.http.* keys). Legacy option for non-Kubernetes deployments. For Kubernetes, prefer the 'tls' configuration instead."),
    tls: PROXY_TLS_CONFIG.optional()
        .describe("TLS certificate configuration for HTTPS termination at the proxy. When configured, the proxy serves HTTPS and the TLS secret is mounted at /etc/proxy-tls/. Mutually exclusive with sslConfigFile."),
    enableMSKAuth: z.boolean().default(false).optional()
        .describe("Enable SASL/IAM authentication for the proxy's Kafka producer when connecting to Amazon MSK."),
    suppressCaptureForHeaderMatch: z.array(z.string()).default([]).optional()
        .describe("List of header patterns. Requests matching any of these header patterns will be forwarded but not captured to Kafka."),
    suppressCaptureForMethod: z.string().default("").optional()
        .describe("HTTP method to suppress from capture (e.g. 'HEAD'). Requests with this method are forwarded but not recorded."),
    suppressCaptureForUriPath: z.string().default("").optional()
        .describe("URI path pattern to suppress from capture. Requests matching this path are forwarded but not recorded."),
    suppressMethodAndPath: z.string().default("").optional()
        .describe("Combined method and path pattern for capture suppression in 'METHOD /path' format."),
}).describe("Process-level configuration options for the capture proxy application. These are passed as command-line arguments to the proxy container.");

export const USER_PROXY_WORKFLOW_OPTION_KEYS = getZodKeys(USER_PROXY_WORKFLOW_OPTIONS);
export const USER_PROXY_PROCESS_OPTION_KEYS = getZodKeys(USER_PROXY_PROCESS_OPTIONS);

export const USER_PROXY_OPTIONS = z.object({
    ...USER_PROXY_WORKFLOW_OPTIONS.shape,
    ...USER_PROXY_PROCESS_OPTIONS.shape,
});

export const USER_REPLAYER_WORKFLOW_OPTIONS = z.object({
    jvmArgs: z.string().default("").optional()
        .describe("Additional JVM arguments passed to the replayer process via JDK_JAVA_OPTIONS (e.g. '-Xmx4g -XX:+UseG1GC')."),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
        .describe("Name of a Kubernetes ConfigMap containing a custom Log4j configuration for the replayer."),
    podReplicas: z.number().default(1).optional()
        .describe("Number of replayer pod replicas in the Kubernetes Deployment. Each replica independently consumes from Kafka and replays traffic to the target."),
    resources: z.preprocess((v) => deepmerge(DEFAULT_RESOURCES.REPLAYER, (v ?? {})), RESOURCE_REQUIREMENTS)
        .describe("Kubernetes resource limits and requests for the replayer container. Partial overrides are deep-merged with the built-in defaults (Guaranteed QoS)."),
}).describe("Kubernetes deployment-level options for the traffic replayer.");

export const USER_REPLAYER_PROCESS_OPTIONS = z.object({
    kafkaTrafficEnableMSKAuth: z.boolean().default(false).optional()
        .describe("Enable SASL/IAM authentication for the replayer's Kafka consumer when connecting to Amazon MSK."),
    kafkaTrafficPropertyFile: z.string().optional()
        .describe("Path to a Java properties file with additional or overridden Kafka consumer configuration. Mounted into the replayer container."),
    lookaheadTimeSeconds: z.number().default(400).optional()
        .describe("Number of seconds of captured traffic to buffer ahead of the current replay position. Must be strictly greater than observedPacketConnectionTimeout. Larger values improve throughput but increase memory usage."),
    maxConcurrentRequests: z.number().default(10000).optional()
        .describe("Maximum number of HTTP requests that can be in-flight simultaneously to the target cluster. Limits concurrency to prevent overwhelming the target."),
    numClientThreads: z.number().default(0).optional()
        .describe("Number of threads used to send replayed requests to the target. 0 uses the Netty event loop (typically number of available processors)."),
    observedPacketConnectionTimeout: z.number().default(360).optional()
        .describe("Seconds of inactivity on a captured connection before assuming it was terminated in the original traffic stream. Must be strictly less than lookaheadTimeSeconds."),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317").optional()
        .describe("URL for the OpenTelemetry Collector to which the replayer sends metrics and traces."),
    quiescentPeriodMs: z.number().default(5000).optional()
        .describe("Milliseconds to delay the first request on a resumed connection after a Kafka partition reassignment. Prevents request bursts during rebalancing."),
    removeAuthHeader: z.boolean().default(false).optional()
        .describe("Remove the Authorization header from replayed requests without replacing it. Useful when the target uses a different auth mechanism (e.g. SigV4) configured separately."),
    speedupFactor: z.number().default(1.1).optional()
        .describe("Multiplier to accelerate replay timing relative to the original captured traffic. 1.0 = real-time, 2.0 = double speed."),
    targetServerResponseTimeoutSeconds: z.number().default(150).optional()
        .describe("Maximum seconds to wait for a response from the target cluster before timing out a replayed request."),
    transformerConfig: z.string().optional()
        .describe("Inline request transformer configuration as a JSON string. Defines transformations applied to each request before replaying to the target."),
    transformerConfigEncoded: z.string().optional()
        .describe("Base64-encoded request transformer configuration. Alternative to transformerConfig for configurations containing special characters."),
    tupleTransformerConfig: z.string().optional()
        .describe("Inline tuple transformer configuration as a JSON string. Tuple transformers operate on request-response pairs for stateful transformations."),
    tupleTransformerConfigBase64: z.string().optional()
        .describe("Base64-encoded tuple transformer configuration."),
    userAgent: z.string().optional()
        .describe("String appended to the User-Agent header on all replayed requests to the target cluster. Useful for identifying replayed traffic in target cluster logs."),
}).describe("Process-level configuration options for the traffic replayer application. These control how captured traffic is consumed from Kafka and replayed to the target cluster.");

export const USER_REPLAYER_WORKFLOW_OPTION_KEYS = getZodKeys(USER_REPLAYER_WORKFLOW_OPTIONS);
export const USER_REPLAYER_PROCESS_OPTION_KEYS = getZodKeys(USER_REPLAYER_PROCESS_OPTIONS);

export const USER_REPLAYER_OPTIONS = z.object({
    ...USER_REPLAYER_WORKFLOW_OPTIONS.shape,
    ...USER_REPLAYER_PROCESS_OPTIONS.shape,
});

// Note: noWait is not included here as it is hardcoded to true in the workflow.
// The workflow manages snapshot completion polling separately via checkSnapshotStatus.
export const USER_CREATE_SNAPSHOT_WORKFLOW_OPTIONS = z.object({
    snapshotPrefix: z.string().default("").optional()
        .describe("Prefix for auto-generated snapshot names. The final snapshot name is constructed as '<sourceLabel>_<snapshotPrefix>_<uniqueRunNonce>'. If empty, the snapshot record key name is used as the prefix."),
    jvmArgs: z.string().default("").optional()
        .describe("Additional JVM arguments passed to the CreateSnapshot process via JDK_JAVA_OPTIONS (e.g. '-Xmx2g')."),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
        .describe("Name of a Kubernetes ConfigMap containing a custom Log4j configuration for the snapshot creation process.")
}).describe("Workflow-level options for snapshot creation, controlling naming and JVM configuration.");

export const USER_CREATE_SNAPSHOT_PROCESS_OPTIONS = z.object({
    indexAllowlist: z.array(z.string()).default([]).optional()
        .describe("List of index name patterns to include in the snapshot. An empty list means all indices are included."),
    maxSnapshotRateMbPerNode: z.number().default(0).optional()
        .describe("Maximum snapshot throughput in MB/s per data node. 0 means no rate limiting. Use to reduce I/O impact on the source cluster during snapshot creation."),
    compressionEnabled: z.boolean().default(false).optional()
        .describe("When true, enables metadata compression for the snapshot. Reduces snapshot size at the cost of slightly increased CPU usage during creation."),
    includeGlobalState: z.boolean().default(true).optional()
        .describe("When true, includes cluster global state (persistent settings, templates, etc.) in the snapshot."),
}).describe("Process-level options for the CreateSnapshot command, controlling which indices are snapshotted and rate limiting.");

export const USER_CREATE_SNAPSHOT_WORKFLOW_OPTION_KEYS = getZodKeys(USER_CREATE_SNAPSHOT_WORKFLOW_OPTIONS);
export const USER_CREATE_SNAPSHOT_PROCESS_OPTION_KEYS = getZodKeys(USER_CREATE_SNAPSHOT_PROCESS_OPTIONS);

export const USER_CREATE_SNAPSHOT_OPTIONS = z.object({
    ...USER_CREATE_SNAPSHOT_WORKFLOW_OPTIONS.shape,
    ...USER_CREATE_SNAPSHOT_PROCESS_OPTIONS.shape,
});

export const USER_METADATA_WORKFLOW_OPTIONS = z.object({
    jvmArgs: z.string().default("").optional()
        .describe("Additional JVM arguments passed to the metadata migration process via JDK_JAVA_OPTIONS."),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
        .describe("Name of a Kubernetes ConfigMap containing a custom Log4j configuration for the metadata migration process."),
    skipEvaluateApproval: z.boolean().default(false).optional()
        .describe("When true, skips the manual approval gate before the metadata evaluation step. The evaluation step analyzes what metadata changes would be applied without making changes."),
    skipMigrateApproval: z.boolean().default(false).optional()
        .describe("When true, skips the manual approval gate before the metadata migration step. The migration step applies the evaluated metadata changes to the target cluster.")
}).describe("Workflow-level options for metadata migration, controlling JVM settings and approval gates.");

export const USER_METADATA_PROCESS_OPTIONS = z.object({
    componentTemplateAllowlist: z.array(z.string()).default([]).optional()
        .describe("List of component template name patterns to include in the metadata migration. An empty list means all component templates are included."),
    indexAllowlist: z.array(z.string()).default([]).optional()
        .describe("List of index name patterns to include in the metadata migration. An empty list means all indices are included."),
    indexTemplateAllowlist: z.array(z.string()).default([]).optional()
        .describe("List of index template name patterns to include in the metadata migration. An empty list means all index templates are included."),

    allowLooseVersionMatching: z.boolean().default(true).optional()
        .describe("When true, allows metadata migration between clusters with non-exact version compatibility (e.g. ES 7.x to OS 2.x). When false, requires strict version matching."),
    clusterAwarenessAttributes: z.number().default(1).optional()
        .describe("Number of shard allocation awareness attributes to preserve during metadata migration. Controls how index settings related to cluster topology are handled."),
    multiTypeBehavior: z.union(["NONE", "UNION", "SPLIT"].map(s=>z.literal(s))).default("NONE").optional()
        .describe("Strategy for handling Elasticsearch multi-type indices (ES 5.x and earlier). " +
            "'NONE': fail if multi-type indices are encountered. " +
            "'UNION': merge all types into a single mapping. " +
            "'SPLIT': create separate indices for each type."),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317").optional()
        .describe("URL for the OpenTelemetry Collector for metadata migration metrics."),
    output: z.union(["HUMAN_READABLE", "JSON"].map(s=>z.literal(s))).default("HUMAN_READABLE").optional()
        .describe("Output format for the metadata migration evaluation report. 'HUMAN_READABLE' for formatted text, 'JSON' for machine-parseable output."),
    transformerConfigBase64: z.string().default("").optional()
        .describe("Base64-encoded JSON configuration for metadata transformers. Defines custom transformations applied to index mappings and settings during migration."),
    transformerConfig: z.string().optional()
        .describe("Inline JSON configuration for metadata transformers. Keys are transformer names and values are their configuration. Alternative to transformerConfigBase64 for simple configurations."),
}).describe("Process-level options for the metadata migration command, controlling which metadata is migrated and how it is transformed.");

export const USER_METADATA_WORKFLOW_OPTION_KEYS = getZodKeys(USER_METADATA_WORKFLOW_OPTIONS);
export const USER_METADATA_PROCESS_OPTION_KEYS = getZodKeys(USER_METADATA_PROCESS_OPTIONS);

export const USER_METADATA_OPTIONS = z.object({
    ...USER_METADATA_WORKFLOW_OPTIONS.shape,
    ...USER_METADATA_PROCESS_OPTIONS.shape,
});

export const USER_RFS_WORKFLOW_OPTIONS = z.object({
    podReplicas: z.number().default(1).optional()
        .describe("Number of RFS (Reindex From Snapshot) pod replicas in the Kubernetes Deployment. Each replica processes shards independently using distributed work coordination."),
    jvmArgs: z.string().default("").optional()
        .describe("Additional JVM arguments passed to the RFS process via JDK_JAVA_OPTIONS (e.g. '-Xmx6g'). Tune based on shard sizes and available memory."),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
        .describe("Name of a Kubernetes ConfigMap containing a custom Log4j configuration for the RFS process."),
    skipApproval: z.boolean().default(false).optional()
        .describe("When true, skips the manual approval gate before starting the document backfill. Useful for automated pipelines where human approval is not needed."),
    useTargetClusterForWorkCoordination: z.boolean().default(false)
        .describe("When true, uses the target OpenSearch cluster for RFS work coordination (lease management and shard assignment). " +
            "When false (default), a dedicated single-node OpenSearch coordinator cluster is automatically deployed, used for the lifetime of the migration, then torn down on completion. " +
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
        .describe("Kubernetes resource limits and requests for the RFS container. Partial overrides are deep-merged with the built-in defaults. Ephemeral storage is auto-calculated from maxShardSizeBytes if not specified."),
}).describe("Kubernetes deployment-level options for the Reindex From Snapshot (RFS) document backfill.");

export const USER_RFS_PROCESS_OPTIONS = z.object({
    indexAllowlist: z.array(z.string()).default([]).optional()
        .describe("List of index name patterns to include in the document backfill. An empty list means all indices from the snapshot are migrated."),
    allowLooseVersionMatching: z.boolean().default(true).optional()
        .describe("When true, allows document migration between clusters with non-exact version compatibility."),
    docTransformerConfigBase64: z.string().default("").optional()
        .describe("Base64-encoded JSON configuration for document transformers. Defines custom transformations applied to each document during the backfill (e.g. field renaming, type conversion)."),
    docTransformerConfig: z.string().optional()
        .describe("Inline JSON configuration for document transformers. Keys are transformer names and values are their configuration. Alternative to docTransformerConfigBase64 for simple configurations."),
    documentsPerBulkRequest: z.number().default(0x7fffffff).optional()
        .describe("Maximum number of documents per bulk indexing request to the target cluster. Lower values reduce per-request latency but increase overhead."),
    documentsSizePerBulkRequest: z.number().default(10*1024*1024).optional()
        .describe("Maximum aggregate document size in bytes per bulk indexing request. Does not apply to single-document requests."),
    initialLeaseDuration: z.string().default("PT1H").optional()
        .describe("ISO 8601 duration for the initial work item lease in the coordination store (e.g. 'PT1H' = 1 hour, 'PT10M' = 10 minutes). If a worker fails to complete a shard within this duration, the lease expires and another worker can pick it up."),
    maxConnections: z.number().default(10).optional()
        .describe("Maximum number of concurrent HTTP connections from each RFS worker to the target cluster for bulk indexing."),
    maxShardSizeBytes: z.number().default(80*1024*1024*1024).optional()
        .describe("Expected maximum shard size in bytes. Used to auto-calculate ephemeral storage requirements as ceil(2.5 * maxShardSizeBytes). Set this to match your largest shard to ensure sufficient disk space for Lucene segment processing."),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317").optional()
        .describe("URL for the OpenTelemetry Collector for RFS backfill metrics and progress tracking."),
    serverGeneratedIds: z.union(["AUTO", "ALWAYS", "NEVER"].map(s=>z.literal(s))).default("AUTO").optional()
        .describe("Controls document ID generation on the target. " +
            "'AUTO': auto-detect serverless TIMESERIES/VECTOR collections and enable server-generated IDs. " +
            "'ALWAYS': always use server-generated IDs (discards source IDs). " +
            "'NEVER': always preserve source document IDs (may fail on serverless TIMESERIES/VECTOR collections)."),
    allowedDocExceptionTypes: z.array(z.string()).default([]).optional()
        .describe("List of document-level exception types to treat as successful operations during bulk migration. " +
            "Enables idempotent migrations by allowing specific errors (e.g. 'version_conflict_engine_exception') to be treated as success rather than failure."),
    coordinatorRetryMaxRetries: z.number().default(7).optional()
        .describe("Maximum number of retries when marking work items as completed on the coordinator."),
    coordinatorRetryInitialDelayMs: z.number().default(1000).optional()
        .describe("Initial delay in milliseconds for coordinator completion retries. Doubles with each attempt up to coordinatorRetryMaxDelayMs."),
    coordinatorRetryMaxDelayMs: z.number().default(64000).optional()
        .describe("Maximum delay in milliseconds for any single coordinator completion retry."),
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

export const KAFKA_CLUSTER_CREATION_CONFIG = z.object({
    replicas:      z.number().int().min(1).default(1).optional()
        .describe("Number of Kafka broker replicas in the Strimzi Kafka cluster. Increase for higher availability and throughput."),
    storage:       z.discriminatedUnion("type", [
                       z.object({ type: z.literal("ephemeral") })
                           .describe("Use ephemeral (emptyDir) storage. Data is lost when pods restart. Suitable for development and testing."),
                       z.object({ type: z.literal("persistent-claim"), size: z.string()
                           .describe("Size of the PersistentVolumeClaim (e.g. '10Gi', '100Gi').") })
                           .describe("Use persistent storage backed by a PersistentVolumeClaim. Required for production workloads.")
                   ]).default({ type: "ephemeral" }).optional()
                   .describe("Storage configuration for Kafka broker data."),
    partitions:    z.number().int().min(1).default(1).optional()
        .describe("Number of partitions for auto-created Kafka topics. More partitions enable higher parallelism for consumers."),
    topicReplicas: z.number().int().min(1).default(1).optional()
        .describe("Replication factor for auto-created Kafka topics. Must not exceed the number of broker replicas. Higher values improve durability."),
}).describe("Configuration for auto-creating a Strimzi Kafka cluster. Used when no existing Kafka cluster is provided.");

export const KAFKA_CLUSTER_CONFIG = z.union([
    z.object({autoCreate: KAFKA_CLUSTER_CREATION_CONFIG})
        .describe("Auto-create a new Strimzi Kafka cluster with the specified configuration. The cluster bootstrap service is available at '<clusterName>-kafka-bootstrap:9092'."),
    z.object({existing: KAFKA_CLIENT_CONFIG })
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
}).describe("AWS SigV4 request signing authentication. Uses the pod's IAM role credentials.");

export const HTTP_AUTH_MTLS = z.object({
    mtls: z.object({
        caCert: z.string()
            .describe("PEM-encoded CA certificate or path to CA certificate file for verifying the server's TLS certificate."),
        clientSecretName: z.string()
            .describe("Name of a Kubernetes TLS Secret containing the client certificate and private key for mutual TLS authentication.")
    })
}).describe("Mutual TLS (mTLS) authentication using client certificates.");

export const CLUSTER_VERSION_STRING = z.string().regex(/^(?:ES [125678]|OS [123])(?:\.[0-9]+)+$/)
    .describe("Cluster version string in '<ENGINE> <VERSION>' format. Supported engines: 'ES' (Elasticsearch) versions 1, 2, 5, 6, 7, 8; 'OS' (OpenSearch) versions 1, 2, 3. Examples: 'ES 7.10.2', 'OS 2.11.0'.");

export const CLUSTER_CONFIG = z.object({
    endpoint:  z.string().regex(new RegExp(OPTIONAL_HTTP_ENDPOINT_PATTERN)).default("").optional()
        .describe("HTTP(S) endpoint URL for the cluster (e.g. 'https://my-cluster:9200/'). Leave empty if the cluster is not directly accessible or will be accessed through a proxy."),
    allowInsecure: z.boolean().default(false).optional()
        .describe("When true, disables TLS certificate verification when connecting to the cluster. Use only for development or self-signed certificates."),
    authConfig: z.union([HTTP_AUTH_BASIC, HTTP_AUTH_SIGV4, HTTP_AUTH_MTLS]).optional()
        .describe("Authentication configuration for connecting to the cluster. Supports HTTP Basic (Kubernetes Secret), AWS SigV4, or mutual TLS."),
}).describe("Base connection configuration for an Elasticsearch or OpenSearch cluster.");

export const TARGET_CLUSTER_CONFIG = CLUSTER_CONFIG.extend({
    enabled: z.boolean().default(true).optional()
        .describe("When false, this target cluster is excluded from the migration. Useful for temporarily disabling a target without removing its configuration."),
    endpoint:  z.string().regex(new RegExp(HTTP_ENDPOINT_PATTERN))
        .describe("HTTP(S) endpoint URL for the target cluster (e.g. 'https://target-cluster:9200/'). Required for target clusters."),
}).describe("Connection configuration for a target OpenSearch cluster. Extends the base cluster config with a required endpoint.");

export const SOURCE_CLUSTER_REPOS_RECORD =
    z.record(z.string(), S3_REPO_CONFIG)
    .describe("Map of snapshot repository names to their S3 configurations. Keys are the repository names as registered in the source cluster.");

export const CAPTURE_CONFIG = z.object({
    kafka: z.string().regex(K8S_NAMING_PATTERN).default("default").optional()
        .describe("Name of the Kafka cluster to use for captured traffic. Must match a key in kafkaClusterConfiguration. Defaults to 'default', which auto-creates a cluster if none is configured."),
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
    skipApprovals: z.boolean().default(false).optional()
        .describe("When true, skips all manual approval gates for this replayer."),
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
    requiredForCompleteMigration: z.union([
        z.object({toTargets: z.array(z.string())
            .describe("List of target cluster names that require this snapshot for a complete migration.")
        }),
        z.boolean().default(true).optional()
    ]).describe("Whether this snapshot is required for migration completeness. Can be true/false or specify specific target clusters.")
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
        .describe("Snapshots to use or create for this source cluster.")
}).describe("Snapshot repository and snapshot configuration for a source cluster.");

export const SOURCE_CLUSTER_CONFIG = CLUSTER_CONFIG.extend({
    version: CLUSTER_VERSION_STRING
        .describe("Version of the source cluster in '<ENGINE> <MAJOR>.<MINOR>.<PATCH>' format (e.g. 'ES 7.10.2', 'OS 1.3.0'). Required for compatibility checks and migration strategy selection."),
    enabled: z.boolean().default(true).optional()
        .describe("When false, this source cluster is excluded from the migration. Useful for temporarily disabling a source without removing its configuration."),
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
    .describe("Ordered list of migration passes to execute for a single snapshot. Each pass can include metadata migration, document backfill, or both.");

export const PER_SNAPSHOT_MIGRATION_CONFIG_RECORD =
    z.record(z.string().regex(/^[a-zA-Z][a-zA-Z0-9]*/),
        SNAPSHOT_MIGRATION_CONFIG_ARRAY.min(1))
    .describe("Map of snapshot names to their migration configurations. Keys must match snapshot names defined in the source cluster's snapshotInfo.snapshots.");

export const NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG = z.object({
    skipApprovals : z.boolean().default(false).optional()
        .describe("When true, skips all manual approval gates for migrations in this configuration block."),
    fromSource: z.string()
        .describe("Name of the source cluster to migrate from. Must match a key in sourceClusters."),
    toTarget: z.string()
        .describe("Name of the target cluster to migrate to. Must match a key in targetClusters."),
    perSnapshotConfig: PER_SNAPSHOT_MIGRATION_CONFIG_RECORD.optional()
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
            .describe("Kafka cluster configurations. If empty and traffic capture is configured, a default ephemeral Kafka cluster is auto-created for each referenced cluster name."),
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
            }
        }
    })
);
