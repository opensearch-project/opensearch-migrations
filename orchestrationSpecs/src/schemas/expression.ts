import { ZodType } from 'zod';
import {InputParamDef, OutputParamDef} from "@/schemas/parameterSchemas";

// Base Expression class
export abstract class Expression<T> {
    readonly _resultType!: T; // Phantom type for compile-time checking
    constructor(public readonly kind: string) {}
}

export class LiteralExpression<T> extends Expression<T> {
    constructor(public readonly value: T) {
        super('literal');
    }
}

type ParameterSource =
    | { kind: 'workflow', parameterName: string }
    | { kind: 'input', parameterName: string }
    | { kind: 'step_output', stepName: string, parameterName: string }
    | { kind: 'task_output', taskName: string, parameterName: string };

export class FromParameterExpression<T> extends Expression<T> {
    constructor(
        public readonly source: ParameterSource,
        public readonly paramDef?: InputParamDef<T, any> | OutputParamDef<T>
    ) {
        super('parameter');
    }
}

export class FromConfigMapExpression<T> extends Expression<T> {
    constructor(
        public readonly configMapName: string,
        public readonly key: string
    ) {
        super('configmap');
    }
}

type PathValue<T, P extends string> = P extends keyof T
    ? T[P]
    : P extends `${infer K}.${infer Rest}`
        ? K extends keyof T
            ? PathValue<T[K], Rest>
            : never
        : P extends `[${infer Index}]${infer Rest}`
            ? T extends readonly (infer U)[]
                ? PathValue<U, Rest extends '' ? never : Rest extends `.${infer R}` ? R : never>
                : never
            : never;

export class PathExpression<TSource, TResult> extends Expression<TResult> {
    constructor(
        public readonly source: Expression<TSource>,
        public readonly path: string
    ) {
        super('path');
    }
}

export class ConcatExpression extends Expression<string> {
    constructor(
        public readonly expressions: readonly Expression<string>[],
        public readonly separator?: string
    ) {
        super('concat');
    }
}

export class TernaryExpression<T> extends Expression<T> {
    constructor(
        public readonly condition: Expression<boolean>,
        public readonly whenTrue: Expression<T>,
        public readonly whenFalse: Expression<T>
    ) {
        super('ternary');
    }
}

export class ArithmeticExpression extends Expression<number> {
    constructor(
        public readonly operator: '+' | '-' | '*' | '/' | '%',
        public readonly left: Expression<number>,
        public readonly right: Expression<number>
    ) {
        super('arithmetic');
    }
}

export class ComparisonExpression extends Expression<boolean> {
    constructor(
        public readonly operator: '==' | '!=' | '<' | '>' | '<=' | '>=',
        public readonly left: Expression<number | string>,
        public readonly right: Expression<number | string>
    ) {
        super('comparison');
    }
}

export class ArrayLengthExpression<T> extends Expression<number> {
    constructor(public readonly array: Expression<readonly T[]>) {
        super('array_length');
    }
}

export class ArrayIndexExpression<T> extends Expression<T> {
    constructor(
        public readonly array: Expression<readonly T[]>,
        public readonly index: Expression<number>
    ) {
        super('array_index');
    }
}

// =============================================================================
// BUILDER FUNCTIONS
// =============================================================================

// Literals
export const literal = <T>(value: T) => new LiteralExpression(value);

// =============================================================================
// PARAMETER HELPERS (Type-safe parameter references)
// =============================================================================

export const workflowParam = <T>(
    name: string,
    paramDef?: InputParamDef<T, any>
) => new FromParameterExpression<T>(
    { kind: 'workflow', parameterName: name },
    paramDef
);

export const inputParam = <T>(
    name: string,
    paramDef?: InputParamDef<T, any>
) => new FromParameterExpression<T>(
    { kind: 'input', parameterName: name },
    paramDef
);

export const stepOutput = <T>(
    stepName: string,
    parameterName: string,
    paramDef?: OutputParamDef<T>
) => new FromParameterExpression<T>(
    { kind: 'step_output', stepName, parameterName },
    paramDef
);

export const taskOutput = <T>(
    taskName: string,
    parameterName: string,
    paramDef?: OutputParamDef<T>
) => new FromParameterExpression<T>(
    { kind: 'task_output', taskName, parameterName },
    paramDef
);

// Helper to create parameter references from parameter maps
export type ParamMap<T extends Record<string, InputParamDef<any, any> | OutputParamDef<any>>> = {
    [K in keyof T]: T[K] extends InputParamDef<infer U, any>
        ? Expression<U>
        : T[K] extends OutputParamDef<infer U>
            ? Expression<U>
            : never;
};

// Create workflow parameter references from a parameter definition map
export const workflowParams = <T extends Record<string, InputParamDef<any, any>>>(
    paramDefs: T
): ParamMap<T> => {
    const result = {} as ParamMap<T>;
    for (const [name, def] of Object.entries(paramDefs)) {
        (result as any)[name] = workflowParam(name, def);
    }
    return result;
};

// Create input parameter references from a parameter definition map
export const inputParams = <T extends Record<string, InputParamDef<any, any>>>(
    paramDefs: T
): ParamMap<T> => {
    const result = {} as ParamMap<T>;
    for (const [name, def] of Object.entries(paramDefs)) {
        (result as any)[name] = inputParam(name, def);
    }
    return result;
};

export const stepOutputs = <T extends Record<string, OutputParamDef<any>>>(
    stepName: string,
    paramDefs: T
): ParamMap<T> => {
    const result = {} as ParamMap<T>;
    for (const [name, def] of Object.entries(paramDefs)) {
        (result as any)[name] = stepOutput(stepName, name, def);
    }
    return result;
};

export const taskOutputs = <T extends Record<string, OutputParamDef<any>>>(
    taskName: string,
    paramDefs: T
): ParamMap<T> => {
    const result = {} as ParamMap<T>;
    for (const [name, def] of Object.entries(paramDefs)) {
        (result as any)[name] = taskOutput(taskName, name, def);
    }
    return result;
};

export const configMap = <T>(name: string, key: string) => new FromConfigMapExpression<T>(name, key);

// Path access with type inference
export const path = <TSource, TPath extends string>(
    source: Expression<TSource>,
    pathStr: TPath
) => new PathExpression<TSource, PathValue<TSource, TPath>>(source, pathStr);

export const concat = (...expressions: Expression<string>[]) =>
    new ConcatExpression(expressions);

export const concatWith = (separator: string, ...expressions: Expression<string>[]) =>
    new ConcatExpression(expressions, separator);

export const ternary = <T>(
    condition: Expression<boolean>,
    whenTrue: Expression<T>,
    whenFalse: Expression<T>
) => new TernaryExpression(condition, whenTrue, whenFalse);

export const add = (left: Expression<number>, right: Expression<number>) =>
    new ArithmeticExpression('+', left, right);

export const subtract = (left: Expression<number>, right: Expression<number>) =>
    new ArithmeticExpression('-', left, right);

export const multiply = (left: Expression<number>, right: Expression<number>) =>
    new ArithmeticExpression('*', left, right);

export const divide = (left: Expression<number>, right: Expression<number>) =>
    new ArithmeticExpression('/', left, right);

export const equals = <T extends string | number>(
    left: Expression<T>,
    right: Expression<T>
) => new ComparisonExpression('==', left as Expression<string | number>, right as Expression<string | number>);

export const lessThan = (left: Expression<number>, right: Expression<number>) =>
    new ComparisonExpression('<', left, right);

export const greaterThan = (left: Expression<number>, right: Expression<number>) =>
    new ComparisonExpression('>', left, right);

export const length = <T>(array: Expression<readonly T[]>) =>
    new ArrayLengthExpression(array);

export const index = <T>(array: Expression<readonly T[]>, idx: Expression<number>) =>
    new ArrayIndexExpression(array, idx);

// =============================================================================
// PARAMETER VALIDATION HELPERS
// =============================================================================

// Helper to validate parameter definitions match expression types
export const validateParameterExpression = <T>(
    expr: FromParameterExpression<T>,
    expectedType: ZodType<T>
): boolean => {
    if (!expr.paramDef) return false;

    // In a real implementation, you might want to compare the Zod schemas
    // This is a placeholder for that validation logic
    return expr.paramDef.type === expectedType;
};

// Helper to get parameter metadata for Argo workflow generation
export const getParameterMetadata = <T>(expr: FromParameterExpression<T>) => ({
    source: expr.source,
    schema: expr.paramDef?.type,
    description: expr.paramDef?.description,
    hasDefault: 'defaultValue' in (expr.paramDef || {})
});


// =============================================================================
// FLUENT API EXTENSION (Optional)
// =============================================================================

// // You can even add methods to the base Expression class for a fluent API
// declare module '@/expressions' {
//     namespace Expression {
//         interface Expression<T> {
//             // Conditional chaining
//             when<U>(condition: Expression<boolean>, whenTrue: Expression<U>, whenFalse: Expression<U>): Expression<U>;
//
//             // Only available on string expressions
//             concat(...others: Expression<string>[]): T extends string ? Expression<string> : never;
//
//             // Only available on array expressions
//             length(): T extends readonly any[] ? Expression<number> : never;
//             at(index: Expression<number>): T extends readonly (infer U)[] ? Expression<U> : never;
//         }
//     }
// }
//
// // Extend the base class with fluent methods
// Expression.prototype.when = function<T, U>(
//     this: Expression<T>,
//     condition: Expression<boolean>,
//     whenTrue: Expression<U>,
//     whenFalse: Expression<U>
// ) {
//     return ternary(condition, whenTrue, whenFalse);
// };

// Usage example with fluent API:
// const result = param<User>('user')
//   .path('name')
//   .concat(literal(' is '), param<User>('user').path('age').toString(), literal(' years old'));