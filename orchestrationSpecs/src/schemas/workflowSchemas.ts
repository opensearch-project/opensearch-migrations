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

class ScopeBuilder<SigScope extends Scope = Scope, FullScope extends Scope = Scope> {
    constructor(
        protected readonly sigScope: SigScope,
        protected readonly fullScope: FullScope
    ) {}

    protected addWithCtor<
        AddSig extends Scope,
        AddFull extends Scope,
        B extends ScopeBuilder<ExtendScope<SigScope, AddSig>, ExtendScope<FullScope, AddFull>>
    >(
        sigFn: ScopeFn<SigScope, AddSig>,
        fullFn: ScopeFn<FullScope, AddFull>,
        builderCtor: (sigScope: Readonly<ExtendScope<SigScope, AddSig>>, fullScope: Readonly<ExtendScope<FullScope, AddFull>>) => B
    ): B {
        const newSigScope = { ...this.sigScope, ...sigFn(this.sigScope) } as ExtendScope<SigScope, AddSig>;
        const newFullScope = { ...this.fullScope, ...fullFn(this.fullScope) } as ExtendScope<FullScope, AddFull>;
        return builderCtor(newSigScope, newFullScope);
    }

    getSigScope(): SigScope {
        return this.sigScope;
    }
    getFullScope(): FullScope {
        return this.fullScope;
    }
}

class UnifiedScopeBuilder<SingleScope extends Scope = Scope> extends ScopeBuilder<SingleScope, SingleScope> {
    constructor(protected readonly scope: SingleScope) {
        super(scope, scope);
    }

    protected addToBothWithCtor<
        Add extends Scope,
        B extends ScopeBuilder<ExtendScope<SingleScope, Add>, ExtendScope<SingleScope, Add>>
    >(
        fn: ScopeFn<SingleScope, Add>,
        builderCtor: (scope: ExtendScope<SingleScope, Add>) => B
    ): B {
        const newScope = { ...this.sigScope, ...fn(this.sigScope) } as ExtendScope<SingleScope, Add>;
        return builderCtor(newScope);
    }
}

export class WFBuilder<S extends Scope = Scope> extends UnifiedScopeBuilder<Scope> {
    static createEmpty(): WFBuilder {
        return new WFBuilder({});
    }

    addParams(params: InputParametersRecord) {
        const wfp = ({"workflowParams": params});
        return super.addToBothWithCtor(
            () => wfp,
            ss => new TemplateChainer(ss,ss)
        );
    }
}


export class TemplateChainer<SigScope extends Scope = Scope, FullScope extends Scope = Scope>
    extends ScopeBuilder<SigScope, FullScope>
{
    addTemplate<
        NSS extends Scope,
        NFS extends Scope
    >(name: string, fn: (tb: TemplateBuilder<SigScope>) => TemplateBuilder<NSS>) {
        // Create a template-scoped builder that exposes workflowParams and inputs separately
        const templateScope = {
            ...this.sigScope,
            inputs: {} // Start with empty inputs for the template
        };
        // start w/ the same public/full scope for a new TemplateBuilder
        const templateResult = fn(new TemplateBuilder(templateScope));

        return super.addWithCtor(() => ({
                "templates": {
                    ...((this.sigScope as any).templates || {}),
                    [name]: {
                        input: (templateResult.getSigScope().inputs || {})
                    }
                }
            }),
            () => ({
                "templates": {
                    ...((this.fullScope as any).templates || {}),
                    [name]: templateResult.getFullScope()
                }
            }), (sigScope, fullScope) => new TemplateChainer(sigScope, fullScope));
    }
}

export class TemplateBuilder<SingleScope extends Scope = Scope> extends UnifiedScopeBuilder<SingleScope> {
    addOptional<T>(name: string,
        defaultValueFromScopeFn: (scope: SingleScope) => T,
        description?: string
    ) {
        const param = defineParam({
            defaultValue: defaultValueFromScopeFn(this.scope),
            description: description
        });
        
        return super.addToBothWithCtor(() => ({
            inputs: { 
                ...((this.sigScope as any).inputs || {}), 
                [name]: param 
            } 
        }), (sigScope) => new TemplateBuilder(sigScope));
    }

    // addRequired<T>(name: string, type: ZodType<T>, description?: string) {
    //     const param = defineRequiredParam({type, description});
    //     return super.addWithCtor(() => ({
    //         inputs: {
    //             ...((this.sigScope as any).inputs || {}),
    //             [name]: param
    //         }
    //     }), () => ({
    //         inputs: {
    //             ...((this.fullScope as any).inputs || {}),
    //             [name]: param
    //         }
    //     }), (sigScope, fullScope) => new TemplateBuilder(sigScope, fullScope));
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
