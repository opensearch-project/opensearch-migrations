import {AllowLiteralOrExpression, BaseExpression, expr} from "./models/expression";
import {z} from "zod";
import {ConfigMapKeySelector, defineParam, defineRequiredParam} from "./models/parameterSchemas";
import {PlainObject} from "./models/plainObject";
import {TypeToken} from "./models/sharedTypes";
import {ExpressionOrConfigMapValue} from "./models/workflowTypes";

export type TypescriptError<Message extends string> = {
    readonly __error: Message;
    readonly __never: never;
};

// Type helper that remaps keys according to the mapping table
export type RemapRecordKeys<T extends Record<string, any>, M extends Partial<Record<keyof T, string>>> = {
    [K in keyof T as K extends keyof M
        ? M[K] extends string
            ? M[K]
            : K
        : K]: T[K]
}

/**
 * Remaps some fields of a record according to a mapping table
 * @param source - The source record to remap
 * @param remapTable - Object mapping old key names to new key names
 * @returns New record with remapped keys
 */
export function remapRecordNames<
    S extends Record<string, any>,
    M extends Partial<Record<keyof S, string>>
>(
    source: S,
    remapTable: M
): RemapRecordKeys<S, M> {
    const result: any = {};

    Object.entries(source).forEach(([key, value]) => {
        const newKey = remapTable[key] ?? key;
        result[newKey] = value;
    });

    return result;
}

export function toEnvVarName(str: string, prefix: string, suffix: string): string {
    // Insert "_" before every uppercase, then strip a possible leading "_", then UPPERCASE
    const snake = str.replace(/([A-Z])/g, "_$1").replace(/^_/, "").toUpperCase();
    return `${prefix}${snake}${suffix}`;
}


type SnakeAll<S extends string, Start extends boolean = true> =
    S extends `${infer Ch}${infer Rest}`
        ? Ch extends Lowercase<Ch>
            ? `${Lowercase<Ch>}${SnakeAll<Rest, false>}`
            : Start extends true
                ? `${Uppercase<Ch>}${SnakeAll<Rest, false>}`
                : `_${Uppercase<Ch>}${SnakeAll<Rest, false>}`
        : '';

type ToEnvKey<K extends string, P extends string, S extends string> =
    `${P}${SnakeAll<K>}${S}`;

export function inputsToPlainEnvVars<T extends Record<string, ExpressionOrConfigMapValue<PlainObject>>>(
    inputs: T,
    prefix: string,
    suffix: string
): { [K in keyof T as (string & K)]: T[K] } {
    const result: Record<string, ExpressionOrConfigMapValue<PlainObject>> = {};
    Object.entries(inputs).forEach(([key, value]) => {
        result[toEnvVarName(key, prefix, suffix)] = value;
    });
    return result as any;
}

export function inputsToEnvVars<
    T extends Record<string, ExpressionOrConfigMapValue<PlainObject>>,
    P extends string,
    S extends string
>(
    inputs: T,
    envVarDecorator: { prefix: P; suffix: S } // keep P/S as literals with `as const`
): {
    [K in keyof T as K extends string ? ToEnvKey<K, P, S> : never]: T[K]
} {
    // runtime stays the same
    return inputsToPlainEnvVars(
        inputs,
        (envVarDecorator as any).prefix,
        (envVarDecorator as any).suffix
    ) as any;
}

export function inputsToEnvVarsList<T extends Record<string, ExpressionOrConfigMapValue<PlainObject>>>(
    inputs: T,
    envVarDecorator: {prefix: string, suffix: string}
) {
    return Object.entries(inputsToEnvVars(inputs, envVarDecorator)).map(([name, value]) => ({name, value}));
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

type TransformedParams<T extends z.ZodRawShape> = {
    [K in keyof T]:
    ExtractInnerType<T[K]> extends infer U
        ? ParamReturnFor<U, HasDefault<T[K]> extends true ? true : false>
        : never;
};

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
                ...(description && {description}),
                expression: expr.literal(defaultValue)
            });
        } else {
            // Field is required (no default)
            result[key] = defineRequiredParam({
                ...(description && {description})
            });
        }
    }

    return result as TransformedParams<T>;
}