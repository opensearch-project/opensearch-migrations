import Ajv, {ErrorObject} from "ajv";
import {
    assertUnifiedSchemaIsUsable,
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
        return `Unrecognized key '${extra}'`;
    }
    return error.message ?? `Schema validation failed (${keyword})`;
}

export function validateInputAgainstUnifiedSchema(data: unknown): void {
    const strippedData = stripComments(data);
    const loaded = loadUnifiedSchema();
    assertUnifiedSchemaIsUsable(loaded);

    const ajv = new Ajv({
        allErrors: true,
        strict: false,
    });
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
