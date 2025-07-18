import {InputParametersRecord, OutputParametersRecord, paramsToCallerSchema} from "@/schemas/parameterSchemas";
import {z} from "zod";
import {AggregatingScope} from "@/scopeHelpers";
import {getKeyAndValue, getKeyAndValueClass} from "@/utils";
import {Class} from "zod/v4/core/util";

export type OuterWorkflowTemplate<
    T extends Record<string, TemplateDef<any,any>>,
    IPR extends InputParametersRecord
> = {
    name: string;
    serviceAccountName: string;
    workflowParams?: IPR,
    templates: T;
};

const TemplateDefSchema = z.object({
    inputs: z.any(),
    outputs: z.any(),
});

export type TemplateDef<
    IN extends InputParametersRecord,
    OUT extends OutputParametersRecord
> = z.infer<typeof TemplateDefSchema> & {
    inputs: IN;
    outputs: OUT;
};


export class OuterWorkflowTemplateScope<WP extends InputParametersRecord> extends AggregatingScope<TemplateDef<any,any>> {
    protected predicate =
        (k:string, v: any) : v is TemplateDef<any, any> => (!!v && typeof v === "object" && "inputs" in v);

    // build(p:{
    //     name: string,
    //     serviceAccountName: string,
    //     workflowParameters: WP
    // }) : OuterWorkflowTemplate<typeof this.allMatchingItems, WP> {
    //     return {...p, templates: this.allMatchingItems};
    // }
}

export type ContainerTemplateDef<IN extends InputParametersRecord, OUT extends OutputParametersRecord=OutputParametersRecord> =
    TemplateDef<IN, OUT> & {
    container: {
        image: string
        args: string[] // will be argo expressions
    }
}

export type WorkflowTask<
    IN extends InputParametersRecord,
    OUT extends OutputParametersRecord
> = {
    templateRef: { key: string, value: TemplateDef<IN,OUT> }
    arguments?: { parameters: any }
}

type TaskGetterNames<T> = { [K in keyof T]: T[K] extends () => WorkflowTask<any, any> ? K : never; }[keyof T];
type TaskGetters<T> = Pick<T, TaskGetterNames<T>>;

// Base class for defining workflow steps with type constraints
export abstract class StepsTemplate<
    IN extends InputParametersRecord = any,
    OUT extends OutputParametersRecord = any
> {
    public abstract readonly inputs: IN;
    public abstract readonly outputs: OUT;
    public abstract readonly steps: Record<string, WorkflowTask<any, any>>;
}

// More specific base class that enforces the relationship between inputs/outputs and steps
export abstract class TypedStepsTemplate<
    IN extends InputParametersRecord,
    OUT extends OutputParametersRecord
> extends StepsTemplate<IN, OUT> {
    public abstract readonly inputs: IN;
    public abstract readonly outputs: OUT;
    // Steps can reference this.inputs and previous steps
    public abstract readonly steps: Record<string, WorkflowTask<any, any>>;
}

// Helper types for extracting types from steps templates
export type ExtractInputs<T> = T extends StepsTemplate<infer IN, any> ? IN : never;
export type ExtractOutputs<T> = T extends StepsTemplate<any, infer OUT> ? OUT : never;

// export function defineDagTemplate<>() {
//
// }
//
// class DagTasksScope extends Scope<TemplateDef<any,any>> {
//     constructor() {
//         super((x:any): x is TemplateDef<any,any> => !!x && typeof x === "object" && "isTemplateDef" in x);
//     }
// }

// Simplified approach that focuses on practical type safety
export function callTemplate<
    TClass extends Record<string, any>,
    TKey extends keyof TClass
>(
    classConstructor: TClass,
    key: TKey,
    params: any
): WorkflowTask<any, any> {
    const kvp = getKeyAndValueClass(classConstructor, key);
    return {
        templateRef: { key: key as string, value: kvp.value },
        arguments: { parameters: params }
    };
}

// Type-safe version that requires explicit input type specification
export function callTemplateWithInputs<
    IN extends InputParametersRecord
>(
    templateRef: { key: string, value: { inputs: IN } },
    params: z.infer<ReturnType<typeof paramsToCallerSchema<IN>>>
): WorkflowTask<IN, any> {
    return {
        templateRef: { key: templateRef.key, value: templateRef.value as any },
        arguments: { parameters: params }
    };
}

// Helper function to create template references from class static members
export function templateRef<
    TClass extends Record<string, any>,
    TKey extends keyof TClass
>(classConstructor: TClass, key: TKey): { key: TKey, value: TClass[TKey] } {
    return getKeyAndValueClass(classConstructor, key);
}

// Type-safe wrapper for when you want explicit type checking
export function callTemplateTyped<IN extends InputParametersRecord>(
    templateRef: { key: string, value: { inputs: IN } },
    params: z.infer<ReturnType<typeof paramsToCallerSchema<IN>>>
): WorkflowTask<IN, any> {
    return {
        templateRef: { key: templateRef.key, value: templateRef.value as any },
        arguments: { parameters: params }
    };
}
