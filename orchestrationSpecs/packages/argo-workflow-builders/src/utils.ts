import {AllowLiteralOrExpression, BaseExpression, expr} from "./models/expression";
import {z} from "zod";
import {defineParam, defineRequiredParam} from "./models/parameterSchemas";
import {PlainObject} from "./models/plainObject";

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

export function toEnvVarName(str: string, prefix:string, suffix:string): string {
    if (prefix === undefined || suffix === undefined) {
        console.log("checkme");
    }
    return prefix + str
        .replace(/([a-z])([A-Z])/g, '$1_$2')
        .replace(/([A-Z])([A-Z][a-z])/g, '$1_$2')
        .toUpperCase() + suffix;
}

export function inputsToPlainEnvVars<T extends Record<string, AllowLiteralOrExpression<PlainObject>>>(
    inputs: T,
    prefix: string,
    suffix: string
): { [K in keyof T as (string & K)]: T[K] } {
    const result: Record<string, AllowLiteralOrExpression<PlainObject>> = {};
    Object.entries(inputs).forEach(([key, value]) => {
        result[toEnvVarName(key, prefix, suffix)] = value;
    });
    return result as any;
}

export function inputsToJCommanderEnvVars<T extends Record<string, AllowLiteralOrExpression<PlainObject>>>(
    inputs: T
): { "JCOMMANDER_OPTS": BaseExpression<string> } {
    const result: BaseExpression<string>[] = [];
    Object.entries(inputs).forEach(([key, value]) => {
        result.push(expr.ternary(expr.isEmpty(value), expr.literal(""),
            expr.concat(expr.literal(" --"+key+" "), expr.cast(value).to<string>())));
    });
    return { "JCOMMANDER_OPTS":  expr.concat(...result) };
}

export function inputsToEnvVars<
    T extends Record<string, AllowLiteralOrExpression<PlainObject>>,
    M extends "JCOMMANDER" | {prefix: string, suffix: string}
>(
    inputs: T,
    mode: M
): M extends "JCOMMANDER"
    ? { "JCOMMANDER_OPTS": BaseExpression<string> }
    : { [K in keyof T as (string & K)]: T[K] } {
    return (mode === "JCOMMANDER"
        ? inputsToJCommanderEnvVars(inputs)
        : inputsToPlainEnvVars(inputs, (mode as any).prefix, (mode as any).suffix)) as any;
}

export function inputsToEnvVarsList<T extends Record<string, AllowLiteralOrExpression<PlainObject>>>(
    inputs: T,
    mode: "JCOMMANDER" | {prefix: string, suffix: string}
) {
    return Object.entries(inputsToEnvVars(inputs, mode)).map(([name, value]) => ({name, value}));
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