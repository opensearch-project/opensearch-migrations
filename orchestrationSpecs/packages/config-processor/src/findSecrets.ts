import { z, ZodTypeAny } from 'zod';
import {
    HTTP_AUTH_BASIC, K8S_NAMING_PATTERN,
    NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG,
    OVERALL_MIGRATION_CONFIG,
} from "@opensearch-migrations/schemas";
import {Console} from "console";
import {parseUserConfig, parseYaml} from "./userConfigReader";
import {setNamesInUserConfig} from "./migrationConfigTransformer";
import {YAMLParseError} from "yaml";

global.console = new Console({
    stdout: process.stderr,
    stderr: process.stderr
});

const COMMAND_LINE_HELP_MESSAGE = `Usage: find-secrets [input-file|-]

Scrape a user workflow configuration for all occurrences of secret names.
`

function isBasicAuth(ac: unknown): ac is z.infer<typeof HTTP_AUTH_BASIC> {
    return ac !== undefined && typeof ac === 'object' && ac !== null && 'basic' in ac;
}

function getClustersFromMap<T>(m: Record<string, T>) {
    return Object.entries(m).map(([k,v])=>v);
}

export function scrapeSecrets(userConfig: Partial<z.infer<typeof OVERALL_MIGRATION_CONFIG>>) {
    return [
        ...getClustersFromMap(userConfig.sourceClusters ?? {}),
        ...getClustersFromMap(userConfig.targetClusters ?? {})
    ]
        .map(c => c?.authConfig)
        .filter(isBasicAuth)
        .map(ac => ac.basic.secretName);
}

export function scrapeAndCategorize(userConfig: Partial<z.infer<typeof OVERALL_MIGRATION_CONFIG>>) {
    const rawSecrets = scrapeSecrets(userConfig);
    return Object.groupBy(rawSecrets, s=> s.match(K8S_NAMING_PATTERN) ? "validSecrets" : "invalidSecrets");
}

export async function main() {
    const args = process.argv.slice(2);

    if (args.length === 0) {
        console.error("Error: no args provided.");
        console.error(COMMAND_LINE_HELP_MESSAGE);
        process.exit(2);
    }

    let userConfig;
    try {
        userConfig = await parseYaml(args[0]);
    } catch (error) {
        if (error instanceof YAMLParseError) {
            console.error('Error: YAML parse error: ' + error.message);
            process.exit(3);
        } else if (error instanceof Error) {
            console.error('Error: Problem loading configuration: ' + error.message);
            process.exit(4);
        }
        console.error('Error: Unknown error loading configuration: ' + String(error));
        process.exit(5);
    }

    if (userConfig) {
        const reducedData = scrapeAndCategorize(userConfig);
        process.stdout.write(JSON.stringify(reducedData, null, 2));
    } else {
        console.error("Could not ");
    }
}

if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch((error) => {
        console.error('Unhandled error:', error);
        process.exit(1);
    });
}
