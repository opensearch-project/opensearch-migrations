import {z, ZodType, ZodTypeAny} from 'zod';
import {InputParametersRecord, OutputParametersRecord} from "@/schemas/parameterSchemas";

export type TemplateDef<
    IN extends InputParametersRecord = InputParametersRecord,
    OUT extends OutputParametersRecord = OutputParametersRecord
> = {
    isTemplateDef: true,
    inputs: IN,
    outputs?: OUT
};

export function defineOuterWorkflowTemplate<
    T extends Record<string, TemplateDef<any>>,
    IPR extends InputParametersRecord
>(wf: {
    name: string;
    serviceAccountName: string;
    workflowParams?: IPR,
    templates: T;
}) {
    return wf;
}

export abstract class Scope<T> {
    protected abstract readonly predicate: (val: unknown) => val is T;

    get allMatchingItems(): Record<string, T> {
        const collected: Record<string, T> = {};
        const proto = Object.getPrototypeOf(this);
        for (const key of Object.getOwnPropertyNames(proto)) {
            const descriptor = Object.getOwnPropertyDescriptor(proto, key);

            // Check getter
            if (descriptor?.get) {
                const val = (this as any)[key];
                if (this.predicate(val)) {
                    collected[key] = val;
                }
            }

            // Check method returning value
            if (typeof descriptor?.value === "function") {
                const result = descriptor.value.call(this);
                if (this.predicate(result)) {
                    collected[key] = result;
                }
            }
        }

        return collected;
    }
}

export abstract class TemplateScope<WP extends InputParametersRecord> extends Scope<TemplateDef<any,any>> {
    protected predicate =
        (x: any) : x is TemplateDef<any, any> => (!!x && typeof x === "object" && "isTemplateDef" in x);

    build(p:{
        name: string,
        serviceAccountName: string,
        workflowParameters: WP
    }) {
        return defineOuterWorkflowTemplate({...p, templates: this.allMatchingItems});
    }
}

export type WorkflowTask = {
    templateRef: TemplateDef
}

export type StepsTemplateDef<IN extends InputParametersRecord, OUT extends OutputParametersRecord> =
    TemplateDef<IN, OUT> & {
    steps: [Record<string, WorkflowTask>]
}

export function defineStepsTemplate()  {
    return {};
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

