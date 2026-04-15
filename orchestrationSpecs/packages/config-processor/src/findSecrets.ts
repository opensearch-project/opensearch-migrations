import { z, ZodTypeAny } from 'zod';
import {
    HTTP_AUTH_BASIC, K8S_NAMING_PATTERN,
    NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG,
    OVERALL_MIGRATION_CONFIG,
} from "@opensearch-migrations/schemas";
import {Console} from "console";
import {parseUserConfig, parseYaml} from "./userConfigReader";
import {MigrationConfigTransformer, setNamesInUserConfig} from "./migrationConfigTransformer";
import {formatInputValidationError, InputValidationError} from "./streamSchemaTransformer";
import {YAMLParseError} from "yaml";

global.console = new Console({
    stdout: process.stderr,
    stderr: process.stderr
});

const COMMAND_LINE_HELP_MESSAGE = `Usage: find-secrets [input-file|-]

Validate a user workflow configuration and scrape all occurrences of secret names.
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

export function getCategorizedCredentialsSecretsFromConfig(
    userConfig: Partial<z.infer<typeof OVERALL_MIGRATION_CONFIG>>
) {
    const rawSecrets = scrapeSecrets(userConfig);
    return Object.groupBy(rawSecrets, s=> s && s.match(K8S_NAMING_PATTERN) ? "validSecrets" : "invalidSecrets");
}

export async function main() {
    const args = process.argv.slice(2);

    if (args.length === 0) {
        console.error("Error: no args provided.");
        console.error(COMMAND_LINE_HELP_MESSAGE);
        process.exit(2);
    }

    let data;
    try {
        data = await parseYaml(args[0]);
    } catch (error) {
        if (error instanceof YAMLParseError) {
            process.stdout.write(JSON.stringify({valid: false, errors: `YAML parse error: ${error.message}`}));
            return;
        }
        process.stdout.write(JSON.stringify({valid: false, errors: String(error)}));
        return;
    }

    if (!data) {
        process.stdout.write(JSON.stringify({valid: false, errors: "Configuration was empty"}));
        return;
    }

    // Validate against full Zod schema with refinements
    try {
        const transformer = new MigrationConfigTransformer();
        transformer.validateInput(data);
    } catch (error) {
        if (error instanceof InputValidationError) {
            process.stdout.write(JSON.stringify({valid: false, errors: formatInputValidationError(error)}));
        } else if (error instanceof z.ZodError) {
            process.stdout.write(JSON.stringify({valid: false, errors: JSON.stringify(error.issues, null, 2)}));
        } else {
            process.stdout.write(JSON.stringify({valid: false, errors: String(error)}));
        }
        return;
    }

    // Validation passed — scrape secrets
    const secrets = getCategorizedCredentialsSecretsFromConfig(data);
    process.stdout.write(JSON.stringify({valid: true, ...secrets}));
}

if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch((error) => {
        console.error('Unhandled error:', error);
        process.exit(1);
    });
}
