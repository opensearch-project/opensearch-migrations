import {
    ARGO_METADATA_OPTIONS,
    ARGO_REPLAYER_OPTIONS,
    ARGO_RFS_OPTIONS,
    DENORMALIZED_REPO_CONFIG,
    DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES,
    OVERALL_MIGRATION_CONFIG,
    REPO_CONFIG,
    SOURCE_CLUSTER_REPOS_RECORD, USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG,
    ARGO_MIGRATION_CONFIG_PRE_ENRICH, KAFKA_CLUSTER_CONFIG, KAFKA_CLUSTER_CREATION_CONFIG, CAPTURE_CONFIG,
    PER_SOURCE_CREATE_SNAPSHOTS_CONFIG,
    SOURCE_CLUSTER_CONFIG,
    FieldMeta, ChecksumDependency,
    USER_CREATE_SNAPSHOT_OPTIONS,
    USER_PROXY_PROCESS_OPTIONS, USER_PROXY_WORKFLOW_OPTIONS,
    USER_RFS_PROCESS_OPTIONS,
    ARGO_PROXY_OPTIONS,
    TRANSFORM_PIPELINE,
    TRANSFORM_CONTEXT_VALUE,
} from '@opensearch-migrations/schemas';
import {StreamSchemaTransformer} from './streamSchemaTransformer';
import { z } from 'zod';
import {promises as dns} from "dns";
import {createHash} from "crypto";
import { generateSemaphoreKey, resolveSerializeSnapshotCreation } from './semaphoreUtils';
import { crdName } from './crdNaming';
import {validateInputAgainstUnifiedSchema} from "./unifiedSchemaValidator";
import {FileSourceRegistry} from "./fileSourceUtils";

type InputConfig = z.infer<typeof OVERALL_MIGRATION_CONFIG>;
type OutputConfig = z.infer<typeof ARGO_MIGRATION_CONFIG_PRE_ENRICH>;
type SolrBackupNormalizedConfig = {
    externalBackupName?: string;
    collectionAllowlist: string[];
    otelTraceCollectorEndpoint?: string;
    otelMetricsCollectorEndpoint?: string;
    jvmArgs?: string;
    loggingConfigurationOverrideConfigMap?: string;
};
type SolrExternalBackupNormalizedConfig = SolrBackupNormalizedConfig & {
    externalBackupName: string;
};
type WorkflowGeneratedSnapshotConfig = {
    createSnapshotConfig: z.infer<typeof USER_CREATE_SNAPSHOT_OPTIONS>;
};
type WorkflowExternalSnapshotConfig = {
    externallyManagedSnapshotName: string;
};
type WorkflowSnapshotNameConfig = WorkflowGeneratedSnapshotConfig | WorkflowExternalSnapshotConfig;
type NormalizedSnapshotDefinition = {
    repoName: string;
    config: WorkflowSnapshotNameConfig;
    solrBackupConfig?: SolrBackupNormalizedConfig;
};
type NormalizedSnapshotInfo = {
    repos?: z.infer<typeof SOURCE_CLUSTER_REPOS_RECORD>;
    snapshots: Record<string, NormalizedSnapshotDefinition>;
    serializeSnapshotCreation?: boolean;
};
type NormalizedSourceCluster = Omit<z.infer<typeof SOURCE_CLUSTER_CONFIG>, "snapshotInfo"> & {
    snapshotInfo?: NormalizedSnapshotInfo;
};
type ClusterAuthConfig = z.infer<typeof SOURCE_CLUSTER_CONFIG>["authConfig"];
export type NormalizedUserConfig = Omit<InputConfig, "kafkaClusterConfiguration" | "sourceClusters"> & {
    kafkaClusterConfiguration: Record<string, z.infer<typeof KAFKA_CLUSTER_CONFIG>>;
    sourceClusters: Record<string, NormalizedSourceCluster>;
};

/** Kafka version deployed by auto-created clusters. Not user-configurable. */
const KAFKA_VERSION = "4.0.0";

async function rewriteLocalStackEndpointToIp(s3Endpoint: string): Promise<string> {
    // Determine protocol based on localstack vs localstacks
    const isSecure = /^localstacks:\/\//i.test(s3Endpoint);
    const protocol = isSecure ? 'https://' : 'http://';

    // Extract hostname and port - first normalize to http(s):// for parsing
    const normalizedEndpoint = s3Endpoint.replace(/^localstacks?:\/\//i, protocol);
    const url = new URL(normalizedEndpoint);
    const port = url.port ? `:${url.port}` : '';
    const result = await dns.lookup(url.hostname);
    let s3Ip = result.address;
    if (result.family === 6) {
        s3Ip = `[${s3Ip}]`;
    }
    return `${protocol}${s3Ip}${port}`;
}

async function rewriteRepoEndpointIfLocalStack(
    snapshotRepo: z.infer<typeof REPO_CONFIG>,
    repoName: string
): Promise<z.infer<typeof DENORMALIZED_REPO_CONFIG>>
{
    // GCS repos have no LocalStack-equivalent; the endpoint check is a no-op
    // for gs:// URIs and the resulting useLocalStack stays false.
    const useLocalStack = /^localstacks?:\/\//i.test(snapshotRepo.endpoint ?? "");
    if (snapshotRepo.endpoint && useLocalStack) {
        snapshotRepo.endpoint = await rewriteLocalStackEndpointToIp(snapshotRepo.endpoint);
    }
    return { ...snapshotRepo, useLocalStack, repoName };
}

async function rewriteRepoRecordEndpointIfLocalStack(
    snapshotRepos: z.infer<typeof SOURCE_CLUSTER_REPOS_RECORD>
): Promise<z.infer<typeof SOURCE_CLUSTER_REPOS_RECORD>>
{
    const entries = Object.entries(snapshotRepos);
    const rewrittenEntries = await Promise.all(
        entries.map(async ([repoName, repoConfig]) => {
            const rewritten = await rewriteRepoEndpointIfLocalStack(repoConfig, repoName);
            return [repoName, rewritten] as const;
        })
    );
    return Object.fromEntries(rewrittenEntries);
}

function autoLabelMigrations(
    migrations: z.infer<typeof USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG>[]
) {
    return migrations.map((m, idx) => {
        const {label, ...rest} = m;
        return { ...rest, label: label || `migration-${idx}` };
    });
}

/** Auto-label migration items within perSnapshotConfig records. */
export function setNamesInUserConfig(userConfig: InputConfig): InputConfig {
    const { snapshotMigrationConfigs, ...rest } = userConfig;
    return {
        ...rest,
        snapshotMigrationConfigs: snapshotMigrationConfigs.map(mc => {
            const { perSnapshotConfig, ...mcRest } = mc;
            if (!perSnapshotConfig) return mc;
            return {
                ...mcRest,
                perSnapshotConfig: Object.fromEntries(
                    Object.entries(perSnapshotConfig).map(([snapshotName, migrations]) =>
                        [snapshotName, autoLabelMigrations(migrations)]
                    )
                )
            };
        })
    };
}

function makeProxyServiceEndpoint(proxyName: string, listenPort: number, hasTls: boolean): string {
    return `${hasTls ? "https" : "http"}://${proxyName}:${listenPort}`;
}

function validateNoExtraKeys(data: any, schema: z.ZodTypeAny, path: string[] = []): void {
    const schemaType = schema.constructor.name;
    
    if (schemaType === 'ZodObject') {
        if (typeof data !== 'object' || data === null) return;
        
        const allowedKeys = Object.keys((schema as z.ZodObject<any>).shape);
        const actualKeys = Object.keys(data);
        const extraKeys = actualKeys.filter(key => !allowedKeys.includes(key));
        
        if (extraKeys.length > 0) {
            const pathStr = path.length > 0 ? path.join('.') : 'root';
            throw new Error(`Unrecognized keys at ${pathStr}: ${extraKeys.join(', ')}`);
        }
        
        // Recursively validate nested objects
        for (const key of allowedKeys) {
            if (data[key] !== undefined) {
                validateNoExtraKeys(data[key], (schema as z.ZodObject<any>).shape[key], [...path, key]);
            }
        }
    } else if (schemaType === 'ZodArray' && Array.isArray(data)) {
        data.forEach((item, index) => {
            validateNoExtraKeys(item, (schema as z.ZodArray<any>).element, [...path, index.toString()]);
        });
    } else if (schemaType === 'ZodUnion' && data !== null && data !== undefined) {
        // For unions, we need to manually check which option matches AND validate extra keys
        let foundValidMatch = false;
        let extraKeyError: Error | null = null;
        let parseError: Error | null = null;
        
        for (const option of (schema as z.ZodUnion<any>).options) {
            try {
                // First check if this option would parse the data
                option.parse(data);
                // If it parses, this could be the matching option
                // Now validate it recursively for extra keys
                validateNoExtraKeys(data, option, path);
                foundValidMatch = true;
                break; // Found a valid match, no need to check other options
            } catch (e) {
                const error = e as Error;
                if (error.message.includes('Unrecognized keys')) {
                    extraKeyError = error;
                } else {
                    parseError = error;
                }
                // Continue to next option
            }
        }
        
        if (!foundValidMatch) {
            // Prioritize extra key errors over parse errors
            throw extraKeyError || parseError || new Error('No valid union option found');
        }
    } else if (schemaType === 'ZodOptional' || schemaType === 'ZodDefault') {
        if (data !== undefined) {
            validateNoExtraKeys(data, (schema as z.ZodOptional<any> | z.ZodDefault<any>).unwrap(), path);
        }
    } else if (schemaType === 'ZodRecord' && typeof data === 'object' && data !== null) {
        Object.values(data).forEach((value, index) => {
            validateNoExtraKeys(value, (schema as z.ZodRecord<any, any>).valueType, [...path, `[${index}]`]);
        });
    }
}

const DEFAULT_AUTO_CREATE_CONFIG: z.infer<typeof KAFKA_CLUSTER_CONFIG> = { autoCreate: {} };
const DEFAULT_WORKFLOW_MANAGED_KAFKA_AUTH = {type: "scram-sha-512" as const};

function resolveWorkflowManagedKafkaAuth(
    cluster: z.infer<typeof KAFKA_CLUSTER_CONFIG> & {autoCreate: z.infer<typeof KAFKA_CLUSTER_CREATION_CONFIG>}
) {
    return cluster.autoCreate.auth ?? DEFAULT_WORKFLOW_MANAGED_KAFKA_AUTH;
}

function normalizeKafkaClusterConfig(
    cluster: z.infer<typeof KAFKA_CLUSTER_CONFIG>
): z.infer<typeof KAFKA_CLUSTER_CONFIG> {
    // Keep the cluster in the user-config schema family while resolving
    // workflow-managed defaults into an explicit canonical form.
    if ("existing" in cluster) {
        return cluster;
    }

    return {
        autoCreate: {
            ...cluster.autoCreate,
            auth: resolveWorkflowManagedKafkaAuth(
                cluster as z.infer<typeof KAFKA_CLUSTER_CONFIG> & {autoCreate: z.infer<typeof KAFKA_CLUSTER_CREATION_CONFIG>}
            ),
        }
    };
}

function defaultProxyTlsConfig(proxyName: string) {
    const awsRegion = process.env.PROXY_DEFAULT_AWS_REGION;
    const dnsNames = [
        proxyName,
        `${proxyName}.ma`,
        `${proxyName}.ma.svc.cluster.local`,
    ];
    if (awsRegion) {
        dnsNames.push(`*.elb.${awsRegion}.amazonaws.com`);
    }
    return {
        mode: "certManager" as const,
        issuerRef: {
            name: "migrations-ca",
            kind: "ClusterIssuer" as const,
        },
        dnsNames,
        duration: "2160h",
        renewBefore: "360h",
    };
}

type TransformPipeline = z.infer<typeof TRANSFORM_PIPELINE>;
type TransformContextValue = z.infer<typeof TRANSFORM_CONTEXT_VALUE>;

const PROVIDER_BY_LANGUAGE = {
    javascript: "JsonJSTransformerProvider",
    python: "JsonPythonTransformerProvider",
} as const;

const CAPTURE_PROXY_SSL_TRUST_CERT_PEM_ENV_VAR = "CAPTURE_PROXY_SSL_TRUST_CERT_PEM";

function lowerTransformPipeline(
    pipeline: TransformPipeline | undefined,
    fileSourceRegistry: FileSourceRegistry
) {
    if (pipeline === undefined || pipeline.length === 0) {
        return undefined;
    }

    return JSON.stringify(pipeline.map(transform => {
        const lowered = lowerTransformSpec(transform, fileSourceRegistry);
        return {
            [lowered.providerName]: lowered.config
        };
    }));
}

function lowerTransformSpec(
    transform: TransformPipeline[number],
    fileSourceRegistry: FileSourceRegistry
): {providerName: string; config: unknown} {
    if (transform.transformName !== undefined) {
        return {
            providerName: transform.transformName,
            config: lowerNamedTransformContext(transform.context, fileSourceRegistry)
        };
    }

    if (transform.entryPoint === undefined) {
        throw new Error("Transform spec is missing entryPoint or transformName after schema validation.");
    }

    const entryPoint = transform.entryPoint;
    const scriptConfig: Record<string, unknown> =
        "javascript" in entryPoint ? {
            initializationScript: entryPoint.javascript
        } :
        "javascriptFile" in entryPoint ? {
            initializationScriptFile: fileSourceRegistry.resolveFileRef(entryPoint.javascriptFile)
        } :
        "python" in entryPoint ? {
            initializationScript: entryPoint.python
        } : {
            initializationScriptFile: fileSourceRegistry.resolveFileRef(entryPoint.pythonFile)
        };

    lowerScriptTransformContext(scriptConfig, transform.context, fileSourceRegistry);

    return {
        providerName: "javascript" in entryPoint || "javascriptFile" in entryPoint
            ? PROVIDER_BY_LANGUAGE.javascript
            : PROVIDER_BY_LANGUAGE.python,
        config: scriptConfig
    };
}

function lowerNamedTransformContext(
    context: TransformPipeline[number]["context"],
    fileSourceRegistry: FileSourceRegistry
) {
    if (context === undefined) {
        return {};
    }
    if (typeof context === "string") {
        return context;
    }

    const config: Record<string, unknown> = {};
    const dirs = context.valueDirectories?.map(directory => ({
        path: fileSourceRegistry.resolveDirectory(directory)
    })) ?? [];
    if (dirs.length > 0) {
        config.providerConfigDirs = dirs;
    }

    const providerConfigFiles = lowerFileBackedContextValues(context.values ?? {}, fileSourceRegistry);
    if (Object.keys(providerConfigFiles.files).length > 0) {
        config.providerConfigFiles = providerConfigFiles.files;
    }
    Object.assign(config, providerConfigFiles.literalValues);
    return config;
}

function lowerScriptTransformContext(
    scriptConfig: Record<string, unknown>,
    context: TransformPipeline[number]["context"],
    fileSourceRegistry: FileSourceRegistry
) {
    if (context === undefined) {
        return;
    }
    if (typeof context === "string") {
        scriptConfig.bindingsObject = JSON.stringify(context);
        return;
    }

    const dirs = context.valueDirectories?.map(directory => ({
        path: fileSourceRegistry.resolveDirectory(directory)
    })) ?? [];
    if (dirs.length > 0) {
        scriptConfig.bindingsObjectDirs = dirs;
    }

    const loweredValues = lowerFileBackedContextValues(context.values ?? {}, fileSourceRegistry);
    if (Object.keys(loweredValues.files).length > 0) {
        scriptConfig.bindingsObjectFiles = loweredValues.files;
    }
    if (Object.keys(loweredValues.literalValues).length > 0) {
        scriptConfig.bindingsObject = loweredValues.literalValues;
    }
}

function lowerFileBackedContextValues(
    values: Record<string, TransformContextValue>,
    fileSourceRegistry: FileSourceRegistry
) {
    const files: Record<string, {path: string}> = {};
    const literalValues: Record<string, unknown> = {};
    for (const [key, contextValue] of Object.entries(values)) {
        if ("value" in contextValue) {
            literalValues[key] = contextValue.value;
        } else {
            files[key] = {
                path: fileSourceRegistry.resolveFileRef(contextValue.fromFile)
            };
        }
    }
    return {files, literalValues};
}

function prepareMetadataConfig(
    config: z.infer<typeof USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG>["metadataMigrationConfig"],
    skipApprovals: boolean
) {
    if (config === undefined) {
        return undefined;
    }

    const {metadataTransforms, ...rest} = config;
    const fileSourceRegistry = new FileSourceRegistry();
    const generatedConfig = lowerTransformPipeline(metadataTransforms, fileSourceRegistry);
    return ARGO_METADATA_OPTIONS.parse({
        ...rest,
        skipEvaluateApproval: rest.skipEvaluateApproval ?? skipApprovals,
        skipMigrateApproval: rest.skipMigrateApproval ?? skipApprovals,
        ...fileSourceRegistry.resolvedFields,
        ...(generatedConfig === undefined ? {} : {transformerConfig: generatedConfig}),
    });
}

function prepareDocumentBackfillConfig(
    config: z.infer<typeof USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG>["documentBackfillConfig"],
    skipApprovals: boolean
) {
    if (config === undefined) {
        return undefined;
    }

    const {documentTransforms, ...rest} = config;
    const fileSourceRegistry = new FileSourceRegistry();
    const generatedConfig = lowerTransformPipeline(documentTransforms, fileSourceRegistry);
    return ARGO_RFS_OPTIONS.parse({
        ...rest,
        skipApproval: rest.skipApproval ?? skipApprovals,
        ...fileSourceRegistry.resolvedFields,
        ...(generatedConfig === undefined ? {} : {docTransformerConfig: generatedConfig}),
    });
}

function prepareReplayerConfig(
    config: NonNullable<NonNullable<z.infer<typeof OVERALL_MIGRATION_CONFIG>["traffic"]>["replayers"][string]["replayerConfig"]> | undefined
) {
    const {requestTransforms, tupleTransforms, ...rest} = config ?? {};
    const fileSourceRegistry = new FileSourceRegistry();
    const generatedRequestConfig = lowerTransformPipeline(requestTransforms, fileSourceRegistry);
    const generatedTupleConfig = lowerTransformPipeline(tupleTransforms, fileSourceRegistry);

    return ARGO_REPLAYER_OPTIONS.parse({
        ...rest,
        ...fileSourceRegistry.resolvedFields,
        ...(generatedRequestConfig === undefined ? {} : {transformerConfig: generatedRequestConfig}),
        ...(generatedTupleConfig === undefined ? {} : {tupleTransformerConfig: generatedTupleConfig}),
    });
}

function prepareProxyConfig(
    config: z.infer<typeof CAPTURE_CONFIG>["proxyConfig"]
) {
    const fileSourceRegistry = new FileSourceRegistry();
    const tls = config.tls;

    if (tls !== undefined && "clientAuth" in tls && tls.clientAuth !== undefined) {
        const {clientAuth} = tls;
        const trustCertConfig = clientAuth.trustedClientCaFile !== undefined
            ? {
                sslTrustCertFile: fileSourceRegistry.resolveFileRef(clientAuth.trustedClientCaFile)
            }
            : {
                sslTrustCertPem: clientAuth.trustedClientCaPem,
                sslTrustCertPemEnvVar: CAPTURE_PROXY_SSL_TRUST_CERT_PEM_ENV_VAR
            };
        return ARGO_PROXY_OPTIONS.parse({
            ...config,
            ...trustCertConfig,
            requireClientAuth: clientAuth.required ?? true,
            ...fileSourceRegistry.resolvedFields,
        });
    }

    return ARGO_PROXY_OPTIONS.parse({
        ...config,
        ...fileSourceRegistry.resolvedFields,
    });
}

function normalizeTrafficConfig(traffic: InputConfig["traffic"]): InputConfig["traffic"] {
    // Drop user-schema sentinel placeholders that are equivalent to omission so
    // AJV validates the canonical user config rather than Zod's empty-string defaults.
    if (!traffic) {
        return traffic;
    }

    const normalizedProxies: NonNullable<InputConfig["traffic"]>["proxies"] = {};
    for (const [key, proxy] of Object.entries(traffic.proxies ?? {})) {
        let normalized = proxy.kafkaTopic === ""
            ? (({kafkaTopic, ...rest}) => rest)(proxy)
            : proxy;

        // Secure-by-default: inject self-signed TLS when no TLS config is specified.
        // Users can opt out with tls.mode: "plaintext".
        if (!normalized.proxyConfig?.tls) {
            console.info(`TLS was auto-configured for '${key}' (secure-by-default). Use tls.mode: "plaintext" to opt out.`);
            normalized = {
                ...normalized,
                proxyConfig: {
                    ...normalized.proxyConfig,
                    tls: defaultProxyTlsConfig(key),
                },
            };
        }

        // Strip plaintext mode so Argo sees no TLS. This preserves existing HTTP behavior.
        if (normalized.proxyConfig?.tls &&
            'mode' in normalized.proxyConfig.tls &&
            normalized.proxyConfig.tls.mode === "plaintext") {
            const {tls: _, ...proxyConfigWithoutTls} = normalized.proxyConfig;
            normalized = {...normalized, proxyConfig: proxyConfigWithoutTls};
        }

        normalizedProxies[key] = normalized;
    }

    const normalizedS3Sources: NonNullable<InputConfig["traffic"]>["s3Sources"] = {};
    for (const [key, s3] of Object.entries(traffic.s3Sources ?? {})) {
        // Same sentinel-placeholder strip as proxies — empty-string kafkaTopic
        // means "default to the source name", and the unified schema rejects
        // empty strings for that field.
        const normalized = s3.kafkaTopic === ""
            ? (({kafkaTopic, ...rest}) => rest)(s3)
            : s3;
        normalizedS3Sources[key] = normalized;
    }

    return {
        ...traffic,
        proxies: normalizedProxies,
        s3Sources: normalizedS3Sources,
    };
}

function normalizeSnapshotInfo(
    snapshotInfo: z.infer<typeof SOURCE_CLUSTER_CONFIG>["snapshotInfo"]
): NormalizedSnapshotInfo | undefined {
    if (!snapshotInfo) {
        return undefined;
    }
    if ("snapshots" in snapshotInfo) {
        return snapshotInfo;
    }

    return {
        repos: snapshotInfo.repos,
        serializeSnapshotCreation: snapshotInfo.serializeSnapshotCreation,
        snapshots: Object.fromEntries(
            Object.entries(snapshotInfo.backups).map(([label, backup]) => {
                if ("createBackupConfig" in backup) {
                    const {
                        repoName,
                        createBackupConfig,
                    } = backup;
                    const {
                        collectionAllowlist,
                        ...createBackupOptions
                    } = createBackupConfig;
                    const normalizedCollectionAllowlist = collectionAllowlist ?? [];
                    return [
                        label,
                        {
                            repoName,
                            config: {
                                createSnapshotConfig: {
                                    ...createBackupOptions,
                                    solrCollections: normalizedCollectionAllowlist,
                                },
                            },
                            solrBackupConfig: {
                                collectionAllowlist: normalizedCollectionAllowlist,
                            },
                        },
                    ];
                }

                const {
                    externalBackupName,
                    repoName,
                    collectionAllowlist,
                    ...solrBackupOptions
                } = backup;
                return [
                    label,
                    {
                        repoName,
                        config: {
                            externallyManagedSnapshotName: externalBackupName,
                        },
                        solrBackupConfig: {
                            ...solrBackupOptions,
                            externalBackupName,
                            collectionAllowlist: collectionAllowlist ?? [],
                        },
                    },
                ];
            })
        ),
    };
}

function normalizeSourceClusters(
    sourceClusters: InputConfig["sourceClusters"]
): NormalizedUserConfig["sourceClusters"] {
    return Object.fromEntries(
        Object.entries(sourceClusters).map(([sourceName, sourceCluster]) => [
            sourceName,
            {
                ...sourceCluster,
                snapshotInfo: normalizeSnapshotInfo(sourceCluster.snapshotInfo),
            },
        ])
    );
}

function normalizeUserConfigForValidation(userConfig: InputConfig): InputConfig {
    const namedConfig = setNamesInUserConfig(userConfig);
    return {
        ...namedConfig,
        traffic: normalizeTrafficConfig(namedConfig.traffic),
        kafkaClusterConfiguration: Object.fromEntries(
            Object.entries(namedConfig.kafkaClusterConfiguration ?? {}).map(([key, cluster]) => [
                key,
                normalizeKafkaClusterConfig(cluster)
            ])
        ),
    } as InputConfig;
}

export function normalizeUserConfig(userConfig: InputConfig): NormalizedUserConfig {
    const validationNormalized = normalizeUserConfigForValidation(userConfig);
    return {
        ...validationNormalized,
        kafkaClusterConfiguration: validationNormalized.kafkaClusterConfiguration ?? {},
        sourceClusters: normalizeSourceClusters(validationNormalized.sourceClusters),
    };
}

/** Resolve kafkaClusterConfiguration, auto-injecting autoCreate entries only when no explicit kafka config was provided. */
function resolveKafkaClusters(userConfig: {
    kafkaClusterConfiguration?: Record<string, z.infer<typeof KAFKA_CLUSTER_CONFIG>>,
    traffic?: { proxies?: Record<string, { kafka?: string }>, s3Sources?: Record<string, { kafka?: string }> }
}) {
    const explicit = userConfig.kafkaClusterConfiguration ?? {};
    if (Object.keys(explicit).length > 0) {
        return explicit;
    }
    const clusters: Record<string, z.infer<typeof KAFKA_CLUSTER_CONFIG>> = {};
    for (const proxy of Object.values(userConfig.traffic?.proxies || {})) {
        const key = proxy.kafka ?? "default";
        if (!(key in clusters)) {
            clusters[key] = DEFAULT_AUTO_CREATE_CONFIG;
        }
    }
    for (const s3 of Object.values(userConfig.traffic?.s3Sources || {})) {
        const key = s3.kafka ?? "default";
        if (!(key in clusters)) {
            clusters[key] = DEFAULT_AUTO_CREATE_CONFIG;
        }
    }
    return clusters;
}

/** Build a NAMED_KAFKA_CLIENT_CONFIG from a kafka cluster reference and topic. */
function buildKafkaClientConfig(
    kafkaClusterKey: string,
    kafkaClusters: Record<string, z.infer<typeof KAFKA_CLUSTER_CONFIG>>,
    topic: string
) {
    const cluster = kafkaClusters[kafkaClusterKey];
    if (!cluster) {
        throw new Error(`Kafka cluster '${kafkaClusterKey}' not found in kafkaClusterConfiguration`);
    }
    if ('existing' in cluster) {
        const auth = cluster.existing.auth ?? {type: "none" as const};
        return {
            enableMSKAuth: cluster.existing.enableMSKAuth,
            kafkaConnection: cluster.existing.kafkaConnection,
            kafkaTopic: topic || cluster.existing.kafkaTopic,
            managedByWorkflow: false,
            listenerName: "",
            authType: auth.type,
            secretName: "secretName" in auth ? auth.secretName : "",
            caSecretName: "caSecretName" in auth ? auth.caSecretName : "",
            kafkaUserName: "kafkaUserName" in auth ? (auth.kafkaUserName ?? "") : "",
            topicSpecOverrides: DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES,
            label: kafkaClusterKey
        };
    }
    const auth = resolveWorkflowManagedKafkaAuth(cluster as z.infer<typeof KAFKA_CLUSTER_CONFIG> & {autoCreate: z.infer<typeof KAFKA_CLUSTER_CREATION_CONFIG>});
    const listenerName = auth.type === "scram-sha-512" ? "tls" : "plain";
    const listenerPort = auth.type === "scram-sha-512" ? 9093 : 9092;
    // autoCreate — Strimzi creates a deterministic bootstrap service for the selected internal listener.
    return {
        enableMSKAuth: false,
        kafkaConnection: `${kafkaClusterKey}-kafka-bootstrap:${listenerPort}`,
        kafkaTopic: topic,
        managedByWorkflow: true,
        listenerName,
        authType: auth.type,
        secretName: auth.type === "scram-sha-512" ? `${kafkaClusterKey}-migration-app` : "",
        caSecretName: auth.type === "scram-sha-512" ? `${kafkaClusterKey}-cluster-ca-cert` : "",
        kafkaUserName: auth.type === "scram-sha-512" ? `${kafkaClusterKey}-migration-app` : "",
        topicSpecOverrides: cluster.autoCreate.topicSpecOverrides,
        label: kafkaClusterKey
    };
}

function isGenerateSnapshot(config: WorkflowSnapshotNameConfig): config is WorkflowGeneratedSnapshotConfig {
    return 'createSnapshotConfig' in config;
}

function isExternalSnapshot(config: WorkflowSnapshotNameConfig): config is WorkflowExternalSnapshotConfig {
    return 'externallyManagedSnapshotName' in config;
}

function isSolrSourceVersion(version: string | undefined): boolean {
    return typeof version === "string" && version.startsWith("SOLR ");
}

function needsSolrExternalPrepare(
    sourceCluster: Pick<NormalizedSourceCluster, "version">,
    snapshotDef: NormalizedSnapshotDefinition
): snapshotDef is NormalizedSnapshotDefinition & { solrBackupConfig: SolrExternalBackupNormalizedConfig } {
    return isSolrSourceVersion(sourceCluster.version)
        && snapshotDef.solrBackupConfig !== undefined
        && snapshotDef.solrBackupConfig.externalBackupName !== undefined
        && isExternalSnapshot(snapshotDef.config);
}

function solrCreateSnapshotConfigForBackup(snapshotDef: NormalizedSnapshotDefinition & { solrBackupConfig: SolrBackupNormalizedConfig }) {
    const {
        externalBackupName: _externalBackupName,
        collectionAllowlist,
        ...runtimeOptions
    } = snapshotDef.solrBackupConfig;
    return {
        ...runtimeOptions,
        solrCollections: collectionAllowlist,
    };
}

function solrCollectionAllowlistForSnapshot(snapshotDef: NormalizedSnapshotDefinition): string[] {
    return snapshotDef.solrBackupConfig?.collectionAllowlist ?? [];
}

function applySolrCollectionAllowlist<T extends {indexAllowlist?: string[]} | undefined>(
    config: T,
    collectionAllowlist: string[]
): T {
    if (config === undefined || collectionAllowlist.length === 0) {
        return config;
    }
    if ((config.indexAllowlist ?? []).length > 0) {
        return config;
    }
    return {
        ...config,
        indexAllowlist: collectionAllowlist,
    } as T;
}

export class MigrationConfigTransformer extends StreamSchemaTransformer<
    typeof OVERALL_MIGRATION_CONFIG,
    typeof ARGO_MIGRATION_CONFIG_PRE_ENRICH
> {
    constructor() {
        super(OVERALL_MIGRATION_CONFIG, ARGO_MIGRATION_CONFIG_PRE_ENRICH);
    }

    validateInput(data: unknown): NormalizedUserConfig {
        // First pass: zod schema validation (including refinements)
        const parsed = super.validateInput(data);
        const validationNormalized = normalizeUserConfigForValidation(parsed);

        // Second pass: unified schema validation against the normalized user-config shape.
        // Internal transformer normalization may add fields that are not user-facing.
        validateInputAgainstUnifiedSchema(validationNormalized);

        // Third pass: check for extra keys
        validateNoExtraKeys(data, OVERALL_MIGRATION_CONFIG);

        return normalizeUserConfig(parsed);
    }

    async transform(input: NormalizedUserConfig): Promise<OutputConfig> {
        const processedInput = await this.preprocessInput(input);
        return await this.transformSync(processedInput);
    }

    private async preprocessInput(input: NormalizedUserConfig): Promise<NormalizedUserConfig> {
        const processedSourceClusters = {...input.sourceClusters};

        for (const [name, cluster] of Object.entries(input.sourceClusters)) {
            if (cluster.snapshotInfo !== undefined && cluster.snapshotInfo.repos !== undefined) {
                processedSourceClusters[name] = {
                    ...cluster,
                    snapshotInfo: {
                        ...cluster.snapshotInfo,
                        repos: await rewriteRepoRecordEndpointIfLocalStack(cluster.snapshotInfo.repos)
                    }
                };
            }
        }

        return { ...input, sourceClusters: processedSourceClusters };
    }

    private async transformSync(userConfig: NormalizedUserConfig): Promise<OutputConfig> {
        const kafkaClusters = this.buildKafkaClusters(userConfig);
        const proxies = this.buildProxies(userConfig, userConfig.skipApprovals ?? false);
        const s3TrafficLoaders = this.buildS3TrafficLoaders(userConfig);
        const snapshots = this.buildSnapshots(userConfig);
        const snapshotMigrations = await this.buildSnapshotMigrations(userConfig);
        const trafficReplays = this.buildTrafficReplays(userConfig);

        // Compute config checksums with dependency chaining
        const cs = MigrationConfigTransformer.configChecksum;
        const csDep = MigrationConfigTransformer.checksumForDependency;
        const PROXY_SCHEMA = z.object({...USER_PROXY_WORKFLOW_OPTIONS.shape, ...USER_PROXY_PROCESS_OPTIONS.shape});
        const RFS_SCHEMA = USER_RFS_PROCESS_OPTIONS;
        const kafkaChecksums = new Map(kafkaClusters.map(k => [
            k.name,
            cs(MigrationConfigTransformer.kafkaClusterContract(k as Record<string, unknown>))
        ]));

        const proxiesWithChecksums = proxies.map(p => {
            const kafkaChecksum = kafkaChecksums.get(p.kafkaConfig.label) ?? '';
            const kafkaIdentity = MigrationConfigTransformer.kafkaClientIdentity(p.kafkaConfig as Record<string, unknown>);
            const topicConfigChecksum = cs(
                kafkaIdentity,
                p.kafkaConfig.kafkaTopic,
                p.kafkaConfig.topicSpecOverrides,
                kafkaChecksum
            );
            const sourceConnectionIdentityChecksum = cs(p.sourceConnectionIdentity);
            return {
                ...p,
                kafkaConfig: { ...p.kafkaConfig, configChecksum: kafkaChecksum },
                configChecksum: cs(p.sourceConnectionIdentity, p.proxyConfig, topicConfigChecksum),
                topicConfigChecksum,
                checksumForSnapshot: csDep(
                    PROXY_SCHEMA,
                    p.proxyConfig as Record<string, unknown>,
                    'snapshot',
                    sourceConnectionIdentityChecksum,
                    topicConfigChecksum
                ),
                checksumForReplayer: csDep(
                    PROXY_SCHEMA,
                    p.proxyConfig as Record<string, unknown>,
                    'replayer',
                    sourceConnectionIdentityChecksum,
                    topicConfigChecksum
                ),
            };
        });
        const proxyChecksums = new Map(proxiesWithChecksums.map(p => [p.name, p.configChecksum]));
        const proxyChecksumForSnapshot = new Map(proxiesWithChecksums.map(p => [p.name, p.checksumForSnapshot]));
        const proxyChecksumForReplayer = new Map(proxiesWithChecksums.map(p => [p.name, p.checksumForReplayer]));

        const s3LoadersWithChecksums = s3TrafficLoaders.map(s => {
            const kafkaCs = kafkaChecksums.get(s.kafkaConfig.label) ?? '';
            const kafkaIdentity = MigrationConfigTransformer.kafkaClientIdentity(s.kafkaConfig as Record<string, unknown>);
            const topicConfigChecksum = cs(
                kafkaIdentity,
                s.kafkaConfig.kafkaTopic,
                s.kafkaConfig.topicSpecOverrides,
                kafkaCs
            );
            // Replayer & topic checksums are derived from the loader's identity:
            // s3Uri + topic + kafka cluster. Editing the URI would force a reset
            // of the CapturedTraffic resource (VAP-protected); the resulting
            // checksum change is what flags the replayer to re-evaluate.
            const checksum = cs(s.s3Uri, topicConfigChecksum);
            return {
                ...s,
                kafkaConfig: { ...s.kafkaConfig, configChecksum: kafkaCs },
                topicConfigChecksum,
                checksumForReplayer: checksum,
                configChecksum: checksum,
            };
        });
        const s3LoaderChecksumForReplayer = new Map(s3LoadersWithChecksums.map(s => [s.name, s.checksumForReplayer]));

        const snapshotsWithChecksums = snapshots.map(s => ({
            ...s,
            createSnapshotConfig: s.createSnapshotConfig.map((item: z.infer<typeof PER_SOURCE_CREATE_SNAPSHOTS_CONFIG>) => {
                const enrichedDeps = (item.dependsOnProxySetups ?? []).map((dep: {name: string}) => ({
                    ...dep,
                    configChecksum: proxyChecksumForSnapshot.get(dep.name) ?? '',
                }));
                const sourceConnectionIdentity =
                    MigrationConfigTransformer.clusterConnectionIdentity(s.sourceConfig as Record<string, unknown>);
                const repoIdentity = MigrationConfigTransformer.repoIdentity(item.repo as Record<string, unknown>);
                return {
                    ...item,
                    dependsOnProxySetups: enrichedDeps,
                    dependsOn: enrichedDeps.map(d => d.name),
                    configChecksum: cs(
                        sourceConnectionIdentity,
                        item.config,
                        item.solrExternalBackupName ?? '',
                        repoIdentity,
                        ...enrichedDeps.map(d => d.configChecksum)
                    ),
                };
            }),
        }));
        const snapshotChecksums = new Map<string, string>();
        for (const s of snapshotsWithChecksums) {
            for (const item of s.createSnapshotConfig) {
                snapshotChecksums.set([s.sourceConfig.label, item.label].join('-'), item.configChecksum!);
            }
        }

        const migrationsWithChecksums = snapshotMigrations.map(m => {
            const snapshotConfigChecksum = snapshotChecksums.get([m.sourceLabel, m.label].join('-')) ?? '';
            const sourceConnectionIdentity = m.sourceConnectionIdentity;
            const targetConnectionIdentity = m.targetConnectionIdentity;
            const snapshotRepoIdentity = MigrationConfigTransformer.repoIdentity(
                (m.snapshotConfig.repoConfig ?? {}) as Record<string, unknown>
            );
            const replayerMaterialPart = m.documentBackfillConfig
                ? csDep(RFS_SCHEMA, m.documentBackfillConfig as Record<string, unknown>, 'replayer')
                : '';
            const workloadIdentityMaterialPart = m.documentBackfillConfig
                ? MigrationConfigTransformer.checksumForChangeRestriction(
                    RFS_SCHEMA,
                    m.documentBackfillConfig as Record<string, unknown>,
                    'impossible'
                )
                : '';
            return {
                ...m,
                snapshotConfigChecksum,
                resourceName: crdName(m.sourceLabel, m.targetConfig.label, m.label, m.migrationLabel),
                configChecksum: cs(
                    sourceConnectionIdentity,
                    m.metadataMigrationConfig ?? {},
                    m.documentBackfillConfig ?? {},
                    targetConnectionIdentity,
                    snapshotConfigChecksum,
                    m.snapshotNameResolution,
                    snapshotRepoIdentity
                ),
                checksumForReplayer: cs(targetConnectionIdentity, replayerMaterialPart),
                workloadIdentityChecksum: cs(
                    sourceConnectionIdentity,
                    targetConnectionIdentity,
                    m.snapshotNameResolution,
                    snapshotRepoIdentity,
                    snapshotConfigChecksum,
                    m.migrationLabel,
                    workloadIdentityMaterialPart
                ),
            };
        });

        const replaysWithChecksums = trafficReplays.map(r => {
            // Resolve the upstream checksum from either the proxy or the s3 loader.
            // (One and only one will exist; super-refine guarantees no name collisions.)
            const fromCapturedTrafficChecksum =
                proxyChecksumForReplayer.get(r.fromCapturedTraffic) ??
                s3LoaderChecksumForReplayer.get(r.fromCapturedTraffic) ??
                '';
            return ({
                ...r,
                dependsOn: [
                    r.fromCapturedTraffic,
                    ...((r.dependsOnSnapshotMigrations ?? []).flatMap(dep =>
                        migrationsWithChecksums
                            .filter(m =>
                                m.sourceLabel === dep.source &&
                                m.targetConfig.label === r.toTarget.label &&
                                m.label === dep.snapshot
                            )
                            .map(m => [m.sourceLabel, m.targetConfig.label, m.label, m.migrationLabel].join('-'))
                    ))
                ],
                kafkaConfig: { ...r.kafkaConfig, configChecksum: kafkaChecksums.get(r.kafkaConfig.label) ?? '' },
                fromCapturedTrafficConfigChecksum: fromCapturedTrafficChecksum,
                configChecksum: cs(r.replayerConfig, r.targetConnectionIdentity, fromCapturedTrafficChecksum),
                dependsOnSnapshotMigrations: (r.dependsOnSnapshotMigrations ?? []).flatMap(dep =>
                    migrationsWithChecksums
                        .filter(m =>
                            m.sourceLabel === dep.source &&
                            m.targetConfig.label === r.toTarget.label &&
                            m.label === dep.snapshot
                        )
                        .map(m => ({
                            ...dep,
                            migrationLabel: m.migrationLabel,
                            configChecksum: m.checksumForReplayer,
                            resourceName: m.resourceName,
                        }))
                ),
            });
        });

        const kafkasWithChecksums = kafkaClusters.map(k => ({
            ...k,
            configChecksum: kafkaChecksums.get(k.name),
        }));

        const output = {
            ...(kafkasWithChecksums.length > 0 ? { kafkaClusters: kafkasWithChecksums } : {}),
            ...(proxiesWithChecksums.length > 0 ? { proxies: proxiesWithChecksums } : {}),
            ...(s3LoadersWithChecksums.length > 0 ? { s3TrafficLoaders: s3LoadersWithChecksums } : {}),
            ...(snapshotsWithChecksums.length > 0 ? { snapshots: snapshotsWithChecksums } : {}),
            ...(migrationsWithChecksums.length > 0 ? { snapshotMigrations: migrationsWithChecksums } : {}),
            ...(replaysWithChecksums.length > 0 ? { trafficReplays: replaysWithChecksums } : {})
        };

        try {
            return ARGO_MIGRATION_CONFIG_PRE_ENRICH.parse(output);
        } catch (error) {
            throw new Error("Error while safely parsing the transformed workflow " +
                "as a configuration for the argo workflow.", { cause: error });
        }
    }

    /** Collect auto-created kafka clusters with their aggregated topics from proxies and s3Sources. */
    private buildKafkaClusters(userConfig: NormalizedUserConfig) {
        const kafkaClusters = resolveKafkaClusters(userConfig);
        // Aggregate topics per kafka cluster from proxies AND s3Sources
        const topicsByCluster = new Map<string, Set<string>>();
        for (const [proxyName, proxy] of Object.entries(userConfig.traffic?.proxies || {})) {
            const clusterKey = proxy.kafka ?? "default";
            if (!topicsByCluster.has(clusterKey)) topicsByCluster.set(clusterKey, new Set());
            topicsByCluster.get(clusterKey)!.add(proxy.kafkaTopic || proxyName);
        }
        for (const [s3Name, s3] of Object.entries(userConfig.traffic?.s3Sources || {})) {
            const clusterKey = s3.kafka ?? "default";
            if (!topicsByCluster.has(clusterKey)) topicsByCluster.set(clusterKey, new Set());
            topicsByCluster.get(clusterKey)!.add(s3.kafkaTopic || s3Name);
        }

        return Object.entries(kafkaClusters)
            .filter(([_, config]) => 'autoCreate' in config)
            .map(([name, config]) => ({
                name,
                version: KAFKA_VERSION,
                config: {
                    ...(config as any).autoCreate,
                    auth: resolveWorkflowManagedKafkaAuth(config as z.infer<typeof KAFKA_CLUSTER_CONFIG> & {autoCreate: z.infer<typeof KAFKA_CLUSTER_CREATION_CONFIG>}),
                },
                topics: [...(topicsByCluster.get(name) ?? [])]
            }));
    }

    /** Denormalize each proxy with source endpoint and kafka client config. */
    private buildProxies(userConfig: NormalizedUserConfig, globalSkipApprovals: boolean) {
        const kafkaClusters = resolveKafkaClusters(userConfig);
        return Object.entries(userConfig.traffic?.proxies || {}).map(([proxyName, proxy]) => {
            const sourceCluster = userConfig.sourceClusters[proxy.source];
            if (!sourceCluster) {
                throw new Error(`Proxy '${proxyName}' references unknown source cluster '${proxy.source}'`);
            }
            const topic = proxy.kafkaTopic || proxyName;
            const sourceConnectionIdentity = MigrationConfigTransformer.clusterConnectionIdentity({
                ...sourceCluster,
                label: proxy.source,
            });
            return {
                name: proxyName,
                kafkaConfig: buildKafkaClientConfig(proxy.kafka ?? "default", kafkaClusters, topic),
                sourceConfig: { ...sourceCluster, label: proxy.source },
                sourceConnectionIdentity,
                proxyConfig: prepareProxyConfig(proxy.proxyConfig),
                skipApproval: proxy.skipApproval ?? globalSkipApprovals,
            };
        });
    }

    private getSourceAttachedProxy(userConfig: NormalizedUserConfig, sourceName: string) {
        const matchingProxies = Object.entries(userConfig.traffic?.proxies || {})
            .filter(([_, proxy]) => proxy.source === sourceName);

        if (matchingProxies.length === 0) {
            return undefined;
        }
        if (matchingProxies.length > 1) {
            const proxyNames = matchingProxies.map(([proxyName]) => proxyName).join(", ");
            throw new Error(
                `Source '${sourceName}' maps to multiple proxies (${proxyNames}). ` +
                `Console test routing requires exactly zero or one proxy per source.`
            );
        }

        const [proxyName, proxy] = matchingProxies[0];
        const hasTls = proxy.proxyConfig.tls !== undefined;
        return {
            name: proxyName,
            endpoint: makeProxyServiceEndpoint(proxyName, proxy.proxyConfig.listenPort, hasTls),
            allowInsecure: hasTls,
        };
    }

    /** Build snapshot creation configs grouped by source cluster. */
    private buildSnapshots(userConfig: NormalizedUserConfig) {
        // Build a map of source → proxy names for dependsOnProxySetups
        const proxyNamesBySource = new Map<string, string[]>();
        for (const [proxyName, proxy] of Object.entries(userConfig.traffic?.proxies || {})) {
            const source = proxy.source;
            if (!proxyNamesBySource.has(source)) proxyNamesBySource.set(source, []);
            proxyNamesBySource.get(source)!.push(proxyName);
        }

        const results: any[] = [];
        for (const [sourceName, sourceCluster] of Object.entries(userConfig.sourceClusters)) {
            const snapshotInfo = sourceCluster.snapshotInfo;
            const createConfigs: any[] = [];

            for (const [snapshotName, snapshotDef] of Object.entries(snapshotInfo?.snapshots || {})) {
                const snapshotConfig = snapshotDef.config;
                // Only generated snapshots and Solr external-backup prepare/validation flow
                // through the snapshot-creation path. ES/OS externally-managed snapshots are
                // handled entirely on the migration side and need no DataSnapshot CR.
                if (!isGenerateSnapshot(snapshotConfig) && !needsSolrExternalPrepare(sourceCluster, snapshotDef)) continue;

                const repoConfig = snapshotInfo?.repos?.[snapshotDef.repoName];
                if (!repoConfig) {
                    throw new Error(`Snapshot '${snapshotName}' in source '${sourceName}' references repo '${snapshotDef.repoName}' which is not defined`);
                }

                const proxyDeps = proxyNamesBySource.get(sourceName);

                const semaphore = this.generateSemaphoreConfig(
                    sourceCluster.version,
                    sourceName,
                    snapshotName,
                    snapshotInfo?.serializeSnapshotCreation
                );

                const dependsOnProxySetups = (proxyDeps ?? []).map(name => ({
                    name,
                    configChecksum: ''
                }));

                if (isGenerateSnapshot(snapshotConfig)) {
                    const { snapshotPrefix: _sp, ...createSnapshotOpts } = snapshotConfig.createSnapshotConfig;
                    createConfigs.push({
                        label: snapshotName,
                        snapshotPrefix: snapshotConfig.createSnapshotConfig.snapshotPrefix || snapshotName,
                        sourceConnectionIdentity: MigrationConfigTransformer.clusterConnectionIdentity({
                            ...sourceCluster,
                            label: sourceName,
                        }),
                        config: {
                            ...createSnapshotOpts,
                        },
                        repo: repoConfig,
                        ...semaphore,
                        dependsOnProxySetups,
                    });
                } else if (needsSolrExternalPrepare(sourceCluster, snapshotDef)) {
                    // Solr external prepare. Build a create-config whose `config` carries
                    // mode:"import" plus optional Solr collection scoping, and record the external
                    // backup name so the workflow uses it verbatim. No backup runs.
                    const solrBackupConfig = solrCreateSnapshotConfigForBackup(snapshotDef);
                    createConfigs.push({
                        label: snapshotName,
                        snapshotPrefix: snapshotName,
                        sourceConnectionIdentity: MigrationConfigTransformer.clusterConnectionIdentity({
                            ...sourceCluster,
                            label: sourceName,
                        }),
                        config: {
                            ...solrBackupConfig,
                            mode: "import" as const,
                        },
                        repo: repoConfig,
                        solrExternalBackupName: snapshotDef.solrBackupConfig.externalBackupName,
                        ...semaphore,
                        dependsOnProxySetups,
                    });
                } else {
                    throw new Error(`Unexpected snapshot config for '${snapshotName}' in source '${sourceName}'`);
                }
            }

            if (createConfigs.length > 0) {
                const { snapshotInfo: _si, ...restOfSource } = sourceCluster;
                const proxy = this.getSourceAttachedProxy(userConfig, sourceName);
                results.push({
                    createSnapshotConfig: createConfigs,
                    sourceConfig: {
                        ...restOfSource,
                        label: sourceName,
                        ...(proxy ? {proxy} : {})
                    }
                });
            }
        }
        return results;
    }

    /** Build snapshot migration configs from snapshotMigrationConfigs + perSnapshotConfig. */
    private async buildSnapshotMigrations(userConfig: NormalizedUserConfig) {
        const results: any[] = [];

        for (const mc of userConfig.snapshotMigrationConfigs) {
            const { fromSource, toTarget, perSnapshotConfig } = mc;
            const skipApprovals = mc.skipApprovals ?? userConfig.skipApprovals ?? false;

            const sourceCluster = userConfig.sourceClusters[fromSource];
            const targetCluster = userConfig.targetClusters[toTarget];
            if (!targetCluster) {
                throw new Error(`Migration references unknown target cluster '${toTarget}'`);
            }

            // When perSnapshotConfig is not provided, auto-generate it from the normalized
            // snapshotInfo.snapshots map so the workflow creates/waits for snapshots the same way
            // for both ES and Solr sources.
            const effectivePerSnapshotConfig = perSnapshotConfig ?? (
                sourceCluster.snapshotInfo?.snapshots
                    ? Object.fromEntries(
                        Object.keys(sourceCluster.snapshotInfo.snapshots).map(snapName => [
                            snapName,
                            [USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG.parse({
                                metadataMigrationConfig: {},
                                documentBackfillConfig: {},
                            })]
                        ])
                    )
                    : undefined
            );

            if (!effectivePerSnapshotConfig) continue;

            const { snapshotInfo: _si, ...restOfSource } = sourceCluster;

            for (const [snapshotName, migrations] of Object.entries(effectivePerSnapshotConfig)) {
                const snapshotDef = sourceCluster.snapshotInfo?.snapshots[snapshotName];
                if (!snapshotDef) {
                    throw new Error(`Migration references snapshot '${snapshotName}' not defined in source '${fromSource}'`);
                }

                const globallyUniqueSnapshotName = `${fromSource}-${snapshotName}`;
                const repoConfig = sourceCluster.snapshotInfo?.repos?.[snapshotDef.repoName];

                const snapshotConfig = snapshotDef.config;
                let snapshotNameResolution:
                    | { externalSnapshotName: string }
                    | { dataSnapshotResourceName: string }
                    | { dataSnapshotResourceName: string; externalSnapshotName: string };
                if (needsSolrExternalPrepare(sourceCluster, snapshotDef)) {
                    // Solr external prepare creates a DataSnapshot CR (so the migration waits for
                    // validation/schema capture), but the snapshot name used is the external,
                    // pre-existing one -- not a workflow-generated name.
                    snapshotNameResolution = {
                        dataSnapshotResourceName: globallyUniqueSnapshotName,
                        externalSnapshotName: snapshotDef.solrBackupConfig.externalBackupName,
                    };
                } else if (isExternalSnapshot(snapshotConfig)) {
                    snapshotNameResolution = {
                        externalSnapshotName: snapshotConfig.externallyManagedSnapshotName,
                    };
                } else {
                    snapshotNameResolution = { dataSnapshotResourceName: globallyUniqueSnapshotName };
                }

                for (const migration of autoLabelMigrations(migrations)) {
                    const solrCollectionAllowlist = isSolrSourceVersion(sourceCluster.version)
                        ? solrCollectionAllowlistForSnapshot(snapshotDef)
                        : [];
                    const sourceConnectionIdentity = MigrationConfigTransformer.clusterConnectionIdentity({
                        ...sourceCluster,
                        label: fromSource,
                    });
                    const targetConnectionIdentity = MigrationConfigTransformer.clusterConnectionIdentity({
                        ...targetCluster,
                        label: toTarget,
                    });
                    const metadataMigrationConfig = prepareMetadataConfig(
                        applySolrCollectionAllowlist(migration.metadataMigrationConfig, solrCollectionAllowlist),
                        skipApprovals
                    );
                    const documentBackfillConfig = prepareDocumentBackfillConfig(
                        applySolrCollectionAllowlist(migration.documentBackfillConfig, solrCollectionAllowlist),
                        skipApprovals
                    );
                    results.push({
                        label: snapshotName,
                        migrationLabel: migration.label,
                        snapshotNameResolution,
                        snapshotConfigChecksum: '',
                        metadataMigrationConfig,
                        documentBackfillConfig,
                        sourceConnectionIdentity,
                        targetConnectionIdentity,
                        sourceVersion: sourceCluster.version || "",
                        sourceLabel: fromSource,
                        ...(sourceCluster.endpoint ? {sourceEndpoint: sourceCluster.endpoint} : {}),
                        ...(sourceCluster.allowInsecure !== undefined ? {sourceAllowInsecure: sourceCluster.allowInsecure} : {}),
                        ...(sourceCluster.authConfig ? {sourceAuth: sourceCluster.authConfig} : {}),
                        targetConfig: { ...targetCluster, label: toTarget },
                        snapshotConfig: {
                            label: snapshotName,
                            ...(repoConfig ? {
                                repoConfig: {
                                    ...repoConfig,
                                    repoName: snapshotDef.repoName
                                }
                            } : {})
                        }
                    });
                }
            }
        }
        return results;
    }

    /** Build traffic replay configs by resolving captured-traffic source → kafka chain.
     * The source can be either a live capture proxy or a one-time S3 load — both
     * appear identical to the replayer (kafka cluster + topic). */
    private buildTrafficReplays(userConfig: NormalizedUserConfig) {
        const kafkaClusters = resolveKafkaClusters(userConfig);
        const proxies = userConfig.traffic?.proxies ?? {};
        const s3Sources = userConfig.traffic?.s3Sources ?? {};

        return Object.entries(userConfig.traffic?.replayers || {}).map(([name, replayer]) => {
            const sourceName = replayer.fromCapturedTraffic;
            const proxy = proxies[sourceName];
            const s3Source = s3Sources[sourceName];

            if (!proxy && !s3Source) {
                throw new Error(`Replayer references unknown captured-traffic source '${sourceName}'`);
            }

            const targetCluster = userConfig.targetClusters[replayer.toTarget];
            if (!targetCluster) {
                throw new Error(`Replayer references unknown target cluster '${replayer.toTarget}'`);
            }

            // proxy and s3Source share the same shape for what the replayer cares about:
            // a kafka cluster reference, a topic name, and a sourceLabel.
            const kafkaCluster = (proxy?.kafka ?? s3Source?.kafka) ?? "default";
            const topicOverride = proxy?.kafkaTopic ?? s3Source?.kafkaTopic ?? "";
            const topic = topicOverride || sourceName;
            const sourceLabel = proxy?.source ?? s3Source!.sourceLabel;

            const replayerConfig = prepareReplayerConfig(
                replayer.replayerConfig
            );
            const targetConnectionIdentity = MigrationConfigTransformer.clusterConnectionIdentity({
                ...targetCluster,
                label: replayer.toTarget,
            });

            return {
                name: [sourceName, replayer.toTarget, name].join('-'),
                sourceLabel,
                fromCapturedTraffic: sourceName,
                fromCapturedTrafficSourceKind: proxy ? "proxy" : "s3",
                kafkaClusterName: kafkaCluster,
                kafkaConfig: buildKafkaClientConfig(kafkaCluster, kafkaClusters, topic),
                toTarget: { ...targetCluster, label: replayer.toTarget },
                targetConnectionIdentity,
                replayerConfig,
                ...(replayer.dependsOnSnapshotMigrations ? { dependsOnSnapshotMigrations: replayer.dependsOnSnapshotMigrations } : {}),
            };
        });
    }

    /** Build one denormalized loader entry per traffic.s3Sources item. */
    private buildS3TrafficLoaders(userConfig: NormalizedUserConfig) {
        const kafkaClusters = resolveKafkaClusters(userConfig);
        const s3Sources = userConfig.traffic?.s3Sources ?? {};
        return Object.entries(s3Sources).map(([name, s3]) => {
            const kafkaCluster = s3.kafka ?? "default";
            const topic = s3.kafkaTopic || name;
            return {
                name,
                sourceLabel: s3.sourceLabel,
                s3Uri: s3.s3Uri,
                awsRegion: s3.awsRegion,
                ...(s3.endpoint ? { endpoint: s3.endpoint } : {}),
                kafkaClusterName: kafkaCluster,
                kafkaConfig: buildKafkaClientConfig(kafkaCluster, kafkaClusters, topic),
            };
        });
    }

    private generateSemaphoreConfig(
        sourceVersion: string,
        sourceName: string,
        snapshotName: string,
        serializeSnapshotCreationOverride: boolean | undefined
    ) {
        const serialize = resolveSerializeSnapshotCreation(sourceVersion, serializeSnapshotCreationOverride);
        return {
            semaphoreConfigMapName: 'concurrency-config',
            semaphoreKey: generateSemaphoreKey(serialize, sourceName, snapshotName)
        };
    }

    /**
     * Pick only the fields from `data` whose schema annotation includes `dep` in checksumFor,
     * then hash them (plus any extra upstream checksums).
     */
    static checksumForDependency(
        schema: z.ZodObject<any>,
        data: Record<string, unknown>,
        dep: ChecksumDependency,
        ...upstreamChecksums: (string | undefined)[]
    ): string {
        const picked: Record<string, unknown> = {};
        for (const [key, fieldSchema] of Object.entries(schema.shape)) {
            const meta = (fieldSchema as z.ZodType).meta() as FieldMeta | undefined;
            if (meta?.checksumFor?.includes(dep)) {
                picked[key] = data[key];
            }
        }
        return MigrationConfigTransformer.configChecksum(picked, ...upstreamChecksums);
    }

    /**
     * Pick only fields whose schema annotation matches a change restriction.
     * Used for stable workload identity, where gated fields must not create
     * parallel Kubernetes workloads.
     */
    static checksumForChangeRestriction(
        schema: z.ZodObject<any>,
        data: Record<string, unknown>,
        restriction: NonNullable<FieldMeta["changeRestriction"]>,
        ...upstreamChecksums: (string | undefined)[]
    ): string {
        const picked: Record<string, unknown> = {};
        for (const [key, fieldSchema] of Object.entries(schema.shape)) {
            const meta = (fieldSchema as z.ZodType).meta() as FieldMeta | undefined;
            if (meta?.changeRestriction === restriction) {
                picked[key] = data[key];
            }
        }
        return MigrationConfigTransformer.configChecksum(picked, ...upstreamChecksums);
    }

    static authIdentity(authConfig?: ClusterAuthConfig): Record<string, string> {
        const emptyAuthIdentity = {
            authType: "none",
            authBasicSecretName: "",
            authSigv4Region: "",
            authSigv4Service: "",
            authMtlsClientSecretName: "",
            authMtlsCaCertHash: "",
        };
        if (!authConfig) {
            return emptyAuthIdentity;
        }
        if ("basic" in authConfig) {
            return {
                ...emptyAuthIdentity,
                authType: "basic",
                authBasicSecretName: authConfig.basic.secretName,
            };
        }
        if ("sigv4" in authConfig) {
            return {
                ...emptyAuthIdentity,
                authType: "sigv4",
                authSigv4Region: authConfig.sigv4.region,
                authSigv4Service: authConfig.sigv4.service ?? "es",
            };
        }
        return {
            ...emptyAuthIdentity,
            authType: "mtls",
            authMtlsClientSecretName: authConfig.mtls.clientSecretName,
            authMtlsCaCertHash: MigrationConfigTransformer.identityHash(authConfig.mtls.caCert),
        };
    }

    static clusterConnectionIdentity(clusterConfig: Record<string, unknown>): Record<string, unknown> {
        const authIdentity = MigrationConfigTransformer.authIdentity(clusterConfig.authConfig as ClusterAuthConfig);
        return {
            label: clusterConfig.label,
            version: clusterConfig.version ?? "",
            endpoint: clusterConfig.endpoint ?? "",
            allowInsecure: clusterConfig.allowInsecure ?? false,
            ...authIdentity,
        };
    }

    static kafkaClusterContract(kafkaCluster: Record<string, unknown>): Record<string, unknown> {
        const config = (kafkaCluster.config ?? {}) as Record<string, unknown>;
        return {
            name: kafkaCluster.name ?? "",
            version: kafkaCluster.version ?? "",
            auth: config.auth ?? {},
            clusterSpecOverrides: config.clusterSpecOverrides ?? {},
            nodePoolSpecOverrides: config.nodePoolSpecOverrides ?? {},
        };
    }

    static kafkaClientIdentity(kafkaConfig: Record<string, unknown>): Record<string, unknown> {
        return {
            kafkaClusterName: kafkaConfig.label ?? "",
            kafkaBrokers: kafkaConfig.kafkaConnection ?? "",
            kafkaManagedByWorkflow: kafkaConfig.managedByWorkflow ?? false,
            kafkaAuthType: kafkaConfig.authType ?? "none",
            kafkaEnableMSKAuth: kafkaConfig.enableMSKAuth ?? false,
            kafkaSecretName: kafkaConfig.secretName ?? "",
            kafkaCaSecretName: kafkaConfig.caSecretName ?? "",
            kafkaUserName: kafkaConfig.kafkaUserName ?? "",
        };
    }

    static repoIdentity(repoConfig: Record<string, unknown>): Record<string, unknown> {
        return {
            repoName: repoConfig.repoName ?? "",
            repoPathUri: repoConfig.repoPathUri ?? "",
            awsRegion: repoConfig.awsRegion ?? "",
            endpoint: repoConfig.endpoint ?? "",
            s3RoleArn: repoConfig.s3RoleArn ?? "",
            useLocalStack: repoConfig.useLocalStack ?? false,
        };
    }

    static identityHash(value: unknown): string {
        return "sha256:" + createHash('sha256')
            .update(JSON.stringify(value))
            .digest('hex');
    }

    static configChecksum(...parts: unknown[]): string {
        return createHash('sha256')
            .update(JSON.stringify(parts))
            .digest('hex')
            .slice(0, 16);
    }
}
