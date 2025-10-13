import {
    DENORMALIZED_S3_REPO_CONFIG,
    OVERALL_MIGRATION_CONFIG,
    PARAMETERIZED_MIGRATION_CONFIG_ARRAYS,
    S3_REPO_CONFIG,
    SNAPSHOT_MIGRATION_CONFIG
} from '@opensearch-migrations/schemas';
import {deepStrict, StreamSchemaTransformer} from './StreamSchemaTransformer';
import { z } from 'zod';
import {promises as dns} from "dns";

type InputConfig = z.infer<typeof OVERALL_MIGRATION_CONFIG>;
type OutputConfig = z.infer<typeof PARAMETERIZED_MIGRATION_CONFIG_ARRAYS>;

async function rewriteLocalStackEndpointToIp(s3Endpoint: string): Promise<string> {
    // Determine protocol based on localstack vs localstacks
    const isSecure = /^localstacks:\/\//i.test(s3Endpoint);
    const protocol = isSecure ? 'https://' : 'http://';

    // Extract hostname and port
    const withoutPrefix = s3Endpoint.replace(/^localstacks?:\/\//i, '');
    const portMatch = withoutPrefix.match(/:\d+/);
    const port = portMatch ? portMatch[0] : '';

    const localStackHostName = withoutPrefix
        .replace(/\/.*$/, '')  // Remove path
        .replace(/:\d+$/, '');  // Remove port

    const result = await dns.lookup(localStackHostName);
    let s3Ip = result.address;

    if (result.family === 6) {
        s3Ip = `[${s3Ip}]`;
    }

    return `${protocol}${s3Ip}${port}`;
}

async function rewriteEndpointIfLocalStack(snapshotRepo: z.infer<typeof S3_REPO_CONFIG>):
    Promise<z.infer<typeof DENORMALIZED_S3_REPO_CONFIG>>
{
    const useLocalStack = /^localstacks?:\/\//i.test(snapshotRepo.endpoint);
    if (useLocalStack) {
        snapshotRepo.endpoint = await rewriteLocalStackEndpointToIp(snapshotRepo.endpoint);
    }
    return {...snapshotRepo, useLocalStack };
}

export class MigrationConfigTransformer extends StreamSchemaTransformer<
    typeof OVERALL_MIGRATION_CONFIG,
    typeof PARAMETERIZED_MIGRATION_CONFIG_ARRAYS
> {
    constructor() {
        super(deepStrict(OVERALL_MIGRATION_CONFIG), PARAMETERIZED_MIGRATION_CONFIG_ARRAYS);
    }

    validateInput(data: unknown): InputConfig {
        const obj = super.validateInput(data);
        return obj;
    }

    /**
     * Custom transformation logic
     */
    async transform(input: InputConfig): Promise<OutputConfig> {
        const seen = new Set<string>();
        const duplicates = new Set<string>();

        // Use Promise.all to handle all async operations
        const output = await Promise.all(input.migrationConfigs.map(async mc => {
            let {fromSource, toTarget, snapshotExtractAndLoadConfigs, replayerConfig} = mc;

            const keyPair = `${fromSource} => ${toTarget}`;
            if (seen.has(keyPair)) {
                duplicates.add(keyPair);
            }
            seen.add(keyPair);

            const sourceCluster = input.sourceClusters[fromSource];
            if (sourceCluster.proxy === undefined) {
                console.warn(`Replayer is configured for ${fromSource} but a proxy is not. " + 
                       "A replayer won't be configured for the target (${toTarget})`);
                replayerConfig = undefined;
            }
            const newSnapshotConfig = snapshotExtractAndLoadConfigs === undefined ? undefined :
                await Promise.all(snapshotExtractAndLoadConfigs.map(async sc => {
                    const {snapshotConfig, indices, migrations} = sc;
                    if (snapshotConfig !== undefined && sourceCluster.snapshotRepo === undefined) {
                        throw Error(`Configured a snapshot repo with ${snapshotConfig}, for ${fromSource}. but the source cluster definition does not define a repo.`);
                    }
                    return {
                        indices,
                        migrations,
                        snapshotConfig: {
                            snapshotName: snapshotConfig.snapshotName,
                            repoConfig: (sourceCluster.snapshotRepo === undefined ? {} :
                                await rewriteEndpointIfLocalStack(sourceCluster.snapshotRepo))
                        }
                    } as z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>;
                })
            )
            return {
                sourceConfig: {...sourceCluster, name: fromSource},
                targetConfig: {...input.targetClusters[toTarget], name: toTarget},
                ...(newSnapshotConfig === undefined ? {} : { snapshotExtractAndLoadConfigArray: newSnapshotConfig }),
                ...(replayerConfig === undefined ? {} : { replayerConfig})
            };
        })) as OutputConfig;
        if (duplicates.size > 0) {
            throw new Error("Found duplicate source-target bindings.  This is most likely an error.  " +
                "Define separate sources with an equivalent structure if you think you need this.\n" +
                "Duplicates: " + [...duplicates].join(","));
        }
        return PARAMETERIZED_MIGRATION_CONFIG_ARRAYS.parse(output);
    }
}