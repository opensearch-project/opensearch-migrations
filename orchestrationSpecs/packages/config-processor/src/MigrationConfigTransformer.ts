import {
    DYNAMIC_SNAPSHOT_CONFIG,
    OVERALL_MIGRATION_CONFIG,
    PARAMETERIZED_MIGRATION_CONFIG_ARRAYS, SNAPSHOT_MIGRATION_CONFIG
} from '@opensearch-migrations/schemas';
import {deepStrict, StreamSchemaTransformer} from './StreamSchemaTransformer';
import { z } from 'zod';

type InputConfig = z.infer<typeof OVERALL_MIGRATION_CONFIG>;
type OutputConfig = z.infer<typeof PARAMETERIZED_MIGRATION_CONFIG_ARRAYS>;

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
    transform(input: InputConfig): OutputConfig {
        const seen = new Set<string>();
        const duplicates = new Set<string>();

        const output = input.migrationConfigs.map(mc => {
            let {fromSource, toTarget, snapshotExtractAndLoadConfigs, replayerConfig} = mc;

            const keyPair = `${fromSource} => ${toTarget}`;
            if (seen.has(keyPair)) {
                duplicates.add(keyPair);
            }
            seen.add(keyPair);

            const sourceCluster = input.sourceClusters[fromSource];
            if (sourceCluster.proxy === undefined) {
                console.warn(`Replayer is configured for ${fromSource} but a proxy is not.  " + 
                       "A replayer won't be configured for the target (${toTarget})`);
                replayerConfig = undefined;
            }
            const newSnapshotConfig =
                snapshotExtractAndLoadConfigs?.map(sc=> {
                    const {snapshotConfig, indices, migrations} = sc;
                    if (snapshotConfig !== undefined && sourceCluster.snapshotRepo === undefined) {
                        throw Error(`Configured a snapshot repo with ${snapshotConfig}, for ${fromSource}. but the source cluster definition does not define a repo.`);
                    }
                    return {
                        indices,
                        migrations,
                        snapshotConfig: {
                            snapshotName: snapshotConfig.snapshotName,
                            repoConfig: (sourceCluster.snapshotRepo === undefined ? {} : sourceCluster.snapshotRepo)
                        }
                    } as z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>;
                }
            )
            return {
                sourceConfig: {...sourceCluster, name: fromSource},
                targetConfig: {...input.targetClusters[toTarget], name: toTarget},
                ...(newSnapshotConfig === undefined ? {} : { snapshotExtractAndLoadConfigArray: newSnapshotConfig }),
                ...(replayerConfig === undefined ? {} : { replayerConfig})
            };
        }) as OutputConfig;
        if (duplicates.size > 0) {
            throw new Error("Found duplicate source-target bindings.  This is most likely an error.  " +
                "Define separate sources with an equivalent structure if you think you need this.\n" +
                "Duplicates: " + [...duplicates].join(","));
        }
        return PARAMETERIZED_MIGRATION_CONFIG_ARRAYS.parse(output);
    }
}
