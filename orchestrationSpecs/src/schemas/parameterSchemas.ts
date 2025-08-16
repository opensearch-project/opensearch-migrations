import {z, ZodType, ZodTypeAny} from 'zod';
import {inputParam, workflowParam} from "@/schemas/expression";
import {DeepWiden, PlainObject} from "@/schemas/plainObject";

export type InputParamDef<T extends PlainObject, REQ extends boolean> = {
    type: ZodType<T>;
    defaultValue?: T;
    description?: string;
} & (REQ extends false ? { _hasDefault: true } : {});

export function defineParam<T extends PlainObject>(opts: {
    defaultValue: T;
    description?: string;
}): InputParamDef<DeepWiden<T>, false> {
    return {
        type: z.custom<DeepWiden<T>>(),
        defaultValue: opts.defaultValue as unknown as DeepWiden<T>,
        description: opts.description,
        _hasDefault: true
    };
}

// Could be used to define Workflow Parameters
export function defineRequiredParam<T extends PlainObject>(opts: {
    type: ZodType<T>;
    description?: string;
}): InputParamDef<T, true> {
    return {
        type: opts.type,
        description: opts.description,
    };
}

// Supporting types for Argo output parameters
export type ConfigMapKeySelector = {
    name: string;
    key: string;
    optional?: boolean;
};

export type SuppliedValueFrom = {
    // This would be defined based on Argo's SuppliedValueFrom spec
    // For now, keeping it simple
    [key: string]: any;
};

export type OutputParamDef<T extends PlainObject> = {
    type: ZodType<T>;
    description?: string;
} & (
    | { fromWhere: "path"; path: string }
    | { fromWhere: "expression"; expression: string }
    | { fromWhere: "parameter"; parameter: string }
    | { fromWhere: "jsonPath"; jsonPath: string }
    | { fromWhere: "jqFilter"; jqFilter: string }
    | { fromWhere: "event"; event: string }
    | { fromWhere: "configMapKeyRef"; configMapKeyRef: ConfigMapKeySelector }
    | { fromWhere: "supplied"; supplied: SuppliedValueFrom }
    | { fromWhere: "default"; default: string }
);

export type InputParametersRecord = Record<string, InputParamDef<any, boolean>>;
export type OutputParametersRecord = Record<string, OutputParamDef<any>>;

export function paramsToCallerSchema<T extends InputParametersRecord>(
    defs: T
): z.ZodObject<{ [K in keyof T]: T[K] extends { _hasDefault: true } ? z.ZodOptional<T[K]['type']> : T[K]['type']; }> {
    const shape: Record<string, ZodTypeAny> = {};
    for (const [key, param] of Object.entries(defs)) {
        shape[key] = '_hasDefault' in param ? param.type.optional() : param.type;
    }

    return z.object(shape) as any;
}

export function templateInputParametersAsExpressions<WP extends Record<string, any>>(params: WP) {
    const result: any = {};
    const workflowParams = params || {};
    Object.keys(params).forEach(key => {
        result[key] = inputParam(key, workflowParams[key]);
    });
    return result;
}

export function workflowParametersAsExpressions<WP extends InputParametersRecord>(params: WP) {
    const result: any = {};
    const workflowParams = params || {};
    Object.keys(params).forEach(key => {
        result[key] = workflowParam(key, workflowParams[key]);
    });
    return result;
}
