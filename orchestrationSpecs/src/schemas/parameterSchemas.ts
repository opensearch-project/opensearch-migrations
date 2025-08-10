import {z, ZodEnum, ZodType, ZodTypeAny} from 'zod';

export type InputParamDef<T, REQ extends boolean> = {
    type: ZodType<T>;
    defaultValue?: T;
    description?: string;
} & (REQ extends false ? { _hasDefault: true } : {});

export function defineParam<T>(opts: {
    defaultValue: T;
    description?: string;
}): InputParamDef<T, false> {
    return {
        type: z.custom<T>(),
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
        type: opts.type,
        description: opts.description,
    };
}

export type OutputParamDef<T> = {
    type: ZodType<T>;
    fromWhere: "path" | "expression";
    description?: string | null;
};

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
