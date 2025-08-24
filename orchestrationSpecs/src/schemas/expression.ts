import { ZodType } from 'zod';
import {InputParamDef, OutputParamDef} from "@/schemas/parameterSchemas";
import {PlainObject} from "@/schemas/plainObject";

// Base Expression class
export abstract class BaseExpression<T extends PlainObject> {
    readonly _resultType!: T; // Phantom type for compile-time checking
    constructor(public readonly kind: string, public readonly isSimple: boolean) {}
}

export abstract class SimpleExpression<T extends PlainObject> extends BaseExpression<T>{
    protected readonly __brand_simple!: void;  // <- brand
    constructor(public readonly kind: string) {
        super(kind, true);
    }
}

export abstract class TemplateExpression<T extends PlainObject> extends BaseExpression<T>{
    protected readonly __brand_complex!: void;  // <- brand
    constructor(public readonly kind: string) {
        super(kind, false);
    }
}

export class AsStringExpression<T extends PlainObject> extends TemplateExpression<string> {
    constructor(public readonly source: TemplateExpression<T>) {
        super('as_string');
    }
}

export class LiteralExpression<T extends PlainObject> extends SimpleExpression<T> {
    constructor(public readonly value: T) {
        super('literal');
    }
}

type ParameterSource =
    | { kind: 'workflow', parameterName: string }
    | { kind: 'input', parameterName: string }
    | { kind: 'step_output', stepName: string, parameterName: string }
    | { kind: 'task_output', taskName: string, parameterName: string };

export class FromParameterExpression<T extends PlainObject> extends SimpleExpression<T> {
    constructor(
        public readonly source: ParameterSource,
        public readonly paramDef?: InputParamDef<T, any> | OutputParamDef<T>
    ) {
        super('parameter');
    }
}

export class FromConfigMapExpression<T extends PlainObject> extends TemplateExpression<T> {
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

export class PathExpression<TSource extends PlainObject, TResult extends PlainObject> extends TemplateExpression<TResult> {
    constructor(
        public readonly source: TemplateExpression<TSource>,
        public readonly path: string
    ) {
        super('path');
    }
}

export class ConcatExpression extends TemplateExpression<string> {
    constructor(
        public readonly expressions: readonly TemplateExpression<string>[],
        public readonly separator?: string
    ) {
        super('concat');
    }
}

export class TernaryExpression<T extends PlainObject> extends SimpleExpression<T> {
    constructor(
        public readonly condition: TemplateExpression<boolean>,
        public readonly whenTrue: TemplateExpression<T>,
        public readonly whenFalse: TemplateExpression<T>
    ) {
        super('ternary');
    }
}

export class ArithmeticExpression extends SimpleExpression<number> {
    constructor(
        public readonly operator: '+' | '-' | '*' | '/' | '%',
        public readonly left: TemplateExpression<number>,
        public readonly right: TemplateExpression<number>
    ) {
        super('arithmetic');
    }
}

export class ComparisonExpression extends SimpleExpression<boolean> {
    constructor(
        public readonly operator: '==' | '!=' | '<' | '>' | '<=' | '>=',
        public readonly left: TemplateExpression<number | string>,
        public readonly right: TemplateExpression<number | string>
    ) {
        super('comparison');
    }
}

export class ArrayLengthExpression<T extends PlainObject> extends SimpleExpression<number> {
    constructor(public readonly array: TemplateExpression<T[]>) {
        super('array_length');
    }
}

export class ArrayIndexExpression<T extends PlainObject> extends SimpleExpression<T> {
    constructor(
        public readonly array: TemplateExpression<T[]>,
        public readonly index: TemplateExpression<number>
    ) {
        super('array_index');
    }
}

// =============================================================================
// BUILDER FUNCTIONS
// =============================================================================

// conversion
export const asString = <T extends PlainObject>(expr: TemplateExpression<T>): TemplateExpression<string> =>
    new AsStringExpression(expr);

// Literals
export const literal = <T extends PlainObject>(value: T) => new LiteralExpression(value);

// =============================================================================
// PARAMETER HELPERS (Type-safe parameter references)
// =============================================================================

export const workflowParam = <T extends PlainObject>(
    name: string,
    paramDef?: InputParamDef<T, any>
) => new FromParameterExpression<T>(
    { kind: 'workflow', parameterName: name },
    paramDef
);

export const inputParam = <T extends PlainObject>(
    name: string,
    paramDef: InputParamDef<T, any>
) => new FromParameterExpression<T>(
    { kind: 'input', parameterName: name },
    paramDef
);

export const stepOutput = <T extends PlainObject>(
    stepName: string,
    parameterName: string,
    paramDef?: OutputParamDef<T>
) => new FromParameterExpression<T>(
    { kind: 'step_output', stepName, parameterName },
    paramDef
);

export const taskOutput = <T extends PlainObject>(
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
        ? TemplateExpression<U>
        : T[K] extends OutputParamDef<infer U>
            ? TemplateExpression<U>
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

export const configMap = <T extends PlainObject>(name: string, key: string) => new FromConfigMapExpression<T>(name, key);

// Path access with type inference
export const path = <TSource extends PlainObject, TPath extends string>(
    source: TemplateExpression<TSource>,
    pathStr: TPath
) => new PathExpression<TSource, PathValue<TSource, TPath> & PlainObject>(source, pathStr);

export const concat = (...expressions: TemplateExpression<string>[]) =>
    new ConcatExpression(expressions);

export const concatWith = (separator: string, ...expressions: TemplateExpression<string>[]) =>
    new ConcatExpression(expressions, separator);

export const ternary = <T extends PlainObject>(
    condition: TemplateExpression<boolean>,
    whenTrue: TemplateExpression<T>,
    whenFalse: TemplateExpression<T>
) => new TernaryExpression(condition, whenTrue, whenFalse);

export const add = (left: TemplateExpression<number>, right: TemplateExpression<number>) =>
    new ArithmeticExpression('+', left, right);

export const subtract = (left: TemplateExpression<number>, right: TemplateExpression<number>) =>
    new ArithmeticExpression('-', left, right);

export const multiply = (left: TemplateExpression<number>, right: TemplateExpression<number>) =>
    new ArithmeticExpression('*', left, right);

export const divide = (left: TemplateExpression<number>, right: TemplateExpression<number>) =>
    new ArithmeticExpression('/', left, right);

export const equals = <T extends string | number>(
    left: TemplateExpression<T>,
    right: TemplateExpression<T>
) => new ComparisonExpression('==', left as TemplateExpression<string | number>, right as TemplateExpression<string | number>);

export const lessThan = (left: TemplateExpression<number>, right: TemplateExpression<number>) =>
    new ComparisonExpression('<', left, right);

export const greaterThan = (left: TemplateExpression<number>, right: TemplateExpression<number>) =>
    new ComparisonExpression('>', left, right);

export const length = <T extends PlainObject>(array: TemplateExpression<T[]>) =>
    new ArrayLengthExpression(array);

export const index = <T extends PlainObject>(array: TemplateExpression<T[]>, idx: TemplateExpression<number>) =>
    new ArrayIndexExpression(array, idx);

// =============================================================================
// PARAMETER VALIDATION HELPERS
// =============================================================================

// Helper to validate parameter definitions match expression types
export const validateParameterExpression = <T extends PlainObject>(
    expr: FromParameterExpression<T>,
    expectedType: ZodType<T>
): boolean => {
    if (!expr.paramDef) return false;

    // In a real implementation, you might want to compare the Zod schemas
    // This is a placeholder for that validation logic
    return expr.paramDef.type === expectedType;
};

// Helper to get parameter metadata for Argo workflow generation
export const getParameterMetadata = <T extends PlainObject>(expr: FromParameterExpression<T>) => ({
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
