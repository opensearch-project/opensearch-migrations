declare const __missingBrand: unique symbol;

export const MISSING_FIELD = Symbol("missingField");
export type MissingField = typeof MISSING_FIELD;

export class Serialized<OutputT> {
    readonly _resultType!: OutputT; // phantom only for typing
    public constructor(public readonly : string) {}
}

// PlainObject type system for constraining values to serializable plain objects
export type Primitive = string | number | boolean | MissingField;
export type NonSerializedPlainObject = Primitive | AggregateType;
export type AggregateType = readonly PlainObject[] | { [key: string]: PlainObject };
export type PlainObject = Primitive | AggregateType | Serialized<PlainObject>;

export type WidenPrimitive<T> =
    T extends string ? string :
        T extends number ? number :
            T extends boolean ? boolean :
                T extends null ? null :
                    T;
export type IsUnion<T, U = T> = T extends any ? [U] extends [T] ? false : true : never;

export type DeepWiden<T> =
    IsUnion<T> extends true
        ? T  // Preserve all unions (primitives, objects, arrays, mixed)
        : T extends readonly (infer U)[] ? Array<DeepWiden<U>> :
            T extends (infer U)[] ? Array<DeepWiden<U>> :
                T extends object ? { -readonly [K in keyof T]: DeepWiden<T[K]> } :
                    WidenPrimitive<T>;

/**
 * Runtime validation helper to check if a value is a PlainObject
 * Only allows primitives, plain arrays, and plain objects (no custom prototypes, functions, etc.)
 */
export function isPlainObject(value: any): value is PlainObject {
    if (value === null || typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
        return true;
    }

    if (Array.isArray(value)) {
        return value.every(isPlainObject);
    }

    if (typeof value === 'object' && value !== null) {
        // Only allow plain objects (created with {} or Object.create(null))
        const proto = Object.getPrototypeOf(value);
        if (proto !== Object.prototype && proto !== null) {
            return false;
        }
        return Object.values(value).every(isPlainObject);
    }

    return false;
}

/**
 * Runtime assertion helper that throws if value is not a PlainObject
 */
export function assertPlainObject(value: any, context?: string): asserts value is PlainObject {
    if (!isPlainObject(value)) {
        const contextMsg = context ? ` in ${context}` : '';
        throw new Error(`Value must be a PlainObject (primitives, plain arrays, or plain objects only)${contextMsg}`);
    }
}
