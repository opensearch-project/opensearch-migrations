import {AllowLiteralOrExpression} from "@/schemas/expression";
import {z} from "zod";
import {defineParam, defineRequiredParam} from "@/schemas/parameterSchemas";
import {PlainObject} from "@/schemas/plainObject";

export type TypescriptError<Message extends string> = {
    readonly __error: Message;
    readonly __never: never;
};

export function toEnvVarName(str: string): string {
    return str
        .replace(/([a-z])([A-Z])/g, '$1_$2')
        .replace(/([A-Z])([A-Z][a-z])/g, '$1_$2')
        .toUpperCase();
}

export function inputsToEnvVars<T extends Record<string, AllowLiteralOrExpression<PlainObject>>>(
    inputs: T
): { [K in keyof T as (string & K)]: T[K] } {
    const result: Record<string, AllowLiteralOrExpression<PlainObject>> = {};
    Object.entries(inputs).forEach(([key, value]) => {
        result[toEnvVarName(key)] = value;
    });
    return result as any;
}

export function inputsToEnvVarsList<T extends Record<string, AllowLiteralOrExpression<PlainObject>>>(
    inputs: T
) {
    return Object.entries(inputsToEnvVars(inputs)).map(([name, value]) => ({ name, value }));
}

// Helper function to create literal values
function literal<T>(value: T): T {
    return value;
}

// Helper to check if a Zod type has a default value
function hasDefault(zodType: any): boolean {
    return zodType && zodType._def && 'defaultValue' in zodType._def;
}

// Helper to extract the default value from a Zod type
function getDefaultValue(zodType: any): any {
    if (!hasDefault(zodType)) return undefined;

    const defaultValue = zodType._def.defaultValue;

    // If it's a function, call it to get the actual default
    if (typeof defaultValue === 'function') {
        return defaultValue();
    }

    return defaultValue;
}

// Helper to extract description from Zod schema
function getDescription(zodType: any): string | undefined {
    return zodType?._def?.description;
}

// Type to detect if a Zod type has a default at the type level
type HasDefault<T> = T extends z.ZodDefault<any> ? true : false;

// Type to extract the inner type from wrapped Zod types
type ExtractInnerType<T> = T extends z.ZodDefault<infer U>
    ? z.infer<U>
    : T extends z.ZodOptional<infer U>
        ? z.infer<U>
        : z.infer<T>;

type ParamReturnFor<U, HasDef extends boolean> =
    U extends PlainObject
        ? HasDef extends true
            ? ReturnType<typeof defineParam<U>>
            : ReturnType<typeof defineRequiredParam<U>>
        : TypescriptError<"Zod field does not infer to PlainObject (required by defineParam).">;

// Strongly typed return type for the transformer
type TransformedParams<T extends z.ZodRawShape> = {
    [K in keyof T]:
    ExtractInnerType<T[K]> extends infer U
        ? ParamReturnFor<U, HasDefault<T[K]> extends true ? true : false>
        : never;
};

// Main transformer function with strong typing
export function transformZodObjectToParams<T extends z.ZodRawShape>(
    zodObject: z.ZodObject<T>
): TransformedParams<T> {
    const shape = zodObject.shape;
    const result: any = {};

    for (const [key, zodType] of Object.entries(shape)) {
        const description = getDescription(zodType);

        if (hasDefault(zodType)) {
            // Field has a default value
            const defaultValue = getDefaultValue(zodType);
            result[key] = defineParam({
                ...(description && { description }),
                expression: literal(defaultValue)
            });
        } else {
            // Field is required (no default)
            result[key] = defineRequiredParam({
                ...(description && { description })
            });
        }
    }

    return result as TransformedParams<T>;
}