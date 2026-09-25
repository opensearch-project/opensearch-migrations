import {z} from "zod";

export class InputValidationElement {
    constructor(
        public readonly path: PropertyKey[],
        public readonly message: string,
    ) {}
}

export class InputValidationError extends Error {
    constructor(
        public readonly errors: InputValidationElement[],
    ) {
        super();
        this.name = "InputValidationError";
    }

    get message(): string {
        return `Found ${this.errors.length} errors: ${formatInputValidationError(this, {singleLine: true})}`;
    }
}

export function formatInputValidationError(
    error: InputValidationError,
    options?: {singleLine?: boolean},
): string {
    const singleLine = options?.singleLine ?? false;
    return error.errors
        .map(item => [item.message, item.path.map(part => part.toString()).join(".")])
        .map(([message, path]) => singleLine
            ? `${message} at: ${path}`
            : `${message}... at:\n  ${path}`)
        .join(singleLine ? "; " : "\n");
}

export function stripComments<T>(obj: T): T {
    if (obj === null || typeof obj !== "object") {
        return obj;
    }
    if (Array.isArray(obj)) {
        return obj.map(stripComments) as T;
    }
    const result: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(obj)) {
        if (key.startsWith("//") || key.startsWith("#")) {
            continue;
        }
        result[key] = stripComments(value);
    }
    return result as T;
}

export function parseWithValidation<TSchema extends z.ZodType>(
    schema: TSchema,
    data: unknown,
): z.infer<TSchema> {
    const result = schema.safeParse(stripComments(data));
    if (!result.success) {
        throw new InputValidationError(result.error.issues.map(issue =>
            new InputValidationElement(issue.path, issue.message)
        ));
    }
    return result.data;
}
