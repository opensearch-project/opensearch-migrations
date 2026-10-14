import Ajv, {ErrorObject} from "ajv";
import {
    classifyKafkaBrokerConfigKey,
    isWorkflowManagedKafkaBrokerConfigPath,
} from "@opensearch-migrations/schemas/browser";
import {
    InputValidationElement,
    InputValidationError,
    stripComments,
} from "./inputValidation";
import type {JsonSchema} from "./schemaEditModel";

function instancePathOf(error: ErrorObject): string {
    return (error as any).instancePath
        ?? (error as any).dataPath
        ?? "";
}

function isDescendantPath(path: string, parentPath: string): boolean {
    return path !== parentPath && path.startsWith(parentPath + "/");
}

function isKafkaClusterUnionPath(path: string): boolean {
    return /^\/traffic\/kafkaClusters\/[^/]+$/.test(path);
}

function isKafkaClusterUnionRequiredError(error: ErrorObject): boolean {
    if (error.keyword !== "required") {
        return false;
    }
    const missingProperty = (error.params as {missingProperty?: string}).missingProperty;
    return isKafkaClusterUnionPath(instancePathOf(error))
        && (missingProperty === "existing" || missingProperty === "autoCreate");
}

function isKafkaClusterUnionAnyOfError(error: ErrorObject): boolean {
    return error.keyword === "anyOf" && isKafkaClusterUnionPath(instancePathOf(error));
}

function isUnionNoiseError(error: ErrorObject): boolean {
    return isKafkaClusterUnionRequiredError(error) || isKafkaClusterUnionAnyOfError(error);
}

function hasMoreSpecificDescendantError(errors: ErrorObject[], parentPath: string): boolean {
    return errors.some(error =>
        isDescendantPath(instancePathOf(error), parentPath) && !isUnionNoiseError(error)
    );
}

function hasKafkaClusterUnionRequiredPair(errors: ErrorObject[], parentPath: string): boolean {
    const missingProperties = new Set(errors
        .filter(error => instancePathOf(error) === parentPath && isKafkaClusterUnionRequiredError(error))
        .map(error => (error.params as {missingProperty?: string}).missingProperty));
    return missingProperties.has("existing") && missingProperties.has("autoCreate");
}

function pointerToPath(instancePath: string): PropertyKey[] {
    if (!instancePath) {
        return [];
    }
    return instancePath
        .split("/")
        .slice(1)
        .map(segment => segment.replace(/~1/g, "/").replace(/~0/g, "~"))
        .map(segment => /^\d+$/.test(segment) ? Number(segment) : segment);
}

function formatAjvMessage(error: ErrorObject): string {
    if (error.keyword === "additionalProperties") {
        const extra = (error.params as {additionalProperty?: string}).additionalProperty;
        const instancePath = instancePathOf(error);
        if (extra && isWorkflowManagedKafkaBrokerConfigPath(instancePath)) {
            return classifyKafkaBrokerConfigKey(extra) === "disallowed-by-strimzi"
                ? `Kafka broker config '${extra}' is valid Kafka syntax but is managed by Strimzi and cannot be set in workflow-managed clusters`
                : `Kafka broker config '${extra}' is not part of the pinned Kafka 4.2.0 broker config catalog for workflow-managed clusters`;
        }
        return `Unrecognized key '${extra}'`;
    }
    return error.message ?? `Schema validation failed (${error.keyword})`;
}

export function buildValidationElements(errors: ErrorObject[]): InputValidationElement[] {
    const elements: InputValidationElement[] = [];
    const synthesizedKafkaClusterUnionPaths = new Set<string>();
    for (const error of errors) {
        const path = instancePathOf(error);
        if (isKafkaClusterUnionAnyOfError(error) && hasKafkaClusterUnionRequiredPair(errors, path)) {
            if (hasMoreSpecificDescendantError(errors, path)
                || synthesizedKafkaClusterUnionPaths.has(path)) {
                continue;
            }
            synthesizedKafkaClusterUnionPaths.add(path);
            elements.push(new InputValidationElement(
                pointerToPath(path),
                "Kafka cluster configuration must define exactly one of 'existing' or 'autoCreate'",
            ));
            continue;
        }
        if (isKafkaClusterUnionRequiredError(error)
            && (hasMoreSpecificDescendantError(errors, path)
                || hasKafkaClusterUnionRequiredPair(errors, path))) {
            continue;
        }
        if (isKafkaClusterUnionAnyOfError(error) && hasMoreSpecificDescendantError(errors, path)) {
            continue;
        }
        elements.push(new InputValidationElement(pointerToPath(path), formatAjvMessage(error)));
    }
    return elements;
}

export function validateInputAgainstUnifiedSchema(
    data: unknown,
    unifiedSchema: JsonSchema,
): void {
    const ajv = new Ajv(({
        allErrors: true,
        strict: false,
    } as unknown) as ConstructorParameters<typeof Ajv>[0]);
    const validate = ajv.compile(unifiedSchema);
    if (validate(stripComments(data))) {
        return;
    }
    throw new InputValidationError(buildValidationElements(validate.errors ?? []));
}
