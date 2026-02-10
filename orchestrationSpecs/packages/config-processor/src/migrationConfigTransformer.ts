import {
    DENORMALIZED_S3_REPO_CONFIG,
    OVERALL_MIGRATION_CONFIG,
    S3_REPO_CONFIG,
    SOURCE_CLUSTER_REPOS_RECORD, USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG,
    ARGO_MIGRATION_CONFIG, KAFKA_CLUSTER_CONFIG, CAPTURE_CONFIG,
    GENERATE_SNAPSHOT,
} from '@opensearch-migrations/schemas';
import {StreamSchemaTransformer} from './streamSchemaTransformer';
import { z } from 'zod';
import {promises as dns} from "dns";
import { generateSemaphoreKey } from './semaphoreUtils';

type InputConfig = z.infer<typeof OVERALL_MIGRATION_CONFIG>;
type OutputConfig = z.infer<typeof ARGO_MIGRATION_CONFIG>;

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
        return {
            ...cluster.existing,
            kafkaTopic: topic || cluster.existing.kafkaTopic,
            label: kafkaClusterKey
        };
    }
    // autoCreate — connection not known at transform time, resolved by workflow
    return {
        enableMSKAuth: false,
        kafkaConnection: "",
        kafkaTopic: topic,
        label: kafkaClusterKey
    };
}

function isGenerateSnapshot(config: any): config is z.infer<typeof GENERATE_SNAPSHOT> {
    return 'createSnapshotConfig' in config;
}

export class MigrationConfigTransformer extends StreamSchemaTransformer<
    typeof OVERALL_MIGRATION_CONFIG,
    typeof ARGO_MIGRATION_CONFIG
> {
    constructor() {
        super(OVERALL_MIGRATION_CONFIG, ARGO_MIGRATION_CONFIG);
    }

    validateInput(data: unknown): InputConfig {
        // First pass: normal schema validation (including refinements)
        const obj = super.validateInput(data);
        
        // Second pass: check for extra keys
        validateNoExtraKeys(data, OVERALL_MIGRATION_CONFIG);
        
        return obj;
    }

    async transform(input: InputConfig): Promise<OutputConfig> {
        const processedInput = await this.preprocessInput(input);
        return this.transformSync(processedInput);
    }

    private async preprocessInput(input: InputConfig): Promise<InputConfig> {
        const processedSourceClusters = {...input.sourceClusters};

        for (const [name, cluster] of Object.entries(input.sourceClusters)) {
            if (cluster.snapshotInfo.repos !== undefined) {
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

    private transformSync(userConfig: InputConfig): OutputConfig {
        const kafkaClusters = this.buildKafkaClusters(userConfig);
        const proxies = this.buildProxies(userConfig);
        const snapshots = this.buildSnapshots(userConfig);
        const snapshotMigrations = this.buildSnapshotMigrations(userConfig);
        const trafficReplays = this.buildTrafficReplays(userConfig);

        const output = {
            ...(kafkaClusters.length > 0 ? { kafkaClusters } : {}),
            proxies,
            snapshots,
            snapshotMigrations,
            trafficReplays,
        };

        try {
            return ARGO_MIGRATION_CONFIG.parse(output);
        } catch (error) {
            throw new Error("Error while safely parsing the transformed workflow " +
                "as a configuration for the argo workflow.", { cause: error });
        }
    }

    /** Collect auto-created kafka clusters with their aggregated topics from proxies. */
    private buildKafkaClusters(userConfig: InputConfig) {
        const kafkaClusters = userConfig.kafkaClusterConfiguration ?? {};
        // Aggregate topics per kafka cluster from proxies
        const topicsByCluster = new Map<string, Set<string>>();
        for (const [proxyName, proxy] of Object.entries(userConfig.traffic.proxies)) {
            const clusterKey = proxy.kafka ?? "default";
            if (!topicsByCluster.has(clusterKey)) topicsByCluster.set(clusterKey, new Set());
            topicsByCluster.get(clusterKey)!.add(proxy.kafkaTopic || proxyName);
        }

        return Object.entries(kafkaClusters)
            .filter(([_, config]) => 'autoCreate' in config)
            .map(([name, config]) => ({
                name,
                config: (config as any).autoCreate,
                topics: [...(topicsByCluster.get(name) ?? [])]
            }));
    }

    /** Denormalize each proxy with source endpoint and kafka client config. */
    private buildProxies(userConfig: InputConfig) {
        const kafkaClusters = userConfig.kafkaClusterConfiguration ?? {};
        return Object.entries(userConfig.traffic.proxies).map(([proxyName, proxy]) => {
            const sourceCluster = userConfig.sourceClusters[proxy.source];
            if (!sourceCluster) {
                throw new Error(`Proxy '${proxyName}' references unknown source cluster '${proxy.source}'`);
            }
            const topic = proxy.kafkaTopic || proxyName;
            return {
                name: proxyName,
                kafkaConfig: buildKafkaClientConfig(proxy.kafka ?? "default", kafkaClusters, topic),
                sourceEndpoint: sourceCluster.endpoint ?? "",
                proxyConfig: proxy.proxyConfig
            };
        });
    }

    /** Build snapshot creation configs grouped by source cluster. */
    private buildSnapshots(userConfig: InputConfig) {
        // Build a map of source → proxy names for dependsUponProxySetups
        const proxyNamesBySource = new Map<string, string[]>();
        for (const [proxyName, proxy] of Object.entries(userConfig.traffic.proxies)) {
            const source = proxy.source;
            if (!proxyNamesBySource.has(source)) proxyNamesBySource.set(source, []);
            proxyNamesBySource.get(source)!.push(proxyName);
        }

        const results: any[] = [];
        for (const [sourceName, sourceCluster] of Object.entries(userConfig.sourceClusters)) {
            const snapshotInfo = sourceCluster.snapshotInfo;
            const createConfigs: any[] = [];

            for (const [snapshotName, snapshotDef] of Object.entries(snapshotInfo.snapshots)) {
                if (!isGenerateSnapshot(snapshotDef.config)) continue;

                const repoConfig = snapshotInfo.repos?.[snapshotDef.repoName];
                if (!repoConfig) {
                    throw new Error(`Snapshot '${snapshotName}' in source '${sourceName}' references repo '${snapshotDef.repoName}' which is not defined`);
                }

                const globalSnapshotName = `${sourceName}.${snapshotName}`;
                const proxyDeps = proxyNamesBySource.get(sourceName);

                const { snapshotPrefix: _sp, ...createSnapshotOpts } = snapshotDef.config.createSnapshotConfig;
                const semaphore = this.generateSemaphoreConfig(sourceCluster.version, sourceName, snapshotName);
                createConfigs.push({
                    snapshotPrefix: snapshotDef.config.createSnapshotConfig.snapshotPrefix || globalSnapshotName,
                    config: {
                        ...createSnapshotOpts,
                    },
                    repo: { ...repoConfig, useLocalStack: /^localstacks?:\/\//i.test(repoConfig.endpoint ?? ""), repoName: snapshotDef.repoName },
                    ...semaphore,
                    ...(proxyDeps && proxyDeps.length > 0 ? { dependsUponProxySetups: proxyDeps } : {})
                });
            }

            if (createConfigs.length > 0) {
                const { snapshotInfo: _si, enabled: _e1, ...restOfSource } = sourceCluster;
                results.push({
                    createSnapshotConfig: createConfigs,
                    sourceConfig: { ...restOfSource, label: sourceName }
                });
            }
        }
        return results;
    }

    /** Build snapshot migration configs from snapshotMigrationConfigs + perSnapshotConfig. */
    private buildSnapshotMigrations(userConfig: InputConfig) {
        const results: any[] = [];

        for (const mc of userConfig.snapshotMigrationConfigs) {
            const { fromSource, toTarget, perSnapshotConfig } = mc;
            if (!perSnapshotConfig) continue;

            const sourceCluster = userConfig.sourceClusters[fromSource];
            const targetCluster = userConfig.targetClusters[toTarget];
            if (!targetCluster) {
                throw new Error(`Migration references unknown target cluster '${toTarget}'`);
            }

            const { enabled: _e2, ...restOfTarget } = targetCluster;
            const { snapshotInfo: _si, enabled: _e1, ...restOfSource } = sourceCluster;

            for (const [snapshotName, migrations] of Object.entries(perSnapshotConfig)) {
                const snapshotDef = sourceCluster.snapshotInfo.snapshots[snapshotName];
                if (!snapshotDef) {
                    throw new Error(`Migration references snapshot '${snapshotName}' not defined in source '${fromSource}'`);
                }

                const globalSnapshotName = `${fromSource}.${snapshotName}`;
                const repoConfig = sourceCluster.snapshotInfo.repos?.[snapshotDef.repoName];

                results.push({
                    label: globalSnapshotName,
                    snapshotLabel: globalSnapshotName,
                    migrations: autoLabelMigrations(migrations),
                    sourceVersion: sourceCluster.version || "",
                    sourceLabel: fromSource,
                    targetConfig: { ...restOfTarget, label: toTarget },
                    snapshotConfig: {
                        snapshotName: globalSnapshotName,
                        label: globalSnapshotName,
                        ...(repoConfig ? {
                            repoConfig: {
                                ...repoConfig,
                                useLocalStack: /^localstacks?:\/\//i.test(repoConfig.endpoint ?? ""),
                                repoName: snapshotDef.repoName
                            }
                        } : {})
                    }
                });
            }
        }
        return results;
    }

    /** Build traffic replay configs by resolving proxy → kafka chain. */
    private buildTrafficReplays(userConfig: InputConfig) {
        const kafkaClusters = userConfig.kafkaClusterConfiguration ?? {};
        const proxies = userConfig.traffic.proxies;

        return Object.entries(userConfig.traffic.replayers).map(([_name, replayer]) => {
            const proxy = proxies[replayer.fromProxy];
            if (!proxy) {
                throw new Error(`Replayer references unknown proxy '${replayer.fromProxy}'`);
            }

            const targetCluster = userConfig.targetClusters[replayer.toTarget];
            if (!targetCluster) {
                throw new Error(`Replayer references unknown target cluster '${replayer.toTarget}'`);
            }

            const topic = proxy.kafkaTopic || replayer.fromProxy;
            const { enabled: _e3, ...restOfTarget } = targetCluster;

            return {
                fromProxy: replayer.fromProxy,
                kafkaConfig: buildKafkaClientConfig(proxy.kafka ?? "default", kafkaClusters, topic),
                toTarget: { ...restOfTarget, label: replayer.toTarget },
                ...(replayer.dependsOnSnapshotMigrations ? { dependsOnSnapshotMigrations: replayer.dependsOnSnapshotMigrations } : {}),
                ...(replayer.replayerConfig ? { replayerConfig: replayer.replayerConfig } : {})
            };
        });
    }

    private generateSemaphoreConfig(sourceVersion: string, sourceName: string, snapshotName: string) {
        return {
            semaphoreConfigMapName: 'concurrency-config',
            semaphoreKey: generateSemaphoreKey(sourceVersion, sourceName, snapshotName)
        };
    }
}
