import Ajv, {ErrorObject} from "ajv";
import {
    assertUnifiedSchemaIsUsable,
    classifyKafkaBrokerConfigKey,
    isWorkflowManagedKafkaBrokerConfigPath,
    loadUnifiedSchema,
} from "@opensearch-migrations/schemas";
import {
    InputValidationElement,
    InputValidationError,
    stripComments,
} from "./streamSchemaTransformer";

function instancePathOf(error: ErrorObject) {
    return (error as any).instancePath ?? (error as any).dataPath ?? "";
}

function isDescendantPath(path: string, parentPath: string) {
    return path !== parentPath && path.startsWith(parentPath + "/");
}

function isKafkaClusterUnionPath(path: string) {
    return /^\/kafkaClusterConfiguration\/[^/]+$/.test(path);
}

function isKafkaClusterUnionRequiredError(error: ErrorObject) {
    if (error.keyword !== "required") {
        return false;
    }
    const missingProperty = (error.params as any).missingProperty;
    return isKafkaClusterUnionPath(instancePathOf(error)) &&
        (missingProperty === "existing" || missingProperty === "autoCreate");
}

function isKafkaClusterUnionAnyOfError(error: ErrorObject) {
    return error.keyword === "anyOf" && isKafkaClusterUnionPath(instancePathOf(error));
}

function isUnionNoiseError(error: ErrorObject) {
    return isKafkaClusterUnionRequiredError(error) || isKafkaClusterUnionAnyOfError(error);
}

function hasMoreSpecificDescendantError(errors: ErrorObject[], parentPath: string) {
    return errors.some(error => {
        const path = instancePathOf(error);
        return isDescendantPath(path, parentPath) && !isUnionNoiseError(error);
    });
}

function hasKafkaClusterUnionRequiredPair(errors: ErrorObject[], parentPath: string) {
    const missingProperties = new Set(
        errors
            .filter(error => instancePathOf(error) === parentPath && isKafkaClusterUnionRequiredError(error))
            .map(error => (error.params as any).missingProperty)
    );
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
        .map(segment => (/^\d+$/.test(segment) ? Number(segment) : segment));
}

function formatAjvMessage(error: ErrorObject) {
    const keyword = error.keyword;
    if (keyword === "additionalProperties") {
        const extra = (error.params as any).additionalProperty;
        const instancePath = (error as any).instancePath ?? (error as any).dataPath ?? "";
        if (typeof extra === "string" && isWorkflowManagedKafkaBrokerConfigPath(instancePath)) {
            return classifyKafkaBrokerConfigKey(extra) === "disallowed-by-strimzi"
                ? `Kafka broker config '${extra}' is valid Kafka syntax but is managed by Strimzi and cannot be set in workflow-managed clusters`
                : `Kafka broker config '${extra}' is not part of the pinned Kafka 4.2.0 broker config catalog for workflow-managed clusters`;
        }
        return `Unrecognized key '${extra}'`;
    }
    return error.message ?? `Schema validation failed (${keyword})`;
}

export function buildValidationElements(errors: ErrorObject[]) {
    const elements: InputValidationElement[] = [];
    const synthesizedKafkaClusterUnionPaths = new Set<string>();

    for (const error of errors) {
        const path = instancePathOf(error);

        if (isKafkaClusterUnionAnyOfError(error) && hasKafkaClusterUnionRequiredPair(errors, path)) {
            if (hasMoreSpecificDescendantError(errors, path)) {
                continue;
            }
            if (synthesizedKafkaClusterUnionPaths.has(path)) {
                continue;
            }

            synthesizedKafkaClusterUnionPaths.add(path);
            elements.push(new InputValidationElement(
                pointerToPath(path),
                "Kafka cluster configuration must define exactly one of 'existing' or 'autoCreate'"
            ));
            continue;
        }

        if (isKafkaClusterUnionRequiredError(error)) {
            if (hasMoreSpecificDescendantError(errors, path) || hasKafkaClusterUnionRequiredPair(errors, path)) {
                continue;
            }
        }

        if (isKafkaClusterUnionAnyOfError(error) && hasMoreSpecificDescendantError(errors, path)) {
            continue;
        }

        elements.push(new InputValidationElement(
            pointerToPath(path),
            formatAjvMessage(error)
        ));
    }

    return elements;
}

export function validateInputAgainstUnifiedSchema(data: unknown): void {
    const strippedData = stripComments(data);
    const loaded = loadUnifiedSchema();
    assertUnifiedSchemaIsUsable(loaded);

    const ajv = new Ajv(({
        allErrors: true,
        strict: false,
    } as unknown) as ConstructorParameters<typeof Ajv>[0]);
    const validate = ajv.compile(loaded.schema);
    if (validate(strippedData)) {
        return;
    }

    throw new InputValidationError(buildValidationElements(validate.errors ?? []));
}
