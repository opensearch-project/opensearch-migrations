// Internal types for workflow schema implementation
import {ZodTypeAny} from "zod";
import {InputParamDef, OutputParamDef, OutputParametersRecord} from "@/schemas/parameterSchemas";
import {TypescriptError} from "@/utils";
import {Expression, stepOutput} from "@/schemas/expression";

declare global {
    // true: worse LSP, but squigglies under the name declaration
    // false: squigglies under other parts of named constructs instead of the declaration, but better LSP support
    const __PREFER_UNIQUE_NAME_CHECKS_AT_NAME_SITE__: boolean;
}

export type Scope = Record<string, any>;
export type ScopeFn<S extends Scope, ADDITIONS extends Scope> = (scope: Readonly<S>) => ADDITIONS;

// Internal type for scope extension - used internally by builder methods
export type ExtendScope<S extends Scope, ADDITIONS extends Scope> = S & ADDITIONS;

export type TemplateSigEntry<T extends { inputs: any }> = {
    input: T["inputs"];
    output?: T extends { outputs: infer O } ? O : never;
};

// shared-types.ts - Export these types from a shared file
export type FieldSpecs = Record<string, ZodTypeAny | string | number | boolean>;

export type FieldSpecsToInputParams<T extends FieldSpecs> = {
    [K in keyof T]: InputParamDef<any, true>;
};

// Constraint type for checking field group conflicts
export type FieldGroupConstraint<T extends FieldSpecs, S extends Scope, TypeWhenValid> =
    typeof __PREFER_UNIQUE_NAME_CHECKS_AT_NAME_SITE__ extends true
        ? keyof T & keyof S extends never
            ? TypeWhenValid
            : TypescriptError<`Field group contains conflicting names: '${keyof T & keyof S & string}' already exists`>
        : TypeWhenValid;

export type ExtractOutputParamType<OPD> = OPD extends OutputParamDef<infer T> ? T : never;

export type OutputParamsToExpressions<Outputs extends OutputParametersRecord> = {
    [K in keyof Outputs]: ReturnType<typeof stepOutput<ExtractOutputParamType<Outputs[K]>>>
};

export type StepWithOutputs<
    StepName extends string,
    TemplateKey extends string,
    Outputs extends OutputParametersRecord | undefined
> = {
    name: StepName;
    template: TemplateKey;
    outputTypes: Outputs;
};

export type StepsScopeToStepsWithOutputs<StepsScope extends Scope> = {
    [StepName in keyof StepsScope]: StepsScope[StepName] extends StepWithOutputs<
        infer Name,
        infer Template,
        infer Outputs
    > ? (Outputs extends OutputParametersRecord ? OutputParamsToExpressions<Outputs> : {}) : {}
};
