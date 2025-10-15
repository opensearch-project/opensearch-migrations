import {InputParamDef, InputParametersRecord} from "./parameterSchemas";
import {
    BaseExpression,
    expr,
    ExpressionType,
    FromParameterExpression,
    ParameterSource,
    UnwrapSerialize
} from "./expression";
import {AggregateType, NonSerializedPlainObject, PlainObject, Serialized} from "./plainObject";
import {StripUndefined} from "./sharedTypes";
import {Primitive} from "zod/v3";

export type ValueHasDefault<V> = V extends { _hasDefault: true } ? true : false;

// Collect keys that are *required* at callsite (i.e., no _hasDefault: true)
export type SomeRequiredKey<T> =
    [keyof T] extends [never] ? never
        : { [K in keyof T]-?: ValueHasDefault<T[K]> extends true ? never : K }[keyof T];
export type HasRequiredByDef<T> = [SomeRequiredKey<T>] extends [never] ? false : true;

export type NormalizeInputs<T> = [T] extends [undefined] ? {} : T;

// Is K optional in T?
export type IsOptionalKey<T, K extends keyof T> =
    {} extends Pick<T, K> ? true : false;

// Union of the *required* (non-optional) keys of T
export type RequiredKeys<T> =
    T extends object
        ? { [K in keyof T]-?: IsOptionalKey<T, K> extends true ? never : K }[keyof T]
        : never;

// Required keys of the first parameter of R (if it’s an object)
export type RequiredKeysOfRegister<R extends (arg: any, ...rest: any[]) => any> =
    Parameters<R>[0] extends object
        ? RequiredKeys<Parameters<R>[0]>
        : never;

/**
 * Read an optional runtime allow-list of parameter keys from the callback object,
 * but keep the type tied to the *input params* keys.
 */
export function getAcceptedRegisterKeys<
    Inputs extends Record<string, any>
>(
    cb:
        | { parameterKeys?: readonly (Extract<keyof Inputs, string>)[] }
        | {
        register: ((arg: any, ...rest: any[]) => any) & {
            parameterKeys?: readonly (Extract<keyof Inputs, string>)[];
        };
    }
): readonly (Extract<keyof Inputs, string>)[];

/** Implementation signature */
export function getAcceptedRegisterKeys(cb: any): readonly string[] {
    if (cb && Array.isArray(cb.parameterKeys)) return cb.parameterKeys as readonly string[];
    if (cb?.register && Array.isArray(cb.register.parameterKeys)) return cb.register.parameterKeys as readonly string[];
    return [];
}

/**
 * NEW: Strongly-typed helper that keeps only the provided keys.
 * - `builder.inputs` can be any record
 * - `keys` must be a readonly array of keys from `builder.inputs`
 * - Return type is precisely Pick<inputs, keys[number]>
 */
export function selectInputsForKeys<
    BuilderT extends { inputs: Record<string, any> },
    Ks extends readonly (keyof BuilderT["inputs"])[]
>(
    builder: BuilderT,
    keys: Ks
): Pick<BuilderT["inputs"], Ks[number]> {
    const src = builder.inputs as Record<string, any>;
    const out: Partial<BuilderT["inputs"]> = {};

    for (const k of keys) {
        if (Object.prototype.hasOwnProperty.call(src, k as string)) {
            (out as any)[k] = src[k as string];
        }
    }

    return out as Pick<BuilderT["inputs"], Ks[number]>;
}

/**
 * Infers the correct Pick<...> from (b.inputs, c.register) at compile time,
 * and at runtime returns only those inputs considered "required at callsite".
 *
 * B: any object with an `inputs` record
 * C: any object with a `register` function of shape (arg: {...}) => any
 */
export function selectInputsForRegister<
    BuilderT extends { inputs: Record<string, any> },
    ParamsCallbackT extends { register: (arg: any, ...rest: any[]) => any }
>(
    builder: BuilderT,
    callback: ParamsCallbackT
): Pick<BuilderT["inputs"], Extract<keyof BuilderT["inputs"], RequiredKeysOfRegister<ParamsCallbackT["register"]>>> {
    const keysToKeep = getAcceptedRegisterKeys(callback) as readonly (keyof BuilderT["inputs"])[];
    return selectInputsForKeys(builder, keysToKeep) as any;
}

// Keys whose param defs carry a default (i.e., optional at callsite)
export type OptionalKeysAtCallsite<T> =
    [keyof T] extends [never] ? never
        : { [K in keyof T]-?: ValueHasDefault<T[K]> extends true ? K : never }[keyof T];

/** Type-only: infer the value type from an InputParamDef. */
export type InferParamType<P> = P extends InputParamDef<infer T, any> ? T : never;
/** Type-only: compute the caller object type.
 * - Keys with defaults become optional (`T | undefined`)
 * - Required keys are mandatory (`T`)
 */
export type CallerParams<T extends InputParametersRecord> =
// optional keys (those with defaults)
    { [K in keyof T as T[K] extends { _hasDefault: true } ? K : never]?: InferParamType<T[K]> } &
    // required keys (no defaults)
    { [K in keyof T as T[K] extends { _hasDefault: true } ? never : K]-?: InferParamType<T[K]> };
// Keys that are both optional-at-callsite AND exist on CallerParams<Inputs>
type KeysWithDefaults<Inputs extends InputParametersRecord> =
    Extract<OptionalKeysAtCallsite<Inputs>, keyof CallerParams<Inputs>>;

// The defaults record: exactly those keys, with CallerParams value types
export type DefaultsOfInputs<Inputs extends InputParametersRecord> =
    Pick<CallerParams<Inputs>, KeysWithDefaults<Inputs>>;

export function buildDefaultsObject(inputs: Record<string, InputParamDef<any, false>>): Record<string, any> {
    const out: Record<string, any> = {};
    for (const [k, def] of Object.entries(inputs)) {
        // We treat a param as "optional-at-callsite" if its value type advertises `_hasDefault: true`.
        // At runtime, we try to read either `defaultValue` or `defaultFrom`.
        if (def && typeof def === "object" && def._hasDefault === true) {
            if ("defaultValue" in def && def.defaultValue !== undefined) {
                out[k] = def.defaultValue;
            } else if ("defaultFrom" in def && def.defaultFrom !== undefined) {
                out[k] = def.defaultFrom;
            }
            // If neither is present, we simply omit the key.
        }
    }
    return out;
}

// ---- Type helpers (only the payload computation changed) -------------------

/** First parameter object type of callback.register (or never). */
type RegisterParamShape<CB> =
    CB extends { register: (arg: infer A, ...rest: any[]) => any }
        ? (A extends object ? A : never)
        : never;

/** All keys (required + optional) the register consumes, intersected with T. */
type KeysOfRegister<T extends PlainObject, CB> =
    Extract<keyof UnwrapSerialize<T>, keyof RegisterParamShape<CB>>;

/** Optional keys in a record type R (reuses your IsOptionalKey). */
type OptionalKeys<R> = R extends object
    ? { [K in keyof R]-?: IsOptionalKey<R, K> extends true ? K : never }[keyof R]
    : never;

type _R<CB> = RegisterParamShape<CB>;
type _AllKeys<T extends PlainObject, CB> = KeysOfRegister<T, CB>;
type _OptKeys<T extends PlainObject, CB> = Extract<_AllKeys<T, CB>, OptionalKeys<_R<CB>>>;
type _ReqKeys<T extends PlainObject, CB> = Exclude<_AllKeys<T, CB>, _OptKeys<T, CB>>;

/** Collapse BaseExpression<T> | T → T (distributes over unions). */
type PayloadOf<V> = V extends BaseExpression<infer U, any> ? U : V;

/** Apply your helper to drop `| undefined` from the inner payload. */
type CleanPayload<V> = StripUndefined<PayloadOf<V>>;

/** Helper type for serialized expressions similar to AllowSerializedAggregateOrPrimitiveExpressionOrLiteral */
type AllowExpressionIncludingSerialized<T extends PlainObject, C extends ExpressionType = ExpressionType> =
// Handle both aggregate T and already-serialized T to ensure we return a union of BaseExpression<U> | BaseExpression<Serialized<U>>
    T extends Serialized<infer U>
        ? U extends AggregateType
            ? BaseExpression<Serialized<U>, C>
            : BaseExpression<T, C>
        : T extends AggregateType
            ? BaseExpression<Serialized<T>, C>
            : BaseExpression<T, C>;

/** Final, flattened return type: payloads come from register, undefined stripped. */
type SelectedExprRecord<T extends Record<string, any>, CB> =
    { [K in _ReqKeys<T, CB>]: AllowExpressionIncludingSerialized<CleanPayload<_R<CB>[K]>, any> } &
    { [K in _OptKeys<T, CB>]?: AllowExpressionIncludingSerialized<CleanPayload<_R<CB>[K]>, any> };


// === Utilities: required/optional key detection ===
// type RequiredKeys<T> = {
//     [K in keyof T]-?: {} extends Pick<T, K> ? never : K
// }[keyof T];
//
// type OptionalKeys<T> = Exclude<keyof T, RequiredKeys<T>>;

// === Your existing set-equality checker for the keys tuple ===
type KeyListMatches<
    R extends Record<PropertyKey, unknown>,
    K extends readonly PropertyKey[]
> =
    Exclude<keyof R, K[number]> extends never
        ? Exclude<K[number], keyof R> extends never
            ? K
            : ["extra keys in argument", Exclude<K[number], keyof R>]
        : ["missing keys from argument", Exclude<keyof R, K[number]>];

// === New: Guard that enforces "all optional keys of R are present in CB.defaultKeys" ===
// - If R has no optional keys, it's OK even if CB.defaultKeys is omitted.
// - If R has optional keys, CB.defaultKeys must include all of them (compile-time).
type OptionalKeysGuard<
    R extends Record<PropertyKey, unknown>,
    D extends Partial<R>,
    CB extends {
        defaults: D;
        defaultKeys?: readonly (Extract<keyof D, string>)[];
        register: (arg: any, ...rest: any[]) => any;
    }
> =
// If there are no optional keys in R, we're fine.
    OptionalKeys<R> extends never
        ? unknown
        : NonNullable<CB["defaultKeys"]> extends readonly any[]
            ? Exclude<
                OptionalKeys<R>,
                // defaultKeys elements are subset of D's keys; D ⊆ R, so intersect with R to be clean
                Extract<NonNullable<CB["defaultKeys"]>[number], keyof R>
            > extends never
                ? unknown
                : ["optional keys must appear in CB.defaultKeys",
                    Exclude<
                        OptionalKeys<R>,
                        Extract<NonNullable<CB["defaultKeys"]>[number], keyof R>
                    >
                ]
            : ["CB.defaultKeys is required for optional keys", OptionalKeys<R>];

export function selectInputsFieldsAsExpressionRecord<
    T extends Record<PropertyKey, PlainObject>,
    D extends Record<string, any>,
    CB extends {
        defaults: D;
        defaultKeys?: readonly (Extract<keyof D, string>)[];
        register: (arg: any, ...rest: any[]) => any;
        parameterKeys?: readonly string[];
    },
    K extends readonly (keyof T)[]
>(
    P: BaseExpression<T>,
    c: CB,
    expressionsKeys: KeyListMatches<T, K>
): SelectedExprRecord<T, CB> {
    const out: any = {};
    for (const k of expressionsKeys) {
        const dh = (c.defaults as any)[k];

        if (dh && typeof dh === "object" && "expression" in dh) {
            out[k] = expr.dig(P as any, [k as any], "");
        } else {
            out[k] = expr.jsonPathStrict(expr.serialize(P), k as any);
        }
    }
    return out as SelectedExprRecord<T, CB>;
}

function autoDeserializedParam<T extends PlainObject>(
    source: ParameterSource,
    def?: InputParamDef<T, any>
) {
    return new FromParameterExpression(source, def);
}

function workflowParam<T extends PlainObject>(
    name: string,
    def?: InputParamDef<T, any>
) {
    return autoDeserializedParam({kind: "workflow", parameterName: name}, def);
}

function inputParam<T extends PlainObject>(
    name: string,
    def: InputParamDef<T, any>
) {
    return autoDeserializedParam({kind: "input", parameterName: name}, def);
}

/**
 * Convenience: turn template input param defs into expression leaves.
 * NOTE: These are *runtime* constructors of expression nodes, but remain
 * shape-preserving and side-effect free. Circular import with expression.ts
 * is intentional and safe as long as you don't execute these during module
 * initialization in a way that depends on expression.ts having finished
 * evaluating. Typical usage is inside builders/factories.
 */
export function templateInputParametersAsExpressions<WP extends InputParametersRecord>(params: WP): {
    [K in keyof WP]: WP[K] extends InputParamDef<infer T, any>
        ? [T] extends [PlainObject]
            ? [T] extends [null | undefined]
                ? never
                : ReturnType<typeof inputParam<T>>
            : never
        : never
} {
    const out: any = {};
    for (const key of Object.keys(params)) {
        out[key] = inputParam(key, params[key]);
    }
    return out;
}

export function workflowParametersAsExpressions<WP extends InputParametersRecord>(params: WP): {
    [K in keyof WP]: WP[K] extends InputParamDef<infer T, any>
        ? [T] extends [PlainObject]
            ? [T] extends [null | undefined]
                ? never
                : ReturnType<typeof workflowParam<T>>
            : never
        : never
} {
    const out: any = {};
    for (const key of Object.keys(params)) {
        out[key] = workflowParam(key, params[key]);
    }
    return out;
}
