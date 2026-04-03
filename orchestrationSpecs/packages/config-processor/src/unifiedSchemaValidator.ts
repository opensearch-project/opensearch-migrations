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

    throw new InputValidationError(
        (validate.errors ?? []).map(error =>
            new InputValidationElement(
                pointerToPath((error as any).instancePath ?? (error as any).dataPath ?? ""),
                formatAjvMessage(error)
            )
        )
    );
}
