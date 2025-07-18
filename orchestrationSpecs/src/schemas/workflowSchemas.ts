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

export abstract class Steps<
    STEPS extends Record<string, WorkflowTask<any, any>>
> {
    public abstract readonly steps: STEPS;
}

export abstract class StepsTemplate<S> {
    public abstract readonly steps: S;
}

// export function defineDagTemplate<>() {
//
// }
//
// class DagTasksScope extends Scope<TemplateDef<any,any>> {
//     constructor() {
//         super((x:any): x is TemplateDef<any,any> => !!x && typeof x === "object" && "isTemplateDef" in x);
//     }
// }

// More flexible approach that works with your existing inline structure
export function callTemplate<
    TClass extends Record<string, any>,
    TKey extends keyof TClass
>(
    classConstructor: TClass,
    key: TKey,
    params: any
): WorkflowTask<any, any>;

// Overload with explicit type parameter for better type safety when needed
export function callTemplate<
    IN extends InputParametersRecord,
    TClass extends Record<string, any>,
    TKey extends keyof TClass
>(
    classConstructor: TClass,
    key: TKey,
    params: z.infer<ReturnType<typeof paramsToCallerSchema<IN>>>
): WorkflowTask<IN, any>;

// Implementation
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
