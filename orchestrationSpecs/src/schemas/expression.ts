import {InputParamDef, OutputParamDef} from "@/schemas/parameterSchemas";
import {DeepWiden, PlainObject} from "@/schemas/plainObject";


export type ExpressionType = "govaluate" | "template";

export abstract class BaseExpression<T extends PlainObject, C extends ExpressionType = ExpressionType> {
    // Invariant phantom to prevent `BaseExpression<number>` from being assignable to `BaseExpression<string>`
    // private _phantom!: (arg: T) => T;

    readonly _resultType!: T; // phantom only
    readonly _complexity!: C; // phantom only

    constructor(public readonly kind: string) {}
}

export type SimpleExpression<T extends PlainObject>   = BaseExpression<T, "govaluate">;
export type TemplateExpression<T extends PlainObject> = BaseExpression<T, "template">;
export type AnyExpression<T extends PlainObject>      = BaseExpression<T, ExpressionType>; // kept for external callers; not used below

type Scalar = number | string;
type ResultOf<E> = E extends BaseExpression<infer U, any> ? U : never;
type ExprC<E>    = E extends BaseExpression<any, infer C> ? C : never;
export type IsAny<T>    = 0 extends (1 & T) ? true : false;
export type NoAny<T>    =  IsAny<ResultOf<T>> extends true ? never : T;

type WidenComplexity2<A extends ExpressionType, B extends ExpressionType> =
    A extends "template" ? "template" :
        B extends "template" ? "template" : "govaluate";
type WidenComplexity3<A extends ExpressionType, B extends ExpressionType, C extends ExpressionType> = WidenComplexity2<WidenComplexity2<A,B>, C>;
type WidenExpressionComplexity2<L, R> = WidenComplexity2<ExprC<L>, ExprC<R>>;
type WidenExpressionComplexity3<A, B, C> = WidenComplexity3<ExprC<A>, ExprC<B>, ExprC<C>>;


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
    constructor(public readonly configMapName: BaseExpression<string>, public readonly key: string) {
        super("configmap");
    }
}

export class FromWorkflowUuid extends BaseExpression<string,  "govaluate"> {
    constructor() {
        super("workflow_uuid");
    }
}

export class LoopItemExpression<T extends PlainObject>
    extends BaseExpression<T, "template"> {
    constructor() { super("loop_item"); }
}

/** ───────────────────────────────────────────────────────────────────────────
 *  Derived nodes (single class; brand inferred from children)
 *  ───────────────────────────────────────────────────────────────────────── */
export class AsStringExpression<
    E extends BaseExpression<any, CE>,
    CE extends ExpressionType = ExprC<E>
> extends BaseExpression<string, CE> {
    constructor(public readonly source: E) { super("as_string"); }
}

/* ── Valid path strings for a schema T ───────────────────────────────────── */

type IndexSeg = `[${number}]`;

type _KeyPathsObj<T> =
// object-ish: K or K.Sub
    {
        [K in Extract<keyof T, string>]:
        | K
        | (KeyPaths<T[K]> extends never ? never : `${K}.${KeyPaths<T[K]>}`)
    }[Extract<keyof T, string>];

type _KeyPathsArray<T> =
// allow "[i]" and "[i].Sub"
    | IndexSeg
    | (KeyPaths<T> extends never ? never : `${IndexSeg}.${KeyPaths<T>}`);

export type KeyPaths<T> =
    T extends readonly (infer U)[] ? _KeyPathsArray<U> :
        T extends object               ? _KeyPathsObj<T>   :
            never;

/* ── Value at a given path P in schema T (preserves null/undefined exactly) ─ */

export type PathValue<T, P extends string> =
// direct key
    P extends keyof T ? T[P] :

        // dot step: "a.b"
        P extends `${infer K}.${infer Rest}`
            ? K extends keyof T
                ? PathValue<T[K], Rest>
                : never
            : // array index: "[0]" or "[0].rest"
            P extends `[${infer _Index}]${infer Rest}`
                ? T extends readonly (infer U)[]
                    ? Rest extends "" ? U
                        : Rest extends `.${infer R}` ? PathValue<U, R> : never
                    : never
                : never;

/* ── PathExpression now keeps the *exact* schema type, including nulls ───── */

export class PathExpression<
    E extends BaseExpression<any, any>,
    TSource = ResultOf<E>,              // do not force PlainObject here
    TPath extends KeyPaths<TSource> = KeyPaths<TSource>,
    TResult = PathValue<TSource, TPath> // preserve null/undefined exactly
> extends BaseExpression<TResult & PlainObject, "template"> {
    constructor(public readonly source: E, public readonly path: TPath) { super("path"); }
}

/* ── path() API: only accepts valid paths; returns exact type at that path ─ */

export function path<
    E extends BaseExpression<any, any>,
    P extends KeyPaths<ResultOf<E>>
>(
    source: E,
    p: P
): BaseExpression<PathValue<ResultOf<E>, P>, "template">;

export function path(
    source: BaseExpression<any, any>,
    p: string
): BaseExpression<any, "template"> {
    // Tell TS the ctor is non-generic for the impl
    const Ctor = PathExpression as unknown as new (
        source: BaseExpression<any, any>,
        path: string
    ) => BaseExpression<any, "template">;

    return new Ctor(source, p) as any;
}
export class ConcatExpression<
    ES extends readonly BaseExpression<string, any>[]
> extends BaseExpression<string, "govaluate"> {
    constructor(public readonly expressions: ES, public readonly separator?: string) {
        super("concat");
    }
}

export class TernaryExpression<
    OutT extends PlainObject,
    B extends BaseExpression<boolean, CB>,
    L extends BaseExpression<OutT, CL>,
    R extends BaseExpression<OutT, CR>,
    CB extends ExpressionType = ExprC<B>,
    CL extends ExpressionType = ExprC<L>,
    CR extends ExpressionType = ExprC<R>,
    C extends ExpressionType = WidenComplexity3<CB, CL, CR>
> extends BaseExpression<OutT, C> {
    constructor(
        public readonly condition: B,
        public readonly whenTrue:  L,
        public readonly whenFalse: R
    ) { super("ternary"); }
}

export class ArithmeticExpression<
    L extends BaseExpression<number, CL>,
    R extends BaseExpression<number, CR>,
    CL extends ExpressionType = ExprC<L>,
    CR extends ExpressionType = ExprC<R>
> extends BaseExpression<number, WidenComplexity2<CL, CR>> {
    constructor(
        public readonly operator: "+" | "-" | "*" | "/" | "%",
        public readonly left: L,
        public readonly right: R
    ) { super("arithmetic"); }
}

export const add = <
    L extends BaseExpression<number, any>,
    R extends BaseExpression<number, any>
>(l: L, r: R): BaseExpression<number, WidenComplexity2<ExprC<L>, ExprC<R>>> =>
    new ArithmeticExpression<L, R>("+", l, r);

export class ComparisonExpression<
    T extends number | string,
    L extends BaseExpression<T, CL>,
    R extends BaseExpression<T, CR>,
    CL extends ExpressionType = ExprC<L>,
    CR extends ExpressionType = ExprC<R>,
    C extends ExpressionType = WidenComplexity2<CL, CR>
> extends BaseExpression<boolean, C> {
    constructor(
        public readonly operator: "==" | "!=" | "<" | ">" | "<=" | ">=",
        public readonly left: L,
        public readonly right: R
    ) { super("comparison"); }
}

export class ArrayLengthExpression<
    E extends BaseExpression<any[], CE>,
    CE extends ExpressionType = ExprC<E>
> extends BaseExpression<number, CE> {
    constructor(public readonly array: E) { super("array_length"); }
}

export class ArrayIndexExpression<
    A extends BaseExpression<any[], CA>,
    I extends BaseExpression<number, CI>,
    Elem extends PlainObject =
        A extends BaseExpression<infer Arr, any>
            ? Arr extends (infer U)[] ? U & PlainObject : never
            : never,
    CA extends ExpressionType = ExprC<A>,
    CI extends ExpressionType = ExprC<I>,
    C extends ExpressionType = WidenComplexity2<CA, CI>
> extends BaseExpression<Elem, C> {
    constructor(public readonly array: A, public readonly index: I) { super("array_index"); }
}

export const literal = <T extends PlainObject>(v: T): SimpleExpression<DeepWiden<T>> =>
    new LiteralExpression<DeepWiden<T>>(v as DeepWiden<T>);

export const asString = <E extends BaseExpression<any, any>>(e: E): BaseExpression<string, ExprC<E>> =>
    new AsStringExpression(e);

export const concat = <ES extends readonly BaseExpression<string, any>[]>(...es: ES): BaseExpression<string, "govaluate"> =>
    new ConcatExpression(es);

export const concatWith = <ES extends readonly BaseExpression<string, any>[]>(sep: string, ...es: ES): BaseExpression<string, "govaluate"> =>
    new ConcatExpression(es, sep);

/* ───────── ternary: symmetrical overloads (outputs can be any PlainObject) ───────── */

// Overload: infer Out from L; force R to match L’s result type
export function ternary<
    B extends BaseExpression<boolean, any>,
    L extends BaseExpression<any, any>,
    R extends BaseExpression<ResultOf<L>, any>
>(cond: B, whenTrue: L, whenFalse: R): BaseExpression<ResultOf<L>, WidenComplexity3<ExprC<B>, ExprC<L>, ExprC<R>>>;

// Overload: infer Out from R; force L to match R’s result type
export function ternary<
    B extends BaseExpression<boolean, any>,
    R extends BaseExpression<any, any>,
    L extends BaseExpression<ResultOf<R>, any>
>(cond: B, whenTrue: L, whenFalse: R): BaseExpression<ResultOf<R>, WidenComplexity3<ExprC<B>, ExprC<L>, ExprC<R>>>;

// Implementation
export function ternary(cond: any, whenTrue: any, whenFalse: any): BaseExpression<any, any> {
    return new TernaryExpression(cond, whenTrue, whenFalse);
}

/* ───────── equals: numeric & string overloads ───────── */

// hidden impl binds both sides to the same T (satisfies invariance)
const _equals = <
    T extends Scalar,
    L extends BaseExpression<T, any>,
    R extends BaseExpression<T, any>
>(l: L, r: R): BaseExpression<boolean, WidenExpressionComplexity2<L, R>> =>
    new ComparisonExpression<T, L, R>("==", l, r);

// public API: only number/number and string/string calls exist to the editor.
// NoAny blocks accidental `any` from matching.
export const equals = _equals as {
    <L extends BaseExpression<number, any>, R extends BaseExpression<number, any>>(
        l: NoAny<L>, r: NoAny<R>
    ): BaseExpression<boolean, WidenExpressionComplexity2<L, R>>;
    <L extends BaseExpression<string, any>, R extends BaseExpression<string, any>>(
        l: NoAny<L>, r: NoAny<R>
    ): BaseExpression<boolean, WidenExpressionComplexity2<L, R>>;
};

/* ───────── ordered comparisons: numbers only ───────── */

export function lessThan<
    L extends BaseExpression<number, any>,
    R extends BaseExpression<number, any>
>(l: L, r: R): BaseExpression<boolean, WidenComplexity2<ExprC<L>, ExprC<R>>> {
    return new ComparisonExpression<number,L,R>("<", l, r);
}

export function greaterThan<
    L extends BaseExpression<number, any>,
    R extends BaseExpression<number, any>
>(l: L, r: R): BaseExpression<boolean, WidenComplexity2<ExprC<L>, ExprC<R>>> {
    return new ComparisonExpression<number,L,R>(">", l, r);
}

/* ───────── arithmetic: numbers only ───────── */

export const subtract = <
    L extends BaseExpression<number, CL>,
    R extends BaseExpression<number, CR>,
    CL extends ExpressionType = ExprC<L>,
    CR extends ExpressionType = ExprC<R>
>(l: L, r: R): BaseExpression<number, WidenComplexity2<CL, CR>> =>
    new ArithmeticExpression("-", l, r);

// export const multiply = <
//     L extends BaseExpression<number, CL>,
//     R extends BaseExpression<number, CR>,
//     CL extends ExpressionType = ExprC<L>,
//     CR extends ExpressionType = ExprC<R>,
//     C extends ExpressionType = WidenComplexity2<CL, CR>
// >(l: L, r: R): BaseExpression<number, C> =>
//     new ArithmeticExpression("*", l, r);
//
// export const divide = <
//     L extends BaseExpression<number, CL>,
//     R extends BaseExpression<number, CR>,
//     CL extends ExpressionType = ExprC<L>,
//     CR extends ExpressionType = ExprC<R>,
//     C extends ExpressionType = WidenComplexity2<CL, CR>
// >(l: L, r: R): BaseExpression<number, C> =>
//     new ArithmeticExpression("/", l, r);

/* ───────── arrays ───────── */

export const length = <E extends BaseExpression<any[], any>>(arr: E): BaseExpression<number, ExprC<E>> =>
    new ArrayLengthExpression(arr);

export const index = <
    A extends BaseExpression<any[], any>,
    I extends BaseExpression<number, any>
>(arr: A, i: I) =>
    new ArrayIndexExpression(arr, i);

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
    new FromParameterExpression<DeepWiden<T>>(
        { kind: "step_output", stepName, parameterName },
        def as any
    );

export const taskOutput = <T extends PlainObject>(
    taskName: string,
    parameterName: string,
    def?: OutputParamDef<T>
): SimpleExpression<DeepWiden<T>> =>
    new FromParameterExpression<DeepWiden<T>>(
        { kind: "task_output", taskName, parameterName },
        def as any
    );

export const loopItem = <T extends PlainObject>(): TemplateExpression<T> =>
    new LoopItemExpression<T>();

export const configMap = <T extends PlainObject>(name: BaseExpression<string>, key: string): TemplateExpression<T> =>
    new FromConfigMapExpression(name, key);

export function getWorkflowUuid() {
    return new FromWorkflowUuid();
}