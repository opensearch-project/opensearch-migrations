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
