import { z, ZodTypeAny } from 'zod';
import {parse} from "yaml";
import * as fs from "node:fs";
import {
    NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG,
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
    perSnapshotConfig: NonNullable<z.infer<typeof NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG>['perSnapshotConfig']>
};

export function scrapeApprovalsForSnapshotConfigs(
    perSnapshotConfig: ConfigWithSnapshot['perSnapshotConfig'],
    globalSkipApprovals: boolean,
    perMigrationSkipApprovals: boolean
) {
    const skipAll = globalSkipApprovals || perMigrationSkipApprovals;
    return Object.fromEntries(
        Object.entries(perSnapshotConfig).map(([snapshotName, migrations]) =>
            [snapshotName,
                Object.fromEntries(
                    migrations.map((migrationCfg, idx) =>
                        [migrationCfg.label || `migration-${idx}`, {
                            ...( (skipAll || migrationCfg.metadataMigrationConfig?.skipEvaluateApproval) ? { evaluateMetadata: true } : {}),
                            ...( (skipAll || migrationCfg.metadataMigrationConfig?.skipMigrateApproval) ? { migrateMetadata: true } : {}),
                            ...( (skipAll || migrationCfg.documentBackfillConfig?.skipApproval) ? { documentBackfill: true } : {}),
                        }])
                )
            ])
    );
}

export function scrapeApprovals(userConfig: z.infer<typeof OVERALL_MIGRATION_CONFIG>) {
    const globalSkipApprovals = userConfig.skipApprovals ?? false;
    return Object.fromEntries(
        Object.entries(Object.groupBy(userConfig.snapshotMigrationConfigs, m=>m.fromSource))
            .filter(hasDefinedList)
            .map(([source,topConfigs])=>
                [source, Object.fromEntries(
                    Object.entries(Object.groupBy(topConfigs, m=>m.toTarget))
                        .filter(hasDefinedList)
                        .filter(([_,v])=>v.length > 0)
                        .filter((entry): entry is [string, ConfigWithSnapshot[]] =>
                            entry[1][0].perSnapshotConfig !== undefined)
                        .map(([target,cfgs])=>
                            cfgs.flatMap(c=>
                                [target, scrapeApprovalsForSnapshotConfigs(
                                    c.perSnapshotConfig,
                                    globalSkipApprovals,
                                    c.skipApprovals ?? false
                                )]
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
