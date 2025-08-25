import { InputParamDef, OutputParamDef } from "@/schemas/parameterSchemas";
import {DeepWiden, PlainObject} from "@/schemas/plainObject";

export type ExpressionType = "govaluate" | "template";

export abstract class BaseExpression<T extends PlainObject, C extends ExpressionType = ExpressionType> {
    readonly _resultType!: T; // phantom only
    readonly _complexity!: C; // phantom only
    constructor(public readonly kind: string) {}
}

export type SimpleExpression<T extends PlainObject>   = BaseExpression<T, "govaluate">;
export type TemplateExpression<T extends PlainObject> = BaseExpression<T, "template">;
export type AnyExpression<T extends PlainObject>      = BaseExpression<T, ExpressionType>;

/** Brand combinators to help generate expression types for compound expressions */
type ExprC<E> = E extends BaseExpression<any, infer C> ? C : never;
type WidenComplexity2<A extends ExpressionType, B extends ExpressionType> =
    A extends "template" ? "template" :
        B extends "template" ? "template" : "govaluate";
type WidenComplexity3 <A extends ExpressionType, B extends ExpressionType, C extends ExpressionType> = WidenComplexity2<WidenComplexity2<A,B>, C>;
type WidenExpressionComplexity2<L, R> = WidenComplexity2<ExprC<L>, ExprC<R>>;
type WidenExpressionComplexity3<A, B, C> = WidenComplexity3<ExprC<A>, ExprC<B>, ExprC<C>>;
/**
 *  Helper used to pull a value from one expression so that it can be fixed in other counterparts
 * (e.g. ternary)
 * */
type ValueOf<E> = E extends AnyExpression<infer U> ? U : never;

/** ───────────────────────────────────────────────────────────────────────────
 *  Leaves
 *  ───────────────────────────────────────────────────────────────────────── */
type ParameterSource =
    | { kind: "workflow",   parameterName: string }
    | { kind: "input",      parameterName: string }
    | { kind: "step_output", stepName: string, parameterName: string }
    | { kind: "task_output", taskName: string, parameterName: string };

export class LiteralExpression<T extends PlainObject>
    extends BaseExpression<T, "govaluate"> {
    constructor(public readonly value: T) { super("literal"); }
}

export class FromParameterExpression<T extends PlainObject>
    extends BaseExpression<T, "govaluate"> {
    constructor(
        public readonly source: ParameterSource,
        public readonly paramDef?: InputParamDef<T, any> | OutputParamDef<T>
    ) { super("parameter"); }
}

export class FromConfigMapExpression<T extends PlainObject>
    extends BaseExpression<T, "template"> {
    constructor(public readonly configMapName: string, public readonly key: string) {
        super("configmap");
    }
}

/** ───────────────────────────────────────────────────────────────────────────
 *  Derived nodes (single class; brand inferred from children)
 *  ───────────────────────────────────────────────────────────────────────── */
export class AsStringExpression<
    E extends AnyExpression<any>,
    C extends ExpressionType = ExprC<E>
> extends BaseExpression<string, C> {
    constructor(public readonly source: E) { super("as_string"); }
}

type PathValue<T, P extends string> =
    P extends keyof T ? T[P]
        : P extends `${infer K}.${infer Rest}` ? (K extends keyof T ? PathValue<T[K], Rest> : never)
            : P extends `[${infer _Index}]${infer Rest}`
                ? T extends readonly (infer U)[] ? PathValue<U, Rest extends "" ? never : Rest extends `.${infer R}` ? R : never> : never
                : never;

export class PathExpression<
    E extends AnyExpression<any>,
    TSource extends PlainObject = E extends AnyExpression<infer S> ? S : never,
    TPath extends string = string,
    TResult extends PlainObject = PathValue<TSource, TPath> & PlainObject
> extends BaseExpression<TResult, "template"> {
    constructor(public readonly source: E, public readonly path: TPath) { super("path"); }
}

export class ConcatExpression<
    ES extends readonly AnyExpression<string>[]
> extends BaseExpression<string, "template"> {
    constructor(public readonly expressions: ES, public readonly separator?: string) {
        super("concat");
    }
}

export class TernaryExpression<
    OutT extends PlainObject,
    B extends AnyExpression<boolean>,
    L extends AnyExpression<OutT>,
    R extends AnyExpression<OutT>,
    C extends ExpressionType = WidenExpressionComplexity3<B, L, R>
> extends BaseExpression<OutT, C> {
    constructor(
        public readonly condition: B,
        public readonly whenTrue:  L,
        public readonly whenFalse: R
    ) { super("ternary"); }
}

export class ArithmeticExpression<
    L extends AnyExpression<number>,
    R extends AnyExpression<number>,
    C extends ExpressionType = WidenExpressionComplexity2<L, R>
> extends BaseExpression<number, C> {
    constructor(
        public readonly operator: "+" | "-" | "*" | "/" | "%",
        public readonly left: L,
        public readonly right: R
    ) { super("arithmetic"); }
}

export class ComparisonExpression<
    L extends AnyExpression<number | string>,
    R extends AnyExpression<number | string>,
    C extends ExpressionType = WidenExpressionComplexity2<L, R>
> extends BaseExpression<boolean, C> {
    constructor(
        public readonly operator: "==" | "!=" | "<" | ">" | "<=" | ">=",
        public readonly left: L,
        public readonly right: R
    ) { super("comparison"); }
}

export class ArrayLengthExpression<
    E extends AnyExpression<any[]>,
    C extends ExpressionType = ExprC<E>
> extends BaseExpression<number, C> {
    constructor(public readonly array: E) { super("array_length"); }
}

export class ArrayIndexExpression<
    A extends AnyExpression<any[]>,
    I extends AnyExpression<number>,
    Elem extends PlainObject =
        A extends AnyExpression<infer Arr>
            ? Arr extends (infer U)[] ? U & PlainObject : never
            : never,
    C extends ExpressionType = WidenExpressionComplexity2<A, I>
> extends BaseExpression<Elem, C> {
    constructor(public readonly array: A, public readonly index: I) { super("array_index"); }
}

export const literal = <T extends PlainObject>(v: T): SimpleExpression<DeepWiden<T>> =>
    new LiteralExpression<DeepWiden<T>>(v as DeepWiden<T>);

export const asString = <E extends AnyExpression<any>>(e: E) =>
    new AsStringExpression(e);

export const path = <
    E extends AnyExpression<any>,
    TPath extends string
>(source: E, p: TPath) =>
    new PathExpression(source, p);

export const concat = <ES extends readonly AnyExpression<string>[]>(...es: ES) =>
    new ConcatExpression(es);

export const concatWith = <ES extends readonly AnyExpression<string>[]>(sep: string, ...es: ES) =>
    new ConcatExpression(es, sep);

export const ternary = <
    B extends AnyExpression<boolean>,
    L extends AnyExpression<OutT>,
    R extends AnyExpression<OutT>,
    OutT extends PlainObject = ValueOf<L>
>(cond: B, whenTrue: L, whenFalse: R) =>
    new TernaryExpression<OutT, B, L, R>(cond, whenTrue, whenFalse);

// Would be nice to drop the specific ones, but not worth it at the moment
export function equals<
    L extends AnyExpression<string>,
    R extends AnyExpression<DeepWiden<ValueOf<L>>>
>(l: L, r: R): BaseExpression<boolean, WidenExpressionComplexity2<L, R>>;
export function equals<
    L extends AnyExpression<number>,
    R extends AnyExpression<DeepWiden<ValueOf<L>>>
>(l: L, r: R): BaseExpression<boolean, WidenExpressionComplexity2<L, R>>;
export function equals<
    L extends AnyExpression<string | number>,
    R extends AnyExpression<string | number>
>(l: L, r: R): BaseExpression<boolean, WidenExpressionComplexity2<L, R>> {
    // Thread L, R, and brand into the class so C narrows to "govaluate" when possible.
    return new ComparisonExpression<L, R, WidenExpressionComplexity2<L, R>>("==", l, r);
}

export const lessThan = <
    L extends AnyExpression<number>,
    R extends AnyExpression<number>
>(l: L, r: R): BaseExpression<boolean, WidenExpressionComplexity2<L, R>> =>
    new ComparisonExpression<L, R, WidenExpressionComplexity2<L, R>>("<", l, r);

export const greaterThan = <
    L extends AnyExpression<number>,
    R extends AnyExpression<number>
>(l: L, r: R): BaseExpression<boolean, WidenExpressionComplexity2<L, R>> =>
    new ComparisonExpression<L, R, WidenExpressionComplexity2<L, R>>(">", l, r);

export const add = <
    L extends AnyExpression<number>,
    R extends AnyExpression<number>
>(l: L, r: R): BaseExpression<number, WidenExpressionComplexity2<L, R>> =>
    new ArithmeticExpression<L, R, WidenExpressionComplexity2<L, R>>("+", l, r);

export const subtract = <
    L extends AnyExpression<number>,
    R extends AnyExpression<number>
>(l: L, r: R): BaseExpression<number, WidenExpressionComplexity2<L, R>> =>
    new ArithmeticExpression<L, R, WidenExpressionComplexity2<L, R>>("-", l, r);

export const multiply = <
    L extends AnyExpression<number>,
    R extends AnyExpression<number>
>(l: L, r: R): BaseExpression<number, WidenExpressionComplexity2<L, R>> =>
    new ArithmeticExpression<L, R, WidenExpressionComplexity2<L, R>>("*", l, r);

export const divide = <
    L extends AnyExpression<number>,
    R extends AnyExpression<number>
>(l: L, r: R): BaseExpression<number, WidenExpressionComplexity2<L, R>> =>
    new ArithmeticExpression<L, R, WidenExpressionComplexity2<L, R>>("/", l, r);

export const length = <E extends AnyExpression<any[]>>(arr: E) =>
    new ArrayLengthExpression(arr);

export const index = <
    A extends AnyExpression<any[]>,
    I extends AnyExpression<number>
>(arr: A, i: I) => new ArrayIndexExpression(arr, i);

/** ───────────────────────────────────────────────────────────────────────────
 *  Helpers to create convenience records of parameter expressions for pulling them into expression slots
 *  ───────────────────────────────────────────────────────────────────────── */
export const workflowParam = <T extends PlainObject>(
    name: string,
    def?: InputParamDef<T, any>
) =>
    new FromParameterExpression({ kind: "workflow", parameterName: name }, def);

export const inputParam = <T extends PlainObject>(
    name: string,
    def: InputParamDef<T, any>
) =>
    new FromParameterExpression({ kind: "input", parameterName: name }, def);

export const stepOutput = <T extends PlainObject>(
    stepName: string,
    parameterName: string,
    def?: OutputParamDef<T>
): SimpleExpression<DeepWiden<T>> =>
    new FromParameterExpression<DeepWiden<T>>({ kind: "step_output", stepName, parameterName }, def as any);

export const taskOutput = <T extends PlainObject>(
    taskName: string,
    parameterName: string,
    def?: OutputParamDef<T>
): SimpleExpression<DeepWiden<T>> =>
    new FromParameterExpression<DeepWiden<T>>({ kind: "task_output", taskName, parameterName }, def as any);

export const configMap = <T extends PlainObject>(name: string, key: string): TemplateExpression<T> =>
    new FromConfigMapExpression(name, key);
