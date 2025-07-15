import {z, ZodType, ZodTypeAny} from 'zod';

export type NormalizedParamDef<T, R extends boolean = false> = {
    type: ZodType<T>;
    defaultValue: T | null;
    description: string | null;
    required : R;
};

type ParamShape<R extends boolean = boolean> = Record<string, NormalizedParamDef<any, R>>;

// Utility to map ParamDef<T> to ZodType<T>
type ExtractZodShape<T extends ParamShape> = {
    [K in keyof T]: T[K]['type'];
};

export function paramsToCallerSchema<T extends ParamShape>(
    defs: T
): z.ZodObject<{
    [K in keyof T]: T[K]['required'] extends true
        ? T[K]['type']
        : z.ZodOptional<T[K]['type']>;
}> {
    const shape: Record<string, ZodTypeAny> = {};
    for (const [key, param] of Object.entries(defs)) {
        shape[key] = param.required === true ? param.type : param.type.optional();
    }

    return z.object(shape) as any;
}

export function defineParam<T, R extends boolean = false>(opts: {
  type?: z.ZodType<T>;
  description?: string;
  defaultValue?: T;
  required?: R;
}): NormalizedParamDef<T, R extends undefined ? false : R> {
    const inferredType: ZodType<T> | null = opts.type ?? (
        opts.defaultValue !== undefined
            ? z.custom<T>()
            : null
    );

    if (!inferredType) throw new Error("Missing 'type' or 'defaultValue'");
    if (opts.defaultValue !== undefined && opts.required) throw new Error("No reason to define a defaultValue for a requiredParam")
    return {
        type: inferredType,
        description: opts.description ?? null,
        defaultValue: opts.defaultValue ?? null,
        required: (opts.required ?? (opts.defaultValue == null)) as any,
    };
}

export function defineRequiredParam<T>(opts: {
    type?: z.ZodType<T>;
    description?: string;
}): NormalizedParamDef<T, true> {
    return defineParam({type: opts.type, description: opts.description, required: true});
}
