import {z, ZodType, ZodTypeAny} from 'zod';

export type InputParamDef<T, REQ extends boolean> = {
    schema: ZodType<T>;
    defaultValue?: T;
    description?: string;
} & (REQ extends false ? { _hasDefault: true } : {});

export function defineParam<T>(opts: {
    defaultValue: T;
    description?: string;
}): InputParamDef<T, false> {
    return {
        schema: z.custom<T>(),
        defaultValue: opts.defaultValue,
        description: opts.description,
        _hasDefault: true
    };
}

export function defineRequiredParam<T>(opts: {
    type: ZodType<T>;
    description?: string;
}): InputParamDef<T, true> {
    return {
        schema: opts.type,
        description: opts.description,
    };
}

export type OutputParamDef<T> = {
    type: ZodType<T>;
    description: string | null;
};

export type InputParametersRecord = Record<string, InputParamDef<any, boolean>>;
export type OutputParametersRecord = Record<string, OutputParamDef<any>>;

export function paramsToCallerSchema<T extends InputParametersRecord>(
    defs: T
): z.ZodObject<{ [K in keyof T]: T[K] extends { _hasDefault: true } ? z.ZodOptional<T[K]['schema']> : T[K]['schema']; }> {
    const shape: Record<string, ZodTypeAny> = {};
    for (const [key, param] of Object.entries(defs)) {
        shape[key] = '_hasDefault' in param ? param.schema.optional() : param.schema;
    }

    return z.object(shape) as any;
}

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

