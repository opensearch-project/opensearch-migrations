import {
    ARGO_REPLAYER_OPTIONS,
    DENORMALIZED_S3_REPO_CONFIG,
    DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES,
    OVERALL_MIGRATION_CONFIG,
    S3_REPO_CONFIG,
    SOURCE_CLUSTER_REPOS_RECORD, USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG,
    ARGO_MIGRATION_CONFIG_PRE_ENRICH, KAFKA_CLUSTER_CONFIG, KAFKA_CLUSTER_CREATION_CONFIG, CAPTURE_CONFIG,
    GENERATE_SNAPSHOT, EXTERNALLY_MANAGED_SNAPSHOT, PER_SOURCE_CREATE_SNAPSHOTS_CONFIG,
    FieldMeta, ChecksumDependency,
    USER_PROXY_PROCESS_OPTIONS, USER_PROXY_WORKFLOW_OPTIONS,
    USER_RFS_PROCESS_OPTIONS,
} from '@opensearch-migrations/schemas';
import {StreamSchemaTransformer} from './streamSchemaTransformer';
import { z } from 'zod';
import {promises as dns} from "dns";
import {createHash} from "crypto";
import { generateSemaphoreKey, resolveSerializeSnapshotCreation } from './semaphoreUtils';
import {validateInputAgainstUnifiedSchema} from "./unifiedSchemaValidator";

type InputConfig = z.infer<typeof OVERALL_MIGRATION_CONFIG>;
type OutputConfig = z.infer<typeof ARGO_MIGRATION_CONFIG_PRE_ENRICH>;
export type NormalizedUserConfig = Omit<InputConfig, "kafkaClusterConfiguration"> & {
    kafkaClusterConfiguration: Record<string, z.infer<typeof KAFKA_CLUSTER_CONFIG>>;
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
    snapshotRepo: z.infer<typeof S3_REPO_CONFIG>,
    repoName: string
): Promise<z.infer<typeof DENORMALIZED_S3_REPO_CONFIG>>
{
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

    return {
        ...traffic,
        proxies: normalizedProxies,
    };
}

export function normalizeUserConfig(userConfig: InputConfig): NormalizedUserConfig {
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
    };
}

/** Resolve kafkaClusterConfiguration, auto-injecting autoCreate entries only when no explicit kafka config was provided. */
function resolveKafkaClusters(userConfig: { kafkaClusterConfiguration?: Record<string, z.infer<typeof KAFKA_CLUSTER_CONFIG>>, traffic?: { proxies?: Record<string, { kafka?: string }> } }) {
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

function isGenerateSnapshot(config: any): config is z.infer<typeof GENERATE_SNAPSHOT> {
    return 'createSnapshotConfig' in config;
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
        const normalized = normalizeUserConfig(parsed);

        // Second pass: unified schema validation against the normalized user config.
        // This stays in the user-config schema family and avoids mixing raw input
        // with selectively patched normalized subtrees.
        validateInputAgainstUnifiedSchema(normalized);

        // Third pass: check for extra keys
        validateNoExtraKeys(data, OVERALL_MIGRATION_CONFIG);
        
        return normalized;
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
        const proxies = this.buildProxies(userConfig);
        const snapshots = this.buildSnapshots(userConfig);
        const snapshotMigrations = await this.buildSnapshotMigrations(userConfig);
        const trafficReplays = this.buildTrafficReplays(userConfig);

        // Compute config checksums with dependency chaining
        const cs = MigrationConfigTransformer.configChecksum;
        const csDep = MigrationConfigTransformer.checksumForDependency;
        const PROXY_SCHEMA = z.object({...USER_PROXY_WORKFLOW_OPTIONS.shape, ...USER_PROXY_PROCESS_OPTIONS.shape});
        const RFS_SCHEMA = USER_RFS_PROCESS_OPTIONS;
        const kafkaChecksums = new Map(kafkaClusters.map(k => [k.name, cs(k)]));

        const proxiesWithChecksums = proxies.map(p => ({
            ...p,
            kafkaConfig: { ...p.kafkaConfig, configChecksum: kafkaChecksums.get(p.kafkaConfig.label) ?? '' },
            configChecksum: cs(p.proxyConfig, kafkaChecksums.get(p.kafkaConfig.label)),
            topicConfigChecksum: cs(p.kafkaConfig.kafkaTopic, p.kafkaConfig.topicSpecOverrides, kafkaChecksums.get(p.kafkaConfig.label)),
            checksumForSnapshot: csDep(PROXY_SCHEMA, p.proxyConfig as Record<string, unknown>, 'snapshot', kafkaChecksums.get(p.kafkaConfig.label)),
            checksumForReplayer: csDep(PROXY_SCHEMA, p.proxyConfig as Record<string, unknown>, 'replayer', kafkaChecksums.get(p.kafkaConfig.label)),
        }));
        const proxyChecksums = new Map(proxiesWithChecksums.map(p => [p.name, p.configChecksum]));
        const proxyChecksumForSnapshot = new Map(proxiesWithChecksums.map(p => [p.name, p.checksumForSnapshot]));
        const proxyChecksumForReplayer = new Map(proxiesWithChecksums.map(p => [p.name, p.checksumForReplayer]));

        const snapshotsWithChecksums = snapshots.map(s => ({
            ...s,
            createSnapshotConfig: s.createSnapshotConfig.map((item: z.infer<typeof PER_SOURCE_CREATE_SNAPSHOTS_CONFIG>) => {
                const enrichedDeps = (item.dependsOnProxySetups ?? []).map((dep: {name: string}) => ({
                    ...dep,
                    configChecksum: proxyChecksumForSnapshot.get(dep.name) ?? '',
                }));
                return {
                    ...item,
                    dependsOnProxySetups: enrichedDeps,
                    configChecksum: cs(item.config, item.repo, ...enrichedDeps.map(d => d.configChecksum)),
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
            const replayerMaterialPart = m.documentBackfillConfig
                ? csDep(RFS_SCHEMA, m.documentBackfillConfig as Record<string, unknown>, 'replayer')
                : '';
            return {
                ...m,
                snapshotConfigChecksum: snapshotChecksums.get([m.sourceLabel, m.label].join('-')) ?? '',
                configChecksum: cs(
                    m.metadataMigrationConfig ?? {},
                    m.documentBackfillConfig ?? {},
                    m.targetConfig,
                    snapshotChecksums.get([m.sourceLabel, m.label].join('-'))
                ),
                checksumForReplayer: cs(m.targetConfig, replayerMaterialPart),
            };
        });

        const replaysWithChecksums = trafficReplays.map(r => ({
            ...r,
            dependsOn: [
                r.fromProxy,
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
            fromProxyConfigChecksum: proxyChecksumForReplayer.get(r.fromProxy) ?? '',
            configChecksum: cs(r.replayerConfig, r.toTarget, proxyChecksumForReplayer.get(r.fromProxy)),
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
                    }))
            ),
        }));

        const kafkasWithChecksums = kafkaClusters.map(k => ({
            ...k,
            configChecksum: kafkaChecksums.get(k.name),
        }));

        const output = {
            ...(kafkasWithChecksums.length > 0 ? { kafkaClusters: kafkasWithChecksums } : {}),
            ...(proxiesWithChecksums.length > 0 ? { proxies: proxiesWithChecksums } : {}),
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

    /** Collect auto-created kafka clusters with their aggregated topics from proxies. */
    private buildKafkaClusters(userConfig: NormalizedUserConfig) {
        const kafkaClusters = resolveKafkaClusters(userConfig);
        // Aggregate topics per kafka cluster from proxies
        const topicsByCluster = new Map<string, Set<string>>();
        for (const [proxyName, proxy] of Object.entries(userConfig.traffic?.proxies || {})) {
            const clusterKey = proxy.kafka ?? "default";
            if (!topicsByCluster.has(clusterKey)) topicsByCluster.set(clusterKey, new Set());
            topicsByCluster.get(clusterKey)!.add(proxy.kafkaTopic || proxyName);
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
    private buildProxies(userConfig: NormalizedUserConfig) {
        const kafkaClusters = resolveKafkaClusters(userConfig);
        return Object.entries(userConfig.traffic?.proxies || {}).map(([proxyName, proxy]) => {
            const sourceCluster = userConfig.sourceClusters[proxy.source];
            if (!sourceCluster) {
                throw new Error(`Proxy '${proxyName}' references unknown source cluster '${proxy.source}'`);
            }
            const topic = proxy.kafkaTopic || proxyName;
            return {
                name: proxyName,
                kafkaConfig: buildKafkaClientConfig(proxy.kafka ?? "default", kafkaClusters, topic),
                sourceEndpoint: sourceCluster.endpoint ?? "",
                sourceAllowInsecure: sourceCluster.allowInsecure ?? false,
                proxyConfig: proxy.proxyConfig
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
                if (!isGenerateSnapshot(snapshotDef.config)) continue;

                const repoConfig = snapshotInfo?.repos?.[snapshotDef.repoName];
                if (!repoConfig) {
                    throw new Error(`Snapshot '${snapshotName}' in source '${sourceName}' references repo '${snapshotDef.repoName}' which is not defined`);
                }

                const proxyDeps = proxyNamesBySource.get(sourceName);

                const { snapshotPrefix: _sp, ...createSnapshotOpts } = snapshotDef.config.createSnapshotConfig;
                const semaphore = this.generateSemaphoreConfig(
                    sourceCluster.version,
                    sourceName,
                    snapshotName,
                    snapshotInfo?.serializeSnapshotCreation
                );
                createConfigs.push({
                    label: snapshotName,
                    snapshotPrefix: snapshotDef.config.createSnapshotConfig.snapshotPrefix || snapshotName,
                    config: {
                        ...createSnapshotOpts,
                    },
                    repo: repoConfig,
                    ...semaphore,
                    ...{dependsOnProxySetups: (proxyDeps ?? []).map(name => ({
                        name,
                        configChecksum: ''
                    }))}
                });
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

            const sourceCluster = userConfig.sourceClusters[fromSource];
            const targetCluster = userConfig.targetClusters[toTarget];
            if (!targetCluster) {
                throw new Error(`Migration references unknown target cluster '${toTarget}'`);
            }

            // When perSnapshotConfig is not provided, auto-generate it from snapshotInfo.snapshots
            // so the workflow creates/waits for snapshots the same way for both ES and Solr sources.
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

                const isExternal = 'externallyManagedSnapshotName' in snapshotDef.config;
                const snapshotNameResolution = isExternal
                    ? { externalSnapshotName: (snapshotDef.config as z.infer<typeof EXTERNALLY_MANAGED_SNAPSHOT>).externallyManagedSnapshotName }
                    : { dataSnapshotResourceName: globallyUniqueSnapshotName };

                for (const migration of autoLabelMigrations(migrations)) {
                    results.push({
                        label: snapshotName,
                        migrationLabel: migration.label,
                        snapshotNameResolution,
                        snapshotConfigChecksum: '',
                        metadataMigrationConfig: migration.metadataMigrationConfig,
                        documentBackfillConfig: migration.documentBackfillConfig,
                        sourceVersion: sourceCluster.version || "",
                        sourceLabel: fromSource,
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

    /** Build traffic replay configs by resolving proxy → kafka chain. */
    private buildTrafficReplays(userConfig: NormalizedUserConfig) {
        const kafkaClusters = resolveKafkaClusters(userConfig);
        const proxies = userConfig.traffic?.proxies;

        return Object.entries(userConfig.traffic?.replayers || {}).map(([name, replayer]) => {
            const proxy = proxies?.[replayer.fromProxy];
            if (!proxy) {
                throw new Error(`Replayer references unknown proxy '${replayer.fromProxy}'`);
            }

            const targetCluster = userConfig.targetClusters[replayer.toTarget];
            if (!targetCluster) {
                throw new Error(`Replayer references unknown target cluster '${replayer.toTarget}'`);
            }

            const topic = proxy.kafkaTopic || replayer.fromProxy;
            const replayerConfig = ARGO_REPLAYER_OPTIONS.parse(replayer.replayerConfig ?? {});

            return {
                name: [replayer.fromProxy, replayer.toTarget, name].join('-'),
                fromProxy: replayer.fromProxy,
                kafkaClusterName: proxy.kafka ?? "default",
                kafkaConfig: buildKafkaClientConfig(proxy.kafka ?? "default", kafkaClusters, topic),
                toTarget: { ...targetCluster, label: replayer.toTarget },
                replayerConfig,
                ...(replayer.dependsOnSnapshotMigrations ? { dependsOnSnapshotMigrations: replayer.dependsOnSnapshotMigrations } : {}),
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

    static configChecksum(...parts: unknown[]): string {
        return createHash('sha256')
            .update(JSON.stringify(parts))
            .digest('hex')
            .slice(0, 16);
    }
}
