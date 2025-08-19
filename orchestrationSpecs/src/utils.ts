export type TypescriptError<Message extends string> = {
    readonly __error: Message;
    readonly __never: never;
};

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

export function toEnvVarName(str: string): string {
    return str
        .replace(/([a-z])([A-Z])/g, '$1_$2')
        .replace(/([A-Z])([A-Z][a-z])/g, '$1_$2')
        .toUpperCase();
}
