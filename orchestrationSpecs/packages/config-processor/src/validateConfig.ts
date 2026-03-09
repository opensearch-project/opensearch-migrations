import {MigrationConfigTransformer} from "./migrationConfigTransformer";
import {formatInputValidationError, InputValidationError} from "./streamSchemaTransformer";
import {parseYaml} from "./userConfigReader";
import {YAMLParseError} from "yaml";
import {Console} from "console";
import {z} from "zod";

global.console = new Console({
    stdout: process.stderr,
    stderr: process.stderr
});

export async function main() {
    const args = process.argv.slice(2);

    if (args.length === 0) {
        console.error("Usage: validate [input-file|-]");
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

    try {
        const transformer = new MigrationConfigTransformer();
        transformer.validateInput(data);
        process.stdout.write(JSON.stringify({valid: true}));
    } catch (error) {
        if (error instanceof InputValidationError) {
            process.stdout.write(JSON.stringify({valid: false, errors: formatInputValidationError(error)}));
        } else if (error instanceof z.ZodError) {
            process.stdout.write(JSON.stringify({valid: false, errors: JSON.stringify(error.issues, null, 2)}));
        } else {
            process.stdout.write(JSON.stringify({valid: false, errors: String(error)}));
        }
    }
}

if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch((error) => {
        console.error('Fatal error:', error);
        process.exit(1);
    });
}
