import {AggregateType, DeepWiden, MissingField, PlainObject, Serialized} from "@/schemas/plainObject";
import {StripUndefined, TaskType, TypeToken} from "@/schemas/sharedTypes";
import {InputParamDef, OutputParamDef} from "@/schemas/parameterSchemas";

export type ExpressionType = "govaluate" | "complicatedExpression";

export abstract class BaseExpression<T extends PlainObject, C extends ExpressionType = ExpressionType> {
    readonly _resultType!: T; // phantom only
    readonly _complexity!: C; // phantom only
    constructor(public readonly kind: string) {}
}

export type SimpleExpression<T extends PlainObject>   = BaseExpression<T, "govaluate">;
export type TemplateExpression<T extends PlainObject> = BaseExpression<T, "complicatedExpression">;

// Helper type to allow both literal values and expressions
export type AllowLiteralOrExpression<T extends PlainObject, C extends ExpressionType = ExpressionType> =
    T | BaseExpression<T, C>;

export function isExpression(v: unknown): v is BaseExpression<any, any> {
    return v instanceof BaseExpression;
}

export function toExpression<T extends PlainObject, C extends ExpressionType>(
    v: AllowLiteralOrExpression<T, C>
): BaseExpression<T, any> {
    return isExpression(v) ? v : new LiteralExpression(v as T);
}

type Scalar = number | string;
type ResultOf<E> = E extends BaseExpression<infer U, any> ? U : never;
type ExprC<E>    = E extends BaseExpression<any, infer C> ? C : never;
export type IsAny<T>    = 0 extends (1 & T) ? true : false;
export type NoAny<T>    =  IsAny<ResultOf<T>> extends true ? never : T;

type WidenComplexity2<A extends ExpressionType, B extends ExpressionType> =
    A extends "complicatedExpression" ? "complicatedExpression" :
        B extends "complicatedExpression" ? "complicatedExpression" : "govaluate";
type WidenComplexity3<A extends ExpressionType, B extends ExpressionType, C extends ExpressionType> = WidenComplexity2<WidenComplexity2<A,B>, C>;
type WidenExpressionComplexity2<L, R> = WidenComplexity2<ExprC<L>, ExprC<R>>;
type WidenExpressionComplexity3<A, B, C> = WidenComplexity3<ExprC<A>, ExprC<B>, ExprC<C>>;

export function widenComplexity<T extends PlainObject>(
    v: BaseExpression<T, any>
): BaseExpression<T, "complicatedExpression"> {
    return v as BaseExpression<T, "complicatedExpression">;
}

export class LiteralExpression<T extends PlainObject>
    extends BaseExpression<T, "govaluate"> {
    constructor(public readonly value: T) { super("literal"); }
}

export class AsStringExpression<
    E extends BaseExpression<any, CE>,
    CE extends ExpressionType = ExprC<E>
> extends BaseExpression<string, CE> {
    constructor(public readonly source: E) { super("as_string"); }
}

export class NullCoalesce<
    T extends PlainObject
> extends BaseExpression<DeepWiden<T>, "complicatedExpression"> {
    public readonly defaultValue: BaseExpression<DeepWiden<T>>;
    constructor(
        public readonly preferredValue: BaseExpression<T|MissingField>,
        d: AllowLiteralOrExpression<DeepWiden<T>>
    ) {
        super("sprig.coalesce");
        this.defaultValue = toExpression(d);
    }
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

// Helper types for dict operations
type NormalizeValue<V> =
    V extends BaseExpression<infer U, infer C> ? BaseExpression<U, C> :
        V extends PlainObject                     ? SimpleExpression<DeepWiden<V>> :
            never;

type NormalizeRecord<R extends Record<string, unknown>> = {
    [K in keyof R]: NormalizeValue<R[K]>;
};

type ResultRecord<N extends Record<string, BaseExpression<any, any>>> = {
    [K in keyof N]: ResultOf<N[K]>;
};

type ComplexityOfRecord<N extends Record<string, BaseExpression<any, any>>> =
    Extract<N[keyof N], BaseExpression<any, "complicatedExpression">> extends never
        ? "govaluate"
        : "complicatedExpression";

type Keys<A, B> = keyof A | keyof B;

type MergeTwo<A extends Record<string, any>, B extends Record<string, any>> = {
    [K in Keys<A, B>]:
    K extends keyof B
        ? (K extends keyof A ? A[K] | B[K] : B[K])
        : (K extends keyof A ? A[K] : never)
};

export class DictMakeExpression<
    N extends Record<string, BaseExpression<any, any>>
> extends BaseExpression<ResultRecord<N>, ComplexityOfRecord<N>> {
    constructor(public readonly entries: N) {
        super("dict_make");
    }
}

export class RecordFieldSelectExpression<T, P, E> extends BaseExpression<any, "complicatedExpression"> {
    constructor(public readonly source: E, public readonly path: P) { super("path"); }
}

// JSON path helper types
type AnyTrue<U> = Extract<U, true> extends never ? false : true;
type MissingInAny<T, K extends PropertyKey> =
    AnyTrue<T extends any ? (K extends keyof T ? false : true) : never>;
type OptionalInAny<T, K extends PropertyKey> =
    AnyTrue<T extends any ? (K extends keyof T ? ({} extends Pick<T, K> ? true : false) : false) : never>;
export type NonMissing<T> = Exclude<T, MissingField>;
type HasMissing<T> = Extract<T, MissingField> extends never ? false : true;
type PropTypeNoUndef<T, K extends PropertyKey> =
    T extends any
        ? (K extends keyof T ? StripUndefined<T[K]> : never)
        : never;
type SegKey<T> = Extract<keyof T, string>;
type IsTuple<A> = A extends readonly [...infer _T] ? true : false;
type SegsFor<T> =
    T extends readonly (infer U)[]
        ? readonly [number, ...SegsFor<U>] | readonly [number]
        : T extends object
            ? { [K in SegKey<T>]:
                readonly [K, ...SegsFor<T[K]>] | readonly [K]
            }[SegKey<T>]
            : readonly [];
export type KeySegments<T> = SegsFor<NonMissing<T>>;
type BoolOr<A extends boolean, B extends boolean> =
    A extends true ? true : B extends true ? true : false;
type IsTupleIndexStrict<T, I> =
    IsTuple<T> extends true
        ? I extends number
            ? T extends readonly [...infer Tup]
                ? Extract<I, keyof Tup> extends never ? false : true
                : false
            : false
        : false;
type NextMissingAfterProp<T, K extends PropertyKey, Acc extends boolean> =
    BoolOr<Acc, BoolOr<OptionalInAny<T, K>, MissingInAny<T, K>>>;
type NextMissingAfterIndex<T, I, Acc extends boolean> =
    IsTupleIndexStrict<T, I> extends true ? Acc : true;
type ValueAtSegsStrict<T, S extends readonly unknown[]> =
    S extends []
        ? T
        : S extends readonly [infer H, ...infer R]
            ? H extends number
                ? T extends readonly (infer U)[]
                    ? ValueAtSegsStrict<U, Extract<R, readonly unknown[]>>
                    : never
                : H extends keyof T
                    ? ValueAtSegsStrict<T[H], Extract<R, readonly unknown[]>>
                    : never
            : never;
export type SegmentsValueStrict<T, S extends readonly unknown[]> =
    ValueAtSegsStrict<NonMissing<T>, S>;
type MaybeMissing<Flag extends boolean, V> =
    Flag extends true ? V | MissingField : V;
type ValueAtSegsMissing<
    T,
    S extends readonly unknown[],
    AccMissing extends boolean
> =
    S extends []
        ? MaybeMissing<AccMissing, T>
        : S extends readonly [infer H, ...infer R]
            ? H extends number
                ? T extends readonly (infer U)[]
                    ? ValueAtSegsMissing<U, Extract<R, readonly unknown[]>, NextMissingAfterIndex<T, H, AccMissing>>
                    : MissingField
                : H extends PropertyKey
                    ? ValueAtSegsMissing<
                        PropTypeNoUndef<T, H>,
                        Extract<R, readonly unknown[]>,
                        NextMissingAfterProp<T, H, AccMissing>
                    >
                    : MissingField
            : never;
export type SegmentsValueMissing<T, S extends readonly unknown[]> =
    ValueAtSegsMissing<NonMissing<T>, S, HasMissing<T>>;

type ElemFromArrayExpr<A extends BaseExpression<any[], any>> =
    ResultOf<A> extends (infer U)[] ? Extract<U, PlainObject> : never;

export class ArrayIndexExpression<
    A  extends BaseExpression<any[], CA>,
    I  extends BaseExpression<number, CI>,
    CA extends ExpressionType = ExprC<A>,
    CI extends ExpressionType = ExprC<I>,
    Elem extends PlainObject = ElemFromArrayExpr<A>,
    C  extends ExpressionType = WidenComplexity2<CA, CI>
> extends BaseExpression<Elem, C> {
    constructor(public readonly array: A, public readonly index: I) { super("array_index"); }
}

type WidenComplexityOfArray<ES extends readonly BaseExpression<any, any>[]> =
    Extract<ExprC<ES[number]>, "complicatedExpression"> extends never
        ? "govaluate"
        : "complicatedExpression";

export class ArrayMakeExpression<
    ES extends readonly BaseExpression<any, any>[],
    Elem extends PlainObject = ResultOf<ES[number]>
> extends BaseExpression<Elem[], WidenComplexityOfArray<ES>> {
    constructor(public readonly elements: ES) { super("array_make"); }
}

export type ParameterSource =
    | { kind: "workflow",     parameterName: string }
    | { kind: "input",        parameterName: string }
    | { kind: "steps_output", stepName: string, parameterName: string }
    | { kind: "tasks_output", taskName: string, parameterName: string };

export type WorkflowParameterSource = { kind: "workflow", parameterName: string };
export type InputParameterSource = { kind: "input", parameterName: string };
export type StepOutputSource = { kind: "steps_output", stepName: string, parameterName: string }
export type TaskOutputSource = { kind: "tasks_output", taskName: string, parameterName: string };

export type WORKFLOW_VALUES =
    "name"|"mainEntrypoint"|"serviceAccountName"|"uid"|"labels.json"|"creationTimestamp"|"priority"|"duration"|"scheduledTime";

export type WrapSerialize<T extends PlainObject> = T extends AggregateType ? Serialized<T> : T;
export type UnwrapSerialize<T extends PlainObject> = T extends Serialized<infer U> ? U : T;
type ConditionalWrap<T extends PlainObject, S extends ParameterSource> =
    S extends WorkflowParameterSource | InputParameterSource ? WrapSerialize<T> : T;

export class FromParameterExpression<
    T extends PlainObject,
    S extends ParameterSource
> extends BaseExpression<T, "govaluate"> {
    constructor(
        public readonly source: S,
        public readonly paramDef?: InputParamDef<T, any> | OutputParamDef<T>
    ) { super("parameter"); }
}

export class TaskDataExpression<T extends PlainObject> extends BaseExpression<T, "govaluate"> {
    constructor(public readonly taskType: TaskType,  public readonly name: string, public readonly key: string) {
        super("task_data");
    }
}

export class WorkflowValueExpression extends BaseExpression<string,  "govaluate"> {
    constructor(public readonly variable: WORKFLOW_VALUES) {
        super("workflow_value");
    }
}

export class LoopItemExpression<T extends PlainObject>
    extends BaseExpression<T, "complicatedExpression"> {
    constructor() { super("loop_item"); }
}

export class TemplateReplacementExpression extends BaseExpression<string,  "complicatedExpression"> {
    constructor(public readonly template: string,
                public readonly replacements: Record<string, BaseExpression<string>>) {
        super("fillTemplate");
    }
}

type ExtractTemplatePlaceholders<T extends string> =
    T extends `${string}{{${infer Placeholder}}}${infer Rest}`
        ? Placeholder | ExtractTemplatePlaceholders<Rest>
        : never;

type TemplateReplacements<T extends string> = {
    [K in ExtractTemplatePlaceholders<T>]: AllowLiteralOrExpression<string>;
};

type NormalizedReplacements<T extends string> = {
    [K in ExtractTemplatePlaceholders<T>]: BaseExpression<string>;
};

export class FunctionExpression<
    ResultT extends PlainObject,
    InputT extends PlainObject,
    CIn extends ExpressionType = "complicatedExpression",
    COut extends ExpressionType = "complicatedExpression",
    InputExpressionsT extends readonly BaseExpression<InputT, CIn>[] = readonly BaseExpression<InputT, CIn>[]
> extends BaseExpression<ResultT, COut> {
    constructor(
        public readonly functionName: string,
        public readonly args: InputExpressionsT,
        // convenience args for callers that would rather infer types
        _typeToken?: TypeToken<ResultT>
    ) {
        super("function");
    }
}

export class BinaryFunctionExpression<
    ResultT extends PlainObject,
    Input1T extends PlainObject = PlainObject,
    Input2T extends PlainObject = PlainObject,
    C1 extends ExpressionType = "complicatedExpression",
    C2 extends ExpressionType = "complicatedExpression"
> extends BaseExpression<ResultT, WidenComplexity2<C1, C2>> {
    constructor(
        public readonly functionName: string,
        public readonly arg1: BaseExpression<Input1T, C1>,
        public readonly arg2: BaseExpression<Input2T, C2>,
        // convenience args for callers that would rather infer types
        _typeToken?: TypeToken<ResultT>
    ) {
        super("function");
    }
}

// Helper types for toArray
type NormalizeOne<E> =
    E extends BaseExpression<infer U, infer C> ? BaseExpression<U, C>
        : E extends PlainObject                       ? SimpleExpression<DeepWiden<E>>
            : never;
type NormalizeTuple<ES extends readonly unknown[]> = {
    [K in keyof ES]: NormalizeOne<ES[K]>;
};
type ElemOfNormalized<ES extends readonly unknown[]> =
    ResultOf<NormalizeTuple<ES>[number]>;
type ComplexityOfNormalized<ES extends readonly unknown[]> =
    WidenComplexityOfArray<
        Extract<NormalizeTuple<ES>, readonly BaseExpression<any, any>[]>
    >;

function fn<
    R extends PlainObject,
    CIn extends ExpressionType = "complicatedExpression",
    COut extends ExpressionType = CIn
>(
    name: string,
    ...args: BaseExpression<any, CIn>[]
) {
    return new FunctionExpression<R, any, CIn, COut, typeof args>(name, args);
}

function _segmentsToPath(segs: readonly unknown[]): string {
    return segs.map(s => typeof s === "number" ? `[${s}]` : String(s)).join(".");
}

class ExprBuilder {
    // Core functions
    literal<T extends PlainObject>(v: T): SimpleExpression<DeepWiden<T>> {
        return new LiteralExpression<DeepWiden<T>>(v as DeepWiden<T>);
    }

    asString<E extends BaseExpression<any, any>>(e: E): BaseExpression<string, ExprC<E>> {
        return new AsStringExpression(e);
    }

    cast<FROM extends PlainObject>(v: BaseExpression<FROM>) {
        return {
            to<TO extends PlainObject>(): BaseExpression<TO> {
                return v as unknown as BaseExpression<TO>;
            }
        };
    }

    concat<ES extends readonly BaseExpression<string, any>[]>(...es: ES): BaseExpression<string, "govaluate"> {
        return new ConcatExpression(es);
    }

    concatWith<ES extends readonly BaseExpression<string, any>[]>(sep: string, ...es: ES): BaseExpression<string, "govaluate"> {
        return new ConcatExpression(es, sep);
    }

    ternary<
        B extends BaseExpression<boolean, any>,
        L extends BaseExpression<any, any>,
        R extends BaseExpression<ResultOf<L>, any>
    >(cond: B, whenTrue: L, whenFalse: R): BaseExpression<ResultOf<L>, WidenComplexity3<ExprC<B>, ExprC<L>, ExprC<R>>>;
    ternary<
        B extends BaseExpression<boolean, any>,
        R extends BaseExpression<any, any>,
        L extends BaseExpression<ResultOf<R>, any>
    >(cond: B, whenTrue: L, whenFalse: R): BaseExpression<ResultOf<R>, WidenComplexity3<ExprC<B>, ExprC<L>, ExprC<R>>>;
    ternary(cond: any, whenTrue: any, whenFalse: any): BaseExpression<any, any> {
        return new TernaryExpression(cond, whenTrue, whenFalse);
    }

    toArray<ES extends readonly unknown[]>(
        ...items: ES
    ): BaseExpression<
        ElemOfNormalized<ES>[],
        ComplexityOfNormalized<ES>
    > {
        const normalized = items.map((it) =>
            isExpression(it) ? it : this.literal(it as PlainObject)
        ) as NormalizeTuple<ES>;

        type N = Extract<typeof normalized, readonly BaseExpression<any, any>[]>;

        return new ArrayMakeExpression<N>(normalized as unknown as N) as BaseExpression<
            ElemOfNormalized<ES>[],
            ComplexityOfNormalized<ES>
        >;
    }

    nullCoalesce<T extends PlainObject>(
        v: BaseExpression<T | MissingField, any>,
        d: AllowLiteralOrExpression<DeepWiden<T>>
    ) {
        return fn<DeepWiden<T>>("sprig.coalesce", v, toExpression(d));
    }

    // Comparisons
    equals: {
        <L extends AllowLiteralOrExpression<number, any>, R extends AllowLiteralOrExpression<number, any>>(
            l: NoAny<L>, r: NoAny<R>
        ): BaseExpression<boolean, WidenExpressionComplexity2<L, R>>;
        <L extends AllowLiteralOrExpression<string, any>, R extends AllowLiteralOrExpression<string, any>>(
            l: NoAny<L>, r: NoAny<R>
        ): BaseExpression<boolean, WidenExpressionComplexity2<L, R>>;
    } = <
        T extends Scalar,
        L extends AllowLiteralOrExpression<T, any>,
        R extends AllowLiteralOrExpression<T, any>,
    >(l: L, r: R): BaseExpression<boolean, WidenExpressionComplexity2<L, R>> =>
        new ComparisonExpression<T, BaseExpression<T, any>, BaseExpression<T, any>>("==", toExpression(l), toExpression(r)) as any;

    lessThan<
        L extends BaseExpression<number, any>,
        R extends BaseExpression<number, any>
    >(l: L, r: R): BaseExpression<boolean, WidenComplexity2<ExprC<L>, ExprC<R>>> {
        return new ComparisonExpression<number,L,R>("<", l, r);
    }

    greaterThan<
        L extends BaseExpression<number, any>,
        R extends BaseExpression<number, any>
    >(l: L, r: R): BaseExpression<boolean, WidenComplexity2<ExprC<L>, ExprC<R>>> {
        return new ComparisonExpression<number,L,R>(">", l, r);
    }

    not<C extends ExpressionType="govaluate">(data: AllowLiteralOrExpression<boolean, C>) {
        return fn<boolean, C>("!", toExpression(data));
    }

    // Arithmetic
    add<
        L extends BaseExpression<number, any>,
        R extends BaseExpression<number, any>
    >(l: L, r: R): BaseExpression<number, WidenComplexity2<ExprC<L>, ExprC<R>>> {
        return new ArithmeticExpression<L, R>("+", l, r);
    }

    subtract<
        L extends BaseExpression<number, CL>,
        R extends BaseExpression<number, CR>,
        CL extends ExpressionType = ExprC<L>,
        CR extends ExpressionType = ExprC<R>
    >(l: L, r: R): BaseExpression<number, WidenComplexity2<CL, CR>> {
        return new ArithmeticExpression("-", l, r);
    }

    // JSON Handling
    keys<T extends Record<string, any>>(obj: BaseExpression<T>) {
        return fn<string[], ExpressionType, "complicatedExpression">("keys", obj);
    }

    jsonPathLoose<
        T extends Record<string, any>,
        K extends Extract<keyof NonMissing<T>, string>
    >(
        source: BaseExpression<T, any>,
        key: K
    ): BaseExpression<SegmentsValueMissing<T, readonly [K]>, "complicatedExpression">;
    jsonPathLoose<
        T extends Record<string, any>,
        S extends KeySegments<T>
    >(
        source: BaseExpression<T, any>,
        ...segs: S
    ): BaseExpression<SegmentsValueMissing<T, S>, "complicatedExpression">;
    jsonPathLoose(
        source: BaseExpression<any, any>,
        ...segs: readonly unknown[]
    ): BaseExpression<any, "complicatedExpression"> {
        const path = _segmentsToPath(segs);
        return new RecordFieldSelectExpression(source, path) as any;
    }

    jsonPathStrict<
        T extends Record<string, any>,
        K extends Extract<keyof NonMissing<T>, string>
    >(
        source: BaseExpression<T, any>,
        key: K
    ): BaseExpression<SegmentsValueStrict<T, readonly [K]>, "complicatedExpression">;
    jsonPathStrict<
        T extends Record<string, any>,
        S extends KeySegments<T>
    >(
        source: BaseExpression<T, any>,
        ...segs: S
    ): BaseExpression<SegmentsValueStrict<T, S>, "complicatedExpression">;
    jsonPathStrict(
        source: BaseExpression<any, any>,
        ...segs: readonly unknown[]
    ): BaseExpression<any, "complicatedExpression"> {
        const path = _segmentsToPath(segs);
        return new RecordFieldSelectExpression(source, path) as any;
    }

    stringToRecord<R extends PlainObject>(capture: TypeToken<R>, data: AllowLiteralOrExpression<string>) {
        return fn<R>("fromJSON", toExpression(data));
    }

    recordToString<T extends PlainObject>(data: AllowLiteralOrExpression<T>) {
        return fn<string>("toJSON", toExpression(data));
    }

    makeDict<R extends Record<string, AllowLiteralOrExpression<PlainObject>>>(
        entries: R
    ): BaseExpression<
        { [K in keyof R]: ResultOf<NormalizeValue<R[K]>> },
        ComplexityOfRecord<NormalizeRecord<R>>
    > {
        const normalized = Object.fromEntries(
            Object.entries(entries).map(([k, v]) => {
                const e = isExpression(v) ? v : this.literal(v);
                return [k, e];
            })
        ) as NormalizeRecord<R>;

        return new DictMakeExpression(normalized) as BaseExpression<
            { [K in keyof R]: ResultOf<NormalizeValue<R[K]>> },
            ComplexityOfRecord<NormalizeRecord<R>>
        >;
    }

    mergeDicts<
        L extends BaseExpression<Record<string, any>, any>,
        R extends BaseExpression<Record<string, any>, any>
    >(
        left: L,
        right: R
    ): BaseExpression<MergeTwo<ResultOf<L>, ResultOf<R>>, "complicatedExpression"> {
        return new FunctionExpression<
            MergeTwo<ResultOf<L>, ResultOf<R>>,
            any,
            "complicatedExpression",
            "complicatedExpression",
            readonly [L, R]
        >("merge", [left, right] as const);
    }

    // Array operations
    index<
        A extends BaseExpression<any[], any>,
        I extends BaseExpression<number, any>
    >(
        arr: A,
        i: I
    ): BaseExpression<ElemFromArrayExpr<A>, WidenComplexity2<ExprC<A>, ExprC<I>>> {
        return new ArrayIndexExpression(arr, i);
    }

    length<T extends PlainObject>(arr: BaseExpression<T[]>) {
        return fn<number, ExpressionType, "complicatedExpression">("len", arr);
    }

    last<T extends PlainObject>(arr: BaseExpression<T[]>) {
        return fn<T,ExpressionType,"complicatedExpression">("last", arr);
    }

    // Parameter functions

    taskData<T extends PlainObject>(
        taskType: TaskType,
        taskName: string,
        key: string
    ): SimpleExpression<T> {
        return new TaskDataExpression<T>(taskType, taskName, key);
    }

    // Utility
    split(arr: AllowLiteralOrExpression<string>, delim: AllowLiteralOrExpression<string>) {
        return fn<string[],ExpressionType,"complicatedExpression">("split", toExpression(arr), toExpression(delim));
    }

    getWorkflowValue(value: WORKFLOW_VALUES) {
        return new WorkflowValueExpression(value);
    }

    fromBase64(data: AllowLiteralOrExpression<string>) {
        return fn<string>("fromBase64", toExpression(data));
    }

    toBase64(data: AllowLiteralOrExpression<string>) {
        return fn<string>("toBase64", toExpression(data));
    }

    fillTemplate<T extends string>(
        template: T,
        replacements: TemplateReplacements<T>
    ): BaseExpression<string, "complicatedExpression"> {
        const normalizedReplacements: NormalizedReplacements<T> = {} as any;

        for (const [key, value] of Object.entries(replacements)) {
            const typedValue = value as AllowLiteralOrExpression<string>;
            normalizedReplacements[key as keyof NormalizedReplacements<T>] =
                typeof typedValue === 'string' ? this.literal(typedValue) : typedValue;
        }

        return new TemplateReplacementExpression(template, normalizedReplacements);
    }
}

export const expr = new ExprBuilder();

export default expr;