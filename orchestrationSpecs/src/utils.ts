export type TypescriptError<Message extends string> = {
    readonly __error: Message;
    readonly __never: never;
};

export function getKeyAndValue<T extends Record<string, any>, K extends keyof T>(
    obj: T,
    key: K
): { key: K; value: T[K] } {
    return { key, value: obj[key] };
}

export function getKeyAndValueClass<T extends Record<string, any>, K extends keyof T>(
    classConstructor: T,
    key: K
): { key: K; value: T[K] } {
    return { key, value: classConstructor[key] };
}

// Helper types to convert union to tuple and count length
type UnionToIntersection<U> = (U extends any ? (k: U) => void : never) extends (k: infer I) => void ? I : never

type UnionToTuple<T> = UnionToIntersection<
    T extends any ? () => T : never
> extends () => infer R
    ? [...UnionToTuple<Exclude<T, R>>, R]
    : []

export type FieldCount<T> = keyof T extends never
    ? 0
    : UnionToTuple<keyof T>['length']
