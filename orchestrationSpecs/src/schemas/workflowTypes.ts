// Internal types for workflow schema implementation
export type Scope = Record<string, any>;
export type ScopeFn<S extends Scope, ADDITIONS extends Scope> = (scope: Readonly<S>) => ADDITIONS;

// Internal type for scope extension - used internally by builder methods
export type ExtendScope<S extends Scope, ADDITIONS extends Scope> = S & ADDITIONS;

export type TemplateSigEntry<T extends { inputs: any }> = {
    input: T["inputs"];
    output?: T extends { outputs: infer O } ? O : never;
};
