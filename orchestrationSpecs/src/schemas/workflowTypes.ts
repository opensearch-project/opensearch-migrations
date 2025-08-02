// Internal types for workflow schema implementation
export type Scope = Record<string, any>;
export type ScopeFn<S extends Scope, ADDITIONS extends Scope> = (scope: Readonly<S>) => ADDITIONS;

// Internal type for scope extension - used internally by builder methods
export type ExtendScope<S extends Scope, ADDITIONS extends Scope> = S & ADDITIONS;

export type TemplateSigEntry<T extends { inputs: any }> = {
    input: T["inputs"];
    output?: T extends { outputs: infer O } ? O : never;
};

// Helper types for uniqueness checking that don't interfere with IntelliSense
export type EnsureUnique<Name extends string, ExistingKeys> = Name extends keyof ExistingKeys ? never : Name;
export type ValidTemplateName<Name extends string, TemplateSigScope> = EnsureUnique<Name, TemplateSigScope>;
export type ValidParamName<Name extends string, InputParamsScope> = EnsureUnique<Name, InputParamsScope>;

// Error message types for better developer experience
export type DuplicateTemplateError<Name extends string> = `ERROR: Template name '${Name}' already exists in this workflow. Please choose a unique name.` & { __brand: 'DuplicateTemplateError' };
export type DuplicateParamError<Name extends string> = `ERROR: Parameter name '${Name}' already exists in this template. Please choose a unique name.` & { __brand: 'DuplicateParamError' };
