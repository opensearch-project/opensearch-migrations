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
