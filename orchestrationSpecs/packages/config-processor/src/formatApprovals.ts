import { z, ZodTypeAny } from 'zod';
import {parse} from "yaml";
import * as fs from "node:fs";
import {
    NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG, NORMALIZED_SNAPSHOT_MIGRATION_CONFIG,
    OVERALL_MIGRATION_CONFIG,
    zodSchemaToJsonSchema
} from "@opensearch-migrations/schemas";
import {Console} from "console";
import {parseUserConfig} from "./userConfigReader";
import {setNamesInUserConfig} from "./migrationConfigTransformer";

global.console = new Console({
    stdout: process.stderr,
    stderr: process.stderr
});

const COMMAND_LINE_HELP_MESSAGE = `Usage: format-approvals [input-file|-]

Convert a user configuration into the approval configmap, pulling all of the approval flags
from the configuration and denormalizing them for each approval step so that the workflow
can do a simple check for each approval (suspension) step.
`

function hasDefinedList<K, V>(
    entry: [K, V[] | undefined]
): entry is [K, V[]] {
    return entry[1] !== undefined;
}

type ConfigWithSnapshot = z.infer<typeof NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG> & {
    snapshotExtractAndLoadConfigs: z.infer<typeof NORMALIZED_SNAPSHOT_MIGRATION_CONFIG>[]
};

export function scrapeApprovalsForSnapshotConfigs(perSnapshotCfgs: z.infer<typeof NORMALIZED_SNAPSHOT_MIGRATION_CONFIG>[]) {
    return Object.fromEntries(
        perSnapshotCfgs.map(snapshotCfg=>[snapshotCfg.name,
            Object.fromEntries(
                snapshotCfg.migrations.map(migrationCfg =>
                    [migrationCfg.name, {
                    ...( migrationCfg.metadataMigrationConfig?.skipEvaluateApproval ? { evaluateMetadata: true } : {}),
                    ...( migrationCfg.metadataMigrationConfig?.skipMigrateApproval ? { migrateMetadata: true } : {}),
                }])
            )
        ])
    );
}

export function scrapeApprovals(userConfig: z.infer<typeof OVERALL_MIGRATION_CONFIG>) {
    return Object.fromEntries(
        Object.entries(Object.groupBy(userConfig.migrationConfigs, m=>m.fromSource))
            .filter(hasDefinedList)
            .map(([source,topConfigs])=>
                [source, Object.fromEntries(
                    Object.entries(Object.groupBy(topConfigs, m=>m.toTarget))
                        .filter(hasDefinedList)
                        .filter(([_,v])=>v.length > 0)
                        .filter((entry): entry is [string, ConfigWithSnapshot[]] =>
                            entry[1][0].snapshotExtractAndLoadConfigs !== undefined)
                        .map(([target,cfgs])=>
                            cfgs.flatMap(c=>
                                [target,scrapeApprovalsForSnapshotConfigs(c.snapshotExtractAndLoadConfigs)]
                            )
                        )
                )]
            )
    );
}

export async function main() {
    const args = process.argv.slice(2);

    if (args.length === 0) {
        console.error("Error: no args provided.");
        console.error(COMMAND_LINE_HELP_MESSAGE);
        process.exit(2);
    }

    let userConfig
    try {
        userConfig = await parseUserConfig(args[0]);
    } catch (error) {
        console.error('Error loading YAML:', error);
        console.error(COMMAND_LINE_HELP_MESSAGE);
        process.exit(3);
    }


    const reducedData = scrapeApprovals(setNamesInUserConfig(userConfig));
    process.stdout.write(JSON.stringify(reducedData, null, 2));
}

if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch((error) => {
        console.error('Unhandled error:', error);
        process.exit(1);
    });
}
