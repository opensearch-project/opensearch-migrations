export type NoBareTemplateString<T> =
    T extends string
        ? T extends `${string}{{${string}` | `${string}}}${string}` ? never : T
        : T;

function hasBareTemplateString(value: string) {
    let start = value.indexOf("{{");
    while (start !== -1) {
        for (let i = start + 2; i < value.length; i++) {
            if (value[i] !== "}") {
                continue;
            }
            if (value[i + 1] === "}") {
                return true;
            }
            break;
        }
        start = value.indexOf("{{", start + 2);
    }
    return false;
}

export function assertNoBareTemplateString(value: unknown, context: string) {
    if (typeof value === "string" && hasBareTemplateString(value)) {
        throw new Error(
            `${context} received raw Argo template syntax '${value}'. ` +
            "Use expression helpers instead of embedding bare {{...}} markers."
        );
    }
}
