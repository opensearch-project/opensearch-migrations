import {
    defineParam,
    defineRequiredParam,
    InputParamDef,
    InputParametersRecord,
    OutputParametersRecord,
    paramsToCallerSchema
} from "@/schemas/parameterSchemas";
import {z} from "zod";
import {AggregatingScope} from "@/scopeHelpers";
import {getKeyAndValue, getKeyAndValueClass} from "@/utils";
import {Class} from "zod/v4/core/util";
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";
import {ZodType} from "zod/index";

export type Scope = Record<string, any>;
export type ExtendScope<S extends Scope, ADDITIONS extends Scope> = S & ADDITIONS;
export type ScopeFn<S extends Scope, ADDITIONS extends Scope> = (scope: Readonly<S>) => ADDITIONS;
export type TemplateBuilderFn<S extends Scope, NS extends Scope> = (tb: TemplateBuilder<S>) => TemplateBuilder<NS>;
export type BuilderCtor<S extends Scope, B extends ScopeBuilder<S>> = (scope: Readonly<S>) => B;

export class ScopeBuilder<S extends Scope = Scope> {
    constructor(protected readonly scope: S) {}

    protected addWithCtor<
        Add extends Scope,
        B extends ScopeBuilder<ExtendScope<S, Add>>
    >(
        fn: ScopeFn<S, Add>,
        builderCtor: BuilderCtor<ExtendScope<S, Add>, B>
    ): B {
        const newScope = { ...this.scope, ...fn(this.scope) } as ExtendScope<S, Add>;
        return builderCtor(newScope);
    }

    protected add<Add extends Scope>(
        fn: ScopeFn<S, Add>
    ): ScopeBuilder<ExtendScope<S, Add>> {
        return this.addWithCtor(fn, s => new ScopeBuilder(s));
    }

    build(): S {
        return this.scope;
    }
}

export class WFBuilder<S extends Scope = Scope> extends ScopeBuilder<S> {
    private readonly ctor = <S extends Scope>(a: S) => new WFBuilder(a);
    addParams(params: InputParametersRecord) {
        return super.addWithCtor(() => ({ "workflowParams": params }), this.ctor);
    }
    addTemplate<NS extends Scope>(name: string, fn: TemplateBuilderFn<S, NS>) {
        const templateToAdd = fn(new TemplateBuilder(this.scope)).build();
        return super.addWithCtor(() => ({ "templates": { [name]: templateToAdd } }), this.ctor);
    }
}

export class TemplateBuilder<S extends Scope = Scope> extends ScopeBuilder<S> {
    private readonly ctor = <S extends Scope>(a: S) => new TemplateBuilder(a);

    addOptional<T>(name: string,
        defaultValueFromScopeFn: (scope: S) => T,
        description?: string
    ) {
        const param =  defineParam({defaultValue: defaultValueFromScopeFn(this.scope), description: description});
        return super.addWithCtor(() => ({ [name]: param }), this.ctor);
    }

    // export function defineRequiredParam<T>(opts: {
    //     type: ZodType<T>;
    //     description?: string;
    // }): InputParamDef<T, true> {
    //     return {
    //         type: opts.type,
    //         description: opts.description,
    //     };
    // }
}

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

export abstract class OuterWorkflowTemplateScope {
    workflowParameters: InputParametersRecord = CommonWorkflowParameters;
    serviceAccountName?: string;
}

export interface StepListInterface {
    [key: string]: WorkflowTask<any, any>;
}

export interface StepsInterface {
    steps: StepListInterface;
}

export function stepsList<T extends object>(instance: T): {
    [K in keyof T]: T[K];
} {
    const result: any = {};
    for (const key of Object.getOwnPropertyNames(instance)) {
        const val = (instance as any)[key];
        if (val?.input !== undefined && val?.output !== undefined) {
            result[key] = val;
        }
    }
    return result;
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

// type TaskGetterNames<T> = { [K in keyof T]: T[K] extends () => WorkflowTask<any, any> ? K : never; }[keyof T];
// type TaskGetters<T> = Pick<T, TaskGetterNames<T>>;

// // Base class for defining workflow steps with type constraints
// export abstract class StepsTemplate<
//     IN extends InputParametersRecord = any,
//     OUT extends OutputParametersRecord = any
// > {
//     public abstract readonly inputs: IN;
//     public abstract readonly outputs: OUT;
//     public abstract readonly steps: Record<string, WorkflowTask<any, any>>;
// }
//
// // More specific base class that enforces the relationship between inputs/outputs and steps
// export abstract class TypedStepsTemplate<
//     IN extends InputParametersRecord,
//     OUT extends OutputParametersRecord
// > extends StepsTemplate<IN, OUT> {
//     public abstract readonly inputs: IN;
//     public abstract readonly outputs: OUT;
//     // Steps should be a class/object instance where all properties are WorkflowTask instances
//     // This allows referencing specific steps by name while maintaining type safety
//     public abstract readonly steps: WorkflowStepsClass;
// }
//
// // Helper type to enforce that all properties in a class are WorkflowTask instances
// // Using intersection with object to allow for class instances while maintaining type safety
// export type WorkflowStepsClass = object & Record<string, WorkflowTask<any, any>>;

// // Utility function to validate that all getters in a class prototype return WorkflowTask instances
// export function validateWorkflowStepsClass<T extends object>(
//     instance: T
// ): asserts instance is T & WorkflowStepsClass {
//     const proto = Object.getPrototypeOf(instance);
//     const descriptors = Object.getOwnPropertyDescriptors(proto);
//
//     for (const [key, descriptor] of Object.entries(descriptors)) {
//         if (descriptor.get && key !== 'constructor') {
//             const value = descriptor.get.call(instance);
//             if (!value || typeof value !== 'object' || !('templateRef' in value)) {
//                 throw new Error(`Getter '${key}' does not return a WorkflowTask instance`);
//             }
//         }
//     }
// }

// // Helper function to create a properly typed steps class
// export function createStepsClass<T extends Record<string, WorkflowTask<any, any>>>(
//     stepsClass: new () => T
// ): T & WorkflowStepsClass {
//     return new stepsClass() as T & WorkflowStepsClass;
// }

// Helper types for extracting types from steps templates
// export type ExtractInputs<T> = T extends StepsTemplate<infer IN, any> ? IN : never;
// export type ExtractOutputs<T> = T extends StepsTemplate<any, infer OUT> ? OUT : never;

// export function defineDagTemplate<>() {
//
// }
//
// class DagTasksScope extends Scope<TemplateDef<any,any>> {
//     constructor() {
//         super((x:any): x is TemplateDef<any,any> => !!x && typeof x === "object" && "isTemplateDef" in x);
//     }
// }

export function callTemplate<
    TClass extends Record<string, any>,
    TKey extends Extract<keyof TClass, string>
>(
    classConstructor: TClass,
    key: TKey,
    params: z.infer<ReturnType<typeof paramsToCallerSchema<TClass[TKey]["inputs"]>>>
): WorkflowTask<TClass[TKey]["inputs"], TClass[TKey]["outputs"]> {
    const value = classConstructor[key];
    return {
        templateRef: { key, value },
        arguments: { parameters: params }
    };
}
//
// // Helper function to create template references from class static members
// export function templateRef<
//     TClass extends Record<string, any>,
//     TKey extends keyof TClass
// >(classConstructor: TClass, key: TKey): { key: TKey, value: TClass[TKey] } {
//     return getKeyAndValueClass(classConstructor, key);
// }
