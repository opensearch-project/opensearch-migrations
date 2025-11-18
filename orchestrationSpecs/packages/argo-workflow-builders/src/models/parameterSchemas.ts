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

import {AggregateType, DeepWiden, PlainObject} from "./plainObject";
import {
    AllowLiteralOrExpression,
    BaseExpression,
    FromParameterExpression,
    StepOutputSource,
    TaskOutputSource
} from "./expression";
import {typeToken, TypeToken} from "./sharedTypes";

type DefaultSpec<T extends PlainObject> =
    | {
        expression: AllowLiteralOrExpression<T>;
        from?: ConfigMapKeySelector;
        type?: TypeToken<T>;
}
    | {
        expression?: AllowLiteralOrExpression<T>;
        from: ConfigMapKeySelector;
        type: TypeToken<T>;
};

export type FieldIsRequired = "FieldRequired" | "FieldOptional";
export type RequiresSerializationType = "AggregateType" | "PrimitiveType";
export type RequiresSerializationCheck<T extends PlainObject> =
    T extends AggregateType ? "AggregateType" : "PrimitiveType";

/** A param definition for inputs.
 *
 * NOTE: This is *type-only*. There is intentionally no runtime validation.
 * - When `REQ = false`, a `defaultValue` must exist and the caller key is optional.
 * - When `REQ = true`, there is no default and the caller key is required.
 */
export type InputParamDef<
    T extends PlainObject,
    REQ extends boolean
> = {
    /** Phantom to preserve T and make it invariant. Never read or written. */
    readonly __param_input_brand?: T; // Use a simple branded property instead
    /** Optional doc string */
    description?: string;
} & (REQ extends false
    ? { _hasDefault: true; defaultValue: DefaultSpec<T> } // if this param is omitted by the caller, default is used
    : {});                                           // param is required by caller

export function defineParam<T extends PlainObject>(opts: {
    description?: string
} & DefaultSpec<T>): InputParamDef<DeepWiden<T>, false> {
    if (opts.expression === undefined && opts.from === undefined) {
        throw new Error("Invalid DefaultSpec: neither expression nor from provided");
    }
    return {
        // phantom is omitted at runtime; TS still sees it
        _hasDefault: true,
        description: opts.description,
        defaultValue: {
            ...(opts.expression !== undefined ? {expression: opts.expression as DeepWiden<T>} : {}),
            ...(opts.from !== undefined ? {from: opts.from, type: typeToken<DeepWiden<T>>()} : {})
        } as any
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
    name: AllowLiteralOrExpression<string>;
    key: AllowLiteralOrExpression<string>;
    optional?: boolean;
};

export function configMapKey(
    name: AllowLiteralOrExpression<string>,
    key: AllowLiteralOrExpression<string>,
    optional?: boolean
): ConfigMapKeySelector {
    return {'name': name, 'key': key, 'optional': optional};
}

export type SuppliedValueFrom = { [key: string]: any };

/** Output param (same issue & same fix) */
export type OutputParamDef<T extends PlainObject> = {
    /** Phantom to preserve T and make it invariant. Never read or written. */
    readonly __param_output_brand?: T; // Use a simple branded property instead
    description?: string;
} & (
    | { fromWhere: "path"; path: string }
    | { fromWhere: "expression"; expression: BaseExpression<T> }
    | { fromWhere: "parameter"; parameter: FromParameterExpression<T, TaskOutputSource | StepOutputSource> }
    | { fromWhere: "jsonPath"; jsonPath: string }
    | { fromWhere: "jqFilter"; jqFilter: string }
    | { fromWhere: "event"; event: string }
    | { fromWhere: "configMapKeyRef"; configMapKeyRef: ConfigMapKeySelector }
    | { fromWhere: "supplied"; supplied: SuppliedValueFrom }
    | { fromWhere: "default"; default: BaseExpression<T> }
    );

/** Canonical maps for inputs/outputs (values are type-only descriptors). */
export type InputParametersRecord = Record<string, InputParamDef<any, boolean>>;
export type OutputParametersRecord = Record<string, OutputParamDef<any>>;

