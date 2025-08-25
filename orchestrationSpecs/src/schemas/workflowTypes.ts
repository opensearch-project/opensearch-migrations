// Internal types for workflow schema implementation
import {ZodType, ZodTypeAny, z} from "zod";
import {InputParamDef, InputParametersRecord, OutputParamDef, OutputParametersRecord} from "@/schemas/parameterSchemas";
import {TypescriptError} from "@/utils";
import {BaseExpression, FromParameterExpression, inputParam, stepOutput} from "@/schemas/expression";
import {DeepWiden, PlainObject} from "@/schemas/plainObject";

declare global {
    // true: worse LSP, but squigglies under the name declaration
    // false: squigglies under other parts of named constructs instead of the declaration, but better LSP support
    const __PREFER_UNIQUE_NAME_CHECKS_AT_NAME_SITE__: boolean;
}

// Specific scope types for different purposes
export type WorkflowAndTemplatesScope<TemplateSignatures extends TemplateSignaturesScope = TemplateSignaturesScope> = { workflowParameters?: InputParametersRecord, templates?: TemplateSignatures };
export type DataScope = Record<string, AllowLiteralOrExpression<PlainObject>>;
export type GenericScope = Record<string, any>;
//export type DagScope = Record<string, DagTask>;
export type TasksOutputsScope = Record<string, TasksWithOutputs<any, any>>;
export type TemplateSignaturesScope = Record<string, TemplateSigEntry<{ inputs: InputParametersRecord; outputs?: OutputParametersRecord }>>;

export type ScopeFn<S extends Record<string, any>, ADDITIONS extends Record<string, any>> = (scope: Readonly<S>) => ADDITIONS;

// Internal type for scope extension - used internally by builder methods
export type ExtendScope<S extends Record<string, any>, ADDITIONS extends Record<string, any>> = S & ADDITIONS;

export type TemplateSigEntry<T extends { inputs: any; outputs?: any }> = {
    input: T["inputs"];
    output?: T extends { outputs: infer O } ? O : never;
};

// shared-types.ts - Export these types from a shared file
export type FieldSpecs = Record<string, ZodTypeAny | string | number | boolean>;

export type FieldSpecsToInputParams<T extends FieldSpecs> = {
    [K in keyof T]: InputParamDef<any, true>;
};

// Constraint type for checking field group conflicts
export type FieldGroupConstraint<T extends FieldSpecs, S extends Record<string, any>, TypeWhenValid> =
    typeof __PREFER_UNIQUE_NAME_CHECKS_AT_NAME_SITE__ extends true
        ? keyof T & keyof S extends never
            ? TypeWhenValid
            : TypescriptError<`Field group contains conflicting names: '${keyof T & keyof S & string}' already exists`>
        : TypeWhenValid;

// Helper types for extracting output types and creating step references
export type ExtractOutputParamType<OPD> = OPD extends OutputParamDef<infer T> ? T : never;

// Helper type to allow both literal values and expressions
export type AllowLiteralOrExpression<T extends PlainObject> = T extends string
    ? string | BaseExpression<string>
    : T extends number
        ? number | BaseExpression<number>
        : T extends boolean
            ? boolean | BaseExpression<boolean>
            : T extends Array<infer U>
                ? U extends PlainObject
                    ? Array<AllowLiteralOrExpression<U>> | BaseExpression<T>
                    : never
                : T extends object
                    ? { [K in keyof T]: T[K] extends PlainObject ? AllowLiteralOrExpression<T[K]> : never } | BaseExpression<T>
                    : T | BaseExpression<T>;

// Apply the literal-or-expression transformation to parameter schemas
export type ParamsWithLiteralsOrExpressions<T> = {
    [K in keyof T]: T[K] extends PlainObject ? AllowLiteralOrExpression<T[K]> : T[K]
};

export type OutputParamsToExpressions<Outputs extends OutputParametersRecord> = {
    [K in keyof Outputs]: ReturnType<typeof stepOutput<ExtractOutputParamType<Outputs[K]>>>
};

export type InputParamsToExpressions<InputParamsScope extends InputParametersRecord> = {
    [K in keyof InputParamsScope]: InputParamsScope[K] extends { type: ZodType<infer T> }
        ? T extends PlainObject
            ? ReturnType<typeof inputParam<T>>
            : never
        : never
};

// Helper type to extract workflow inputs from contextual scope
export type WorkflowInputsToExpressions<ContextualScope extends { workflowParameters?: InputParametersRecord }> =
    ContextualScope extends { workflowParameters: infer WP }
        ? WP extends InputParametersRecord
            ? InputParamsToExpressions<WP>
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

export type TasksScopeToTasksWithOutputs<
    TasksScope extends Record<string, TasksWithOutputs<any, any>>,
    LoopItemsType extends PlainObject = never
> = {
    tasks: {
        [StepName in keyof TasksScope]:
        TasksScope[StepName] extends TasksWithOutputs<
            infer Name,
            infer Outputs
        > ? (Outputs extends OutputParametersRecord ? OutputParamsToExpressions<Outputs> : {}) : {}
    }
} & IfNever<LoopItemsType, {}, { item: AllowLiteralOrExpression<LoopItemsType> }>;

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

export type LoopWithParams<T extends PlainObject> = {
    loopWith: "params",
    value: FromParameterExpression<T[]>
}

export function makeParameterLoop<T extends PlainObject>(param: FromParameterExpression<T[]>) {
    return {
        "loopWith": "params",
        "value": param
    } as LoopWithParams<T>;
}

export type LoopWithUnion<T extends PlainObject> = ( T extends number ? LoopWithSequence : never)
    | LoopWithItems<T>
    | LoopWithParams<T>;
