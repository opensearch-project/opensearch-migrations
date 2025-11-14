import {AllowLiteralOrExpression, BaseExpression, expr} from "./models/expression";
import {z} from "zod";
import {ConfigMapKeySelector, defineParam, defineRequiredParam, InputParamDef} from "./models/parameterSchemas";
import {PlainObject} from "./models/plainObject";
import {TypeToken} from "./models/sharedTypes";
import {ExpressionOrConfigMapValue} from "./models/workflowTypes";

export type TypescriptError<Message extends string> = {
    readonly __error: Message;
    readonly __never: never;
};

// Type helper that makes specific keys required in a type
export type SetRequired<T, K extends keyof T> =
    Omit<T, K> & Required<Pick<T, K>>;

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
