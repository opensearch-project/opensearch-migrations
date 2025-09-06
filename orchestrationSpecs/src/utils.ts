import {AllowLiteralOrExpression} from "@/schemas/workflowTypes";

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

export function inputsToEnvVars<T extends Record<string, AllowLiteralOrExpression<string>>>(
    inputs: T
): { [K in keyof T as (string & K)]: T[K] } {
    const result: Record<string, AllowLiteralOrExpression<string>> = {};
    Object.entries(inputs).forEach(([key, value]) => {
        result[toEnvVarName(key)] = value;
    });
    return result as any;
}