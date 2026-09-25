import {normalizeLegacySnapshotMigrationSlices, unwrapSchema} from "@opensearch-migrations/schemas/browser";
import {z} from "zod";
import {InputValidationElement, InputValidationError} from "./inputValidation";

function schemaDef(schema: z.ZodTypeAny): Record<string, any> {
    return (schema as any)._def ?? {};
}

function schemaType(schema: z.ZodTypeAny): string {
    return String(schemaDef(schema).type ?? schema.constructor.name);
}

function unwrapTransparentSchema(schema: z.ZodTypeAny): z.ZodTypeAny {
    let current = unwrapSchema(schema);
    while (true) {
        const currentType = schemaType(current);
        const definition = schemaDef(current);
        if (currentType === "catch" || currentType === "readonly") {
            current = typeof (current as any).unwrap === "function"
                ? (current as any).unwrap()
                : definition.innerType;
            current = unwrapSchema(current);
            continue;
        }
        if (currentType === "lazy" && typeof definition.getter === "function") {
            current = unwrapSchema(definition.getter());
            continue;
        }
        return current;
    }
}

function extraKeysError(path: string[], extraKeys: string[]): InputValidationError {
    return new InputValidationError(extraKeys.map(key =>
        new InputValidationElement([...path, key], `Unrecognized key '${key}'`)
    ));
}

function kafkaClusterUnionError(path: string[]): InputValidationError {
    return new InputValidationError([
        new InputValidationElement(
            path,
            "Kafka cluster configuration must define exactly one of 'existing' or 'autoCreate'",
        ),
    ]);
}

function isKafkaClusterConfigPath(path: string[]): boolean {
    return path.length === 3 && path[0] === "traffic" && path[1] === "kafkaClusters";
}

function validateNoExtraKeys(data: unknown, inputSchema: z.ZodTypeAny, path: string[] = []): void {
    const schema = unwrapTransparentSchema(inputSchema);
    const typeName = schema.constructor.name;
    const typeDefinition = schemaType(schema);
    if (typeName === "ZodObject" || typeDefinition === "object") {
        if (typeof data !== "object" || data === null || Array.isArray(data)) {
            return;
        }
        const shape = (schema as z.ZodObject<any>).shape as Record<string, z.ZodTypeAny>;
        const allowedKeys = Object.keys(shape);
        const extraKeys = Object.keys(data).filter(key => !allowedKeys.includes(key));
        if (extraKeys.length > 0) {
            throw extraKeysError(path, extraKeys);
        }
        for (const key of allowedKeys) {
            if (Object.hasOwn(data, key)) {
                validateNoExtraKeys((data as Record<string, unknown>)[key], shape[key], [...path, key]);
            }
        }
        return;
    }
    if ((typeName === "ZodArray" || typeDefinition === "array") && Array.isArray(data)) {
        const element = (schema as z.ZodArray<any>).element;
        data.forEach((item, index) => validateNoExtraKeys(item, element, [...path, String(index)]));
        return;
    }
    if ((typeName === "ZodUnion" || typeDefinition === "union") && data !== null && data !== undefined) {
        if (
            isKafkaClusterConfigPath(path)
            && typeof data === "object"
            && "existing" in data
            && "autoCreate" in data
        ) {
            throw kafkaClusterUnionError(path);
        }
        let extraKeyError: InputValidationError | undefined;
        let parseError: Error | undefined;
        for (const option of (schema as z.ZodUnion<any>).options) {
            try {
                option.parse(data);
                validateNoExtraKeys(data, option, path);
                return;
            } catch (error) {
                if (error instanceof InputValidationError) {
                    extraKeyError = error;
                } else if (error instanceof Error) {
                    parseError = error;
                }
            }
        }
        throw extraKeyError ?? parseError ?? new Error("No valid union option found");
    }
    if ((typeName === "ZodRecord" || typeDefinition === "record")
        && typeof data === "object" && data !== null && !Array.isArray(data)) {
        const definition = schemaDef(schema);
        const valueType = (schema as any).valueType ?? definition.valueType;
        Object.entries(data).forEach(([key, value]) =>
            validateNoExtraKeys(value, valueType, [...path, key])
        );
    }
}

export function validateNoExtraConfigKeys(data: unknown, schema: z.ZodTypeAny): void {
    validateNoExtraKeys(normalizeLegacySnapshotMigrationSlices(data), schema);
}
