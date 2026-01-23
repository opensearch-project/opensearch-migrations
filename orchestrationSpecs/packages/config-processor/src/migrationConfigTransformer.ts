import {
    DENORMALIZED_S3_REPO_CONFIG, NORMALIZED_SNAPSHOT_MIGRATION_CONFIG,
    OVERALL_MIGRATION_CONFIG,
    PARAMETERIZED_MIGRATION_CONFIG_ARRAYS, PER_INDICES_SNAPSHOT_MIGRATION_CONFIG,
    S3_REPO_CONFIG,
    SNAPSHOT_MIGRATION_CONFIG, SOURCE_CLUSTER_REPOS_RECORD, USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG
} from '@opensearch-migrations/schemas';
import {StreamSchemaTransformer} from './streamSchemaTransformer';
import { z } from 'zod';
import {promises as dns} from "dns";

type InputConfig = z.infer<typeof OVERALL_MIGRATION_CONFIG>;
type OutputConfig = z.infer<typeof PARAMETERIZED_MIGRATION_CONFIG_ARRAYS>;

async function rewriteLocalStackEndpointToIp(s3Endpoint: string): Promise<string> {
    // Determine protocol based on localstack vs localstacks
    const isSecure = /^localstacks:\/\//i.test(s3Endpoint);
    const protocol = isSecure ? 'https://' : 'http://';

    // Extract hostname and port - first normalize to http(s):// for parsing
    const normalizedEndpoint = s3Endpoint.replace(/^localstacks?:\/\//i, protocol);
    const url = new URL(normalizedEndpoint);
    const localStackHostName = url.hostname;
    const port = url.port ? `:${url.port}` : '';

    const result = await dns.lookup(localStackHostName);
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

function namePerIndexSnapshotMigration(
    data: z.infer<typeof USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG>,
    idx: number) :
z.infer<typeof PER_INDICES_SNAPSHOT_MIGRATION_CONFIG> {
    const {label, ...rest} = data;
    return {
        ...({ label: label? label : `snapshot-migration-${idx.toString()}` }),
        ...rest
    };
}

export function setNamesInUserConfig(userConfig: InputConfig): InputConfig {
    const {migrationConfigs, ...rest} = userConfig;
    return {
        ...rest,
        migrationConfigs: migrationConfigs.map(mc => {
            const {snapshotExtractAndLoadConfigs, ...rest} = mc;

            const newSnapshotConfig = snapshotExtractAndLoadConfigs === undefined ? undefined :
                snapshotExtractAndLoadConfigs.map((sc, idx) => {
                    const {label, migrations, ...rest} = sc;
                    return {
                        ...rest,
                        ...(migrations ? { migrations: migrations.flatMap(namePerIndexSnapshotMigration) } : {}),
                        ...({ label: label? label : `snapshot-${idx.toString()}` }),
                    } as z.infer<typeof NORMALIZED_SNAPSHOT_MIGRATION_CONFIG>;
                });

            return {
                ...rest,
                ...(newSnapshotConfig === undefined ? {} : { snapshotExtractAndLoadConfigs: newSnapshotConfig })
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

export class MigrationConfigTransformer extends StreamSchemaTransformer<
    typeof OVERALL_MIGRATION_CONFIG,
    typeof PARAMETERIZED_MIGRATION_CONFIG_ARRAYS
> {
    constructor() {
        super(OVERALL_MIGRATION_CONFIG, PARAMETERIZED_MIGRATION_CONFIG_ARRAYS);
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

    // This actually returns a promise of an InputConfig+a couple modifications around snapshot repos
    private async preprocessInput(input: InputConfig): Promise<InputConfig> {
        const processedSourceClusters = {...input.sourceClusters};

        for (const [name, cluster] of Object.entries(input.sourceClusters)) {
            if (cluster.snapshotRepos !== undefined) {
                processedSourceClusters[name] = {
                    ...cluster,
                    snapshotRepos: await rewriteRepoRecordEndpointIfLocalStack(cluster.snapshotRepos)
                };
            }
        }

        return {
            ...input,
            sourceClusters: processedSourceClusters
        };
    }

    private transformSync(userConfig: InputConfig): OutputConfig {
        userConfig = setNamesInUserConfig(userConfig);
        const seen = new Set<string>();
        const duplicates = new Set<string>();

        const output = userConfig.migrationConfigs.map(mc => {
            let {fromSource, toTarget, snapshotExtractAndLoadConfigs, replayerConfig} = mc;

            const keyPair = `${fromSource} => ${toTarget}`;
            if (seen.has(keyPair)) {
                duplicates.add(keyPair);
            }
            seen.add(keyPair);

            const sourceCluster = userConfig.sourceClusters[fromSource];
            if (sourceCluster.proxy === undefined) {
                console.warn(`Replayer is configured for ${fromSource} but a proxy is not. ` +
                    `A replayer won't be configured for the target (${toTarget})`);
                replayerConfig = undefined;
            }

            const newSnapshotConfig = snapshotExtractAndLoadConfigs === undefined ? undefined :
                snapshotExtractAndLoadConfigs.map((sc, idx) => {
                    const {snapshotConfig, createSnapshotConfig, label, ...rest} = sc;
                    if (sourceCluster.snapshotRepos === undefined) {
                        throw Error(`Configured a snapshot repo with ${snapshotConfig}, for ${fromSource}. but the source cluster definition does not define a repo.`);
                    }
                    
                    let enhancedCreateSnapshotConfig = createSnapshotConfig;
                    if (createSnapshotConfig && snapshotConfig) {
                        enhancedCreateSnapshotConfig = {
                            ...createSnapshotConfig,
                            ...this.generateSemaphoreConfig(sourceCluster.version || "", fromSource, snapshotConfig)
                        };
                    }
                    
                    return {
                        ...rest,
                        label,
                        ...(snapshotConfig !== undefined && {
                            snapshotConfig: {
                                snapshotNameConfig: snapshotConfig.snapshotNameConfig,
                                repoConfig: sourceCluster.snapshotRepos[snapshotConfig.repoName] ?? {},
                                label
                            }
                        }),
                        ...(enhancedCreateSnapshotConfig !== undefined && {
                            createSnapshotConfig: enhancedCreateSnapshotConfig
                        })
                    }// as z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>;
                });

            const { snapshotRepos, ...restOfSourceCluster } = sourceCluster; // drop the normalized form of the repos
            return {
                sourceConfig: {...restOfSourceCluster, label: fromSource},
                targetConfig: {...userConfig.targetClusters[toTarget], label: toTarget},
                ...(newSnapshotConfig === undefined ? {} : { snapshotExtractAndLoadConfigArray: newSnapshotConfig }),
                ...(replayerConfig === undefined ? {} : { replayerConfig})
            };
        }) as OutputConfig;

        if (duplicates.size > 0) {
            throw new Error("Found duplicate source-target bindings.  This is most likely an error.  " +
                "Define separate sources with an equivalent structure if you think you need this.\n" +
                "Duplicates: " + [...duplicates].join(","));
        }

        try {
            return PARAMETERIZED_MIGRATION_CONFIG_ARRAYS.parse(output);
        } catch (error) {
            throw new Error("Error while safely parsing the transformed workflow " +
                "as a configuration for the argo workflow.", { cause: error} );
        }
    }

    private generateSemaphoreConfig(sourceVersion: string, sourceName: string, snapshotConfig: any) {
        const isLegacyVersion = /^(?:ES [1-7]|OS 1)(?:\.[0-9]+)*$/.test(sourceVersion);
        
        let semaphoreKey: string;
        if (isLegacyVersion) {
            // Legacy versions: shared semaphore per source cluster
            semaphoreKey = `snapshot-legacy-${sourceName}`;
        } else {
            // Modern versions: unique key per snapshot (no effective limiting)
            const snapshotName = snapshotConfig?.snapshotNameConfig?.snapshotNamePrefix || 
                               snapshotConfig?.snapshotNameConfig?.externallyManagedSnapshot || 
                               'unknown';
            semaphoreKey = `snapshot-modern-${sourceName}-${snapshotName}`;
        }

        return {
            semaphoreConfigMapName: 'concurrency-config',
            semaphoreKey: semaphoreKey
        };
    }
}