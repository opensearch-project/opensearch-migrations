
type GetterReturnTypes<T> = {
    [K in keyof T as T[K] extends () => any ? K : never]: ReturnType<Extract<T[K], () => any>>;
};

export function sameMatchingItems<T>(
    obj: Object,
    predicate: (getterName: string, val: unknown) => val is T): Record<string, T> //GetterReturnTypes<this>
{
    const collected: Partial<Record<string, any>> = {};
    const proto = Object.getPrototypeOf(obj);
    for (const key of Object.getOwnPropertyNames(proto)) {
        if (Object.getOwnPropertyDescriptor(proto, key)?.get) { // only check getters
            const val = (obj as any)[key];
            if (predicate(key, val)) {
                collected[key] = val;
            }
        }
    }
    return collected;// as GetterReturnTypes<this>;
}

export function buildRecordFromMatchingItems<T>(
    obj: Object,
    predicate: (getterName: string, val: unknown) => val is T): Record<string, T> //GetterReturnTypes<this>
{
    const collected: Partial<Record<string, any>> = {};
    const proto = Object.getPrototypeOf(obj);
    for (const key of Object.getOwnPropertyNames(proto)) {
        if (Object.getOwnPropertyDescriptor(proto, key)?.get) { // only check getters
            const val = (obj as any)[key];
            if (predicate(key, val)) {
                collected[key] = val;
            }
        }
    }
    return collected;// as GetterReturnTypes<this>;
}

export function getAlwaysMatchPredicate<T>(): (getterName: string, val: unknown) => val is T {
    return (getterName: string, val: unknown): val is T => {
        return true;
    };
}
/// Helper class to facilitate defining classes with getter members that match a certain predicate
/// and can be accumulated together.  This is useful so that we can follow a convention of defining
/// accessors and then automatically pulling them together into things like template or task collections
export abstract class AggregatingScope<T> {
}

export abstract class KeyMatchingAggregateScope<T> extends AggregatingScope<T> {
    protected abstract readonly keyRegex: RegExp;

    protected predicate = (getterName: string, val: unknown): val is T => {
        return this.keyRegex.test(getterName);
    };
}

/// Actually will put every get field into a record with its name, unless the field started with '_'
export abstract class EverythingToRecordScope<T> extends KeyMatchingAggregateScope<T> {
    protected readonly keyRegex: RegExp = /^[^_].*/;
}
