import {
    applyEditOperation as applyCoreEditOperation,
    buildEditStateFromObject as buildCoreEditStateFromObject,
    buildEditStateFromObjectWithValidation,
    inputSchemaValidationError,
    rawRepairState,
    syntaxValidation,
    validationFromError,
    validationSuccess,
} from "@opensearch-migrations/config-edit-core";
import type {
    ConfigEditCoreOptions,
    EditApplyResultV1,
    EditOperation,
    EditStateV1,
} from "@opensearch-migrations/config-edit-core";
import {loadUnifiedSchema} from "@opensearch-migrations/schemas";
import {parse, stringify} from "yaml";
import {MigrationConfigTransformer} from "./migrationConfigTransformer";
import {parseYaml, readYamlText} from "./userConfigReader";

function coreOptions(): ConfigEditCoreOptions {
    return {
        unifiedSchema: loadUnifiedSchema().schema,
    };
}

export function validationForConfig(config: unknown): EditStateV1["validation"] {
    const schemaValidation = inputSchemaValidationError(config);
    if (schemaValidation) {
        return schemaValidation;
    }
    try {
        new MigrationConfigTransformer().validateInput(config);
        return validationSuccess();
    } catch (error) {
        return validationFromError(error);
    }
}

export async function submitValidationForConfig(config: unknown): Promise<EditStateV1["validation"]> {
    const schemaValidation = inputSchemaValidationError(config);
    if (schemaValidation) {
        return schemaValidation;
    }
    try {
        await new MigrationConfigTransformer(
            {},
            {resolveLocalStackDns: false},
        ).processFromObject(config);
        return validationSuccess();
    } catch (error) {
        return validationFromError(error);
    }
}

export function buildEditStateFromObject(
    config: unknown,
    validationOverride?: EditStateV1["validation"],
): EditStateV1 {
    return buildCoreEditStateFromObject(
        config,
        validationOverride ?? validationForConfig(config),
        coreOptions(),
    );
}

export async function buildEditStateFromObjectForSubmit(config: unknown): Promise<EditStateV1> {
    return buildEditStateFromObjectWithValidation(
        config,
        await submitValidationForConfig(config),
        coreOptions(),
    );
}

export function applyEditOperation(config: unknown, operation: EditOperation): unknown {
    return applyCoreEditOperation(config, operation, coreOptions());
}

export function applyEditOperationToObject(
    config: unknown,
    operation: EditOperation,
): EditApplyResultV1 {
    const nextConfig = applyEditOperation(config, operation);
    return {
        formatVersion: 1,
        yaml: stringify(nextConfig),
        editState: buildEditStateFromObject(nextConfig),
    };
}

async function applyEditOperationToObjectForSubmit(
    config: unknown,
    operation: EditOperation,
): Promise<EditApplyResultV1> {
    const nextConfig = applyEditOperation(config, operation);
    return {
        formatVersion: 1,
        yaml: stringify(nextConfig),
        editState: await buildEditStateFromObjectForSubmit(nextConfig),
    };
}

async function withConsoleDiagnosticsOnStderr<T>(callback: () => T | Promise<T>): Promise<T> {
    const originalLog = console.log;
    const originalInfo = console.info;
    const originalWarn = console.warn;
    const redirect = (...args: unknown[]) => console.error(...args);
    console.log = redirect;
    console.info = redirect;
    console.warn = redirect;
    try {
        return await callback();
    } finally {
        console.log = originalLog;
        console.info = originalInfo;
        console.warn = originalWarn;
    }
}

function usage(): never {
    console.error("Usage: editConfig state --pending-config <file|->");
    console.error("       editConfig apply --pending-config <file|-> --operation <json-file|->");
    process.exit(2);
}

export async function main(): Promise<void> {
    const args = process.argv.slice(2);
    const subcommand = args.shift();
    if (subcommand !== "state" && subcommand !== "apply") {
        usage();
    }
    const pendingConfigFlag = args.shift();
    const pendingConfigPath = args.shift();
    if (pendingConfigFlag !== "--pending-config" || !pendingConfigPath) {
        usage();
    }
    const yamlContents = await readYamlText(pendingConfigPath);
    let config: unknown;
    try {
        config = yamlContents.trim() === "" ? {} : parse(yamlContents);
    } catch (error) {
        if (subcommand === "state" && args.length === 0) {
            process.stdout.write(JSON.stringify(rawRepairState(
                syntaxValidation(error),
                "The saved YAML must be repaired before the form editor can open it.",
            ), null, 2));
            return;
        }
        throw error;
    }
    if (subcommand === "state") {
        if (args.length > 0) {
            usage();
        }
        const editState = await withConsoleDiagnosticsOnStderr(
            () => buildEditStateFromObjectForSubmit(config),
        );
        process.stdout.write(JSON.stringify(editState, null, 2));
        return;
    }
    const operationFlag = args.shift();
    const operationPath = args.shift();
    if (operationFlag !== "--operation" || !operationPath || args.length > 0) {
        usage();
    }
    const operation = await parseYaml(operationPath) as EditOperation;
    const result = await withConsoleDiagnosticsOnStderr(
        () => applyEditOperationToObjectForSubmit(config, operation),
    );
    process.stdout.write(JSON.stringify(result, null, 2));
}

if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch(error => {
        console.error(error);
        process.exit(1);
    });
}
