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

function objectShape(schema: z.ZodTypeAny): Record<string, z.ZodTypeAny> | undefined {
    const unwrapped = unwrapTransparentSchema(schema);
    const typeName = unwrapped.constructor.name;
    const typeDefinition = schemaType(unwrapped);
    if (typeName !== "ZodObject" && typeDefinition !== "object") {
        return undefined;
    }
    return (unwrapped as z.ZodObject<any>).shape as Record<string, z.ZodTypeAny>;
}

function selectObjectUnionOption(
    data: Record<string, unknown>,
    options: readonly z.ZodTypeAny[],
): z.ZodTypeAny | undefined {
    const dataKeys = Object.keys(data);
    const ranked = options.flatMap((option, index) => {
        const shape = objectShape(option);
        if (!shape) {
            return [];
        }
        const allowedKeys = new Set(Object.keys(shape));
        const unrecognizedKeys = dataKeys.filter(key => !allowedKeys.has(key)).length;
        return [{
            option,
            index,
            unrecognizedKeys,
            recognizedKeys: dataKeys.length - unrecognizedKeys,
        }];
    }).sort((left, right) =>
        left.unrecognizedKeys - right.unrecognizedKeys
        || right.recognizedKeys - left.recognizedKeys
        || left.index - right.index);

    const best = ranked[0];
    const runnerUp = ranked[1];
    if (
        !best
        || (
            runnerUp
            && best.unrecognizedKeys === runnerUp.unrecognizedKeys
            && best.recognizedKeys === runnerUp.recognizedKeys
        )
    ) {
        return undefined;
    }
    return best.option;
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
        const options = (schema as z.ZodUnion<any>).options as readonly z.ZodTypeAny[];
        if (typeof data === "object" && !Array.isArray(data)) {
            const selectedOption = selectObjectUnionOption(
                data as Record<string, unknown>,
                options,
            );
            if (selectedOption) {
                validateNoExtraKeys(data, selectedOption, path);
                return;
            }
        }
        let extraKeyError: InputValidationError | undefined;
        let parseError: Error | undefined;
        for (const option of options) {
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
