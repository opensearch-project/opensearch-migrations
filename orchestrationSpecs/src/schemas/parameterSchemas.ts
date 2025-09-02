/**
 * DESIGN PRINCIPLE: ERGONOMIC AND INTUITIVE API
 * 
 * This schema system is designed to provide an intuitive, ergonomic developer experience.
 * Users should NEVER need to use explicit type casts (as any, as string, etc.) or 
 * cumbersome workarounds to make the type system work. If the API requires such casts,
 * the type system implementation needs to be improved, not the caller code.
 * 
 * The goal is to make template building feel natural and safe, with proper type inference
 * working automatically without forcing developers to manually specify types.
 */

// parameterSchemas.ts
// ─────────────────────────────────────────────────────────────────────────────
// Zero-runtime, strongly-typed parameter model for building Argo workflows.
// No Zod, no runtime validation. The goal is to model everything *statically*
// and simply serialize the in-memory model to YAML at runtime.
// ─────────────────────────────────────────────────────────────────────────────

import { PlainObject } from "@/schemas/plainObject";
import { inputParam, workflowParam } from "@/schemas/expression";

// A unique symbol so the field can’t be faked by random objects
export declare const __param_T: unique symbol;
export declare const __out_T: unique symbol;
// Zero-runtime “type witness” object that carries a generic T.
export declare const __type_token__: unique symbol;

export type TypeToken<T> = {
    readonly [__type_token__]?: (x: T) => T; // phantom to make T invariant
};
export const typeToken = <T>(): TypeToken<T> => ({}) as TypeToken<T>;

/** A param definition for inputs.
 *
 * NOTE: This is *type-only*. There is intentionally no runtime validation.
 * - When `REQ = false`, a `defaultValue` must exist and the caller key is optional.
 * - When `REQ = true`, there is no default and the caller key is required.
 */
export type InputParamDef<T extends PlainObject, REQ extends boolean> = {
    /** Phantom to preserve T and make it invariant. Never read or written. */
    readonly [__param_T]?: (x: T) => T;
    /** Optional doc string */
    description?: string;
} & (REQ extends false
    ? { _hasDefault: true; defaultValue: T } // optional at callsite
    : {});                                   // required at callsite

export function defineParam<T extends PlainObject>(opts: {
    defaultValue: T;
    description?: string;
}): InputParamDef<T, false> {
    return {
        // phantom is omitted at runtime; TS still sees it
        _hasDefault: true,
        defaultValue: opts.defaultValue,
        description: opts.description,
    };
}

export function defineRequiredParam<T extends PlainObject>(opts?: {
    description?: string;
}): InputParamDef<T, true> {
    return {
        description: opts?.description,
    };
}

/** Output parameter definition.
 *
 * NOTE: Type-only carrier of the *intended* output shape and extraction locus.
 * No runtime checks; extraction is performed by Argo, not by our code.
 */
export type ConfigMapKeySelector = {
    name: string;
    key: string;
    optional?: boolean;
};

export type SuppliedValueFrom = { [key: string]: any };

/** Output param (same issue & same fix) */
export type OutputParamDef<T extends PlainObject> = {
    /** Phantom to preserve T and make it invariant. Never read or written. */
    readonly [__out_T]?: (x: T) => T;
    description?: string;
} & (
    | { fromWhere: "path"; path: string }
    | { fromWhere: "expression"; expression: string }
    | { fromWhere: "parameter"; parameter: string }
    | { fromWhere: "jsonPath"; jsonPath: string }
    | { fromWhere: "jqFilter"; jqFilter: string }
    | { fromWhere: "event"; event: string }
    | { fromWhere: "configMapKeyRef"; configMapKeyRef: ConfigMapKeySelector }
    | { fromWhere: "supplied"; supplied: SuppliedValueFrom }
    | { fromWhere: "default"; default: string }
    );

/** Canonical maps for inputs/outputs (values are type-only descriptors). */
export type InputParametersRecord = Record<string, InputParamDef<any, boolean>>;
export type OutputParametersRecord = Record<string, OutputParamDef<any>>;

/** Type-only: infer the value type from an InputParamDef. */
export type InferParamType<P> = P extends InputParamDef<infer T, any> ? T : never;

/** Type-only: compute the caller object type.
 * - Keys with defaults become optional (`T | undefined`)
 * - Required keys are mandatory (`T`)
 */
export type CallerParams<T extends InputParametersRecord> =
// optional keys (those with defaults)
    { [K in keyof T as T[K] extends { _hasDefault: true } ? K : never]?: InferParamType<T[K]> } &
    // required keys (no defaults)
    { [K in keyof T as T[K] extends { _hasDefault: true } ? never : K]-?: InferParamType<T[K]> };

/** Convenience: turn template input param defs into expression leaves.
 * NOTE: These are *runtime* constructors of expression nodes, but remain
 * shape-preserving and side-effect free. Circular import with expression.ts
 * is intentional and safe as long as you don't execute these during module
 * initialization in a way that depends on expression.ts having finished
 * evaluating. Typical usage is inside builders/factories.
 */
export function templateInputParametersAsExpressions<WP extends InputParametersRecord>(params: WP): {
    [K in keyof WP]: WP[K] extends InputParamDef<infer T, any>
        ? [T] extends [PlainObject]
            ? [T] extends [null | undefined]
                ? never
                : ReturnType<typeof inputParam<T>>
            : never
        : never
} {
    const out: any = {};
    for (const key of Object.keys(params)) {
        out[key] = inputParam(key, params[key]);
    }
    return out;
}

export function workflowParametersAsExpressions<WP extends InputParametersRecord>(params: WP): {
    [K in keyof WP]: WP[K] extends InputParamDef<infer T, any>
        ? [T] extends [PlainObject]
            ? [T] extends [null | undefined]
                ? never
                : ReturnType<typeof workflowParam<T>>
            : never
        : never
} {
    const out: any = {};
    for (const key of Object.keys(params)) {
        out[key] = workflowParam(key, params[key]);
    }
    return out;
}
