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

import {
    ConfigMapKeySelector,
    InputParamDef,
    InputParametersRecord,
    OutputParamDef,
    OutputParametersRecord
} from "./parameterSchemas";
import {
    AllowLiteralOrExpression,
    AllowSerializedAggregateOrPrimitiveExpressionOrLiteral,
    BaseExpression,
    FromParameterExpression,
    InputParameterSource,
    ParameterSource,
    WorkflowParameterSource
} from "./expression";
import {NonSerializedPlainObject, PlainObject} from "./plainObject";
import {TypeToken} from "./sharedTypes";

export type ExpressionOrConfigMapValue<T extends PlainObject> =
    | AllowLiteralOrExpression<T> & {
    type?: never;
    from?: never
}
    | {
    from: ConfigMapKeySelector;
    type: TypeToken<T>;
};

export type LowercaseOnly<S extends string> =
    S extends Lowercase<S> ? S : never;

// Specific scope types for different purposes
export type WorkflowAndTemplatesScope<
    TemplateSignatures extends TemplateSignaturesScopeTyped<Record<string, { inputs: any; outputs?: any }>> =
        TemplateSignaturesScopeTyped<Record<string, { inputs: any; outputs?: any }>>> =
    {
        workflowParameters?: InputParametersRecord,
        templates?: TemplateSignatures
    };
export type DataScope = Record<string, AllowLiteralOrExpression<PlainObject>>;
export type DataOrConfigMapScope = Record<string, ExpressionOrConfigMapValue<PlainObject>>;
export type GenericScope = Record<string, any>;
export type TasksOutputsScope = Record<string, TasksWithOutputs<any, any>>;
export type TemplateSignaturesScopeTyped<Sigs extends Record<string, { inputs: any; outputs?: any }>> = {
    [K in keyof Sigs]: TemplateSigEntry<Sigs[K]>;
};

// Keep a permissive alias for backward compatibility where exact keys aren't needed
export type TemplateSignaturesScope = TemplateSignaturesScopeTyped<Record<string, {
    inputs: InputParametersRecord;
    outputs?: OutputParametersRecord
}>>;

export type ScopeFn<S extends Record<string, any>, ADDITIONS extends Record<string, any>> = (scope: Readonly<S>) => ADDITIONS;

// Internal type for scope extension - used internally by builder methods
export type ExtendScope<S extends Record<string, any>, ADDITIONS extends Record<string, any>> = S & ADDITIONS;

export type TemplateSigEntry<T extends { inputs: any; outputs?: any }> = {
    inputs: T["inputs"];
    outputs?: T extends { outputs: infer O } ? O : never;
};

// Helper types for extracting output types and creating step references
export type ExtractOutputParamType<OPD> = OPD extends OutputParamDef<infer T> ? T : never;

export type InvalidType<T> = { INVALID_TYPE: T };

type StripUndefined<T> = Exclude<T, undefined>;
type HasUndefined<T> = undefined extends T ? true : false;

type NormalizeBaseExpression<T> =
    T extends BaseExpression<infer R, infer C>
        ? HasUndefined<R> extends true
            ? BaseExpression<StripUndefined<R>, C> | undefined
            : BaseExpression<StripUndefined<R>, C>
        : T;

// Apply the literal-or-expression transformation to parameter schemas
export type ParamsWithLiteralsOrExpressions<T> = {
    [K in keyof T]:
    T[K] extends PlainObject | undefined
        ? HasUndefined<T[K]> extends true
            ? AllowLiteralOrExpression<StripUndefined<T[K]>> | undefined
            : AllowLiteralOrExpression<StripUndefined<T[K]>>
        : T[K] extends BaseExpression<any, any>
            ? NormalizeBaseExpression<T[K]>
            : InvalidType<T[K]>;
};

export type ParamsWithLiteralsOrExpressionsIncludingSerialized<T> = {
    [K in keyof T]:
    T[K] extends PlainObject | undefined
        ? HasUndefined<T[K]> extends true
            ? AllowSerializedAggregateOrPrimitiveExpressionOrLiteral<StripUndefined<T[K]>> | undefined
            : AllowSerializedAggregateOrPrimitiveExpressionOrLiteral<StripUndefined<T[K]>>
        : T[K] extends BaseExpression<any, any>
            ? NormalizeBaseExpression<T[K]>
            : InvalidType<T[K]>;
};

export type InputParamsToExpressions<
    InputParamsScope extends InputParametersRecord,
    InputType extends ParameterSource = InputParameterSource
> = {
    [K in keyof InputParamsScope]: InputParamsScope[K] extends InputParamDef<infer T, any>
        ? [T] extends [PlainObject]
            ? [T] extends [null | undefined]
                ? never
                : FromParameterExpression<T, InputType>
            : never
        : never
};

// Helper type to extract workflow inputs from contextual scope
export type WorkflowInputsToExpressions<ContextualScope extends { workflowParameters?: InputParametersRecord }> =
    ContextualScope extends { workflowParameters: infer WP }
        ? WP extends InputParametersRecord
            ? InputParamsToExpressions<WP, WorkflowParameterSource>
            : {}
        : {};

export type TasksWithOutputs<
    TaskName extends string,
    Outputs extends OutputParametersRecord
> = {
    name: TaskName;
    outputTypes?: Outputs;
};

export type IfNever<T, Then, Else> = [T] extends [never] ? Then : Else;

export type LoopWithSequence = {
    loopWith: "sequence",
    count: number
};

export function makeSequenceLoop(count: number) {
    return {
        loopWith: "sequence",
        "count": count
    } as LoopWithSequence;
}

export type LoopWithItems<T extends PlainObject> = {
    loopWith: "items",
    items: T[]
};

export function makeItemsLoop<T extends PlainObject>(items: T[]) {
    return {
        loopWith: "items",
        "items": items
    } as LoopWithItems<T>;
}

export type LoopWithParam<T extends PlainObject> = {
    loopWith: "params",
    value: BaseExpression<T[]>
}

export function makeParameterLoop<T extends NonSerializedPlainObject>(expr: BaseExpression<T[]>) {
    return {
        loopWith: "params",
        value: expr
    } as LoopWithParam<T>;
}

export type LoopWithUnion<T extends PlainObject> = (T extends number ? LoopWithSequence : never)
    | LoopWithItems<T>
    | LoopWithParam<T>;
