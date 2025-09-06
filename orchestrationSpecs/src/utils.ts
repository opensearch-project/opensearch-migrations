export type TypescriptError<Message extends string> = {
    readonly __error: Message;
    readonly __never: never;
};

export function toEnvVarName(str: string): string {
    return str
        .replace(/([a-z])([A-Z])/g, '$1_$2')
        .replace(/([A-Z])([A-Z][a-z])/g, '$1_$2')
        .toUpperCase();
}
