import {
    AggregateType,
    DeepWiden,
    MissingField,
    NonSerializedPlainObject,
    PlainObject,
    Serialized
} from "./plainObject";
import {StripUndefined, TaskType, typeToken, TypeToken} from "./sharedTypes";
import {ConfigMapKeySelector, InputParamDef, OutputParamDef} from "./parameterSchemas";

export type ExpressionType = "govaluate" | "complicatedExpression";

export abstract class BaseExpression<T extends PlainObject, C extends ExpressionType = ExpressionType> {
    readonly _resultType!: T; // phantom only
    readonly _complexity!: C; // phantom only
    constructor(public readonly kind: string) {}
}

export type SimpleExpression<T extends PlainObject> = BaseExpression<T, "govaluate">;
export type TemplateExpression<T extends PlainObject> = BaseExpression<T, "complicatedExpression">;

// Helper type to allow both literal values and expressions
export type AllowLiteralOrExpression<T extends PlainObject, C extends ExpressionType = ExpressionType> =
    T | BaseExpression<T, C>;

export type AllowSerializedAggregateOrPrimitiveExpressionOrLiteral<T extends PlainObject, C extends ExpressionType = ExpressionType> =
    T extends AggregateType
        ? BaseExpression<Serialized<T>, C>
        : T | BaseExpression<T, C>;

export function isExpression(v: unknown): v is BaseExpression<any, any> {
    return v instanceof BaseExpression;
}

export function toExpression<T extends PlainObject, C extends ExpressionType>(
    v: AllowLiteralOrExpression<T, C>
): BaseExpression<T, C> {
    return isExpression(v) ? v : widenComplexity(new LiteralExpression(v as T));
}

type Scalar = number | string;
type ResultOf<E> = E extends BaseExpression<infer U, any> ? U : never;
type ExprC<E> = E extends BaseExpression<any, infer C> ? C : never;
export type IsAny<T> = 0 extends (1 & T) ? true : false;
export type NoAny<T> = IsAny<ResultOf<T>> extends true ? never : T;

type WidenComplexity2<A extends ExpressionType, B extends ExpressionType> =
    A extends "complicatedExpression" ? "complicatedExpression" :
        B extends "complicatedExpression" ? "complicatedExpression" : "govaluate";
type WidenComplexity3<A extends ExpressionType, B extends ExpressionType, C extends ExpressionType> = WidenComplexity2<WidenComplexity2<A,B>, C>;
type WidenExpressionComplexity2<L, R> = WidenComplexity2<ExprC<L>, ExprC<R>>;
type WidenExpressionComplexity3<A, B, C> = WidenComplexity3<ExprC<A>, ExprC<B>, ExprC<C>>;

export function widenComplexity<
    T extends PlainObject,
    C extends ExpressionType="complicatedExpression"
>(
    v: BaseExpression<T, any>
): BaseExpression<T, C> {
    return v as BaseExpression<T, C>;
}

export class UnquotedTypeWrapper<T extends PlainObject> extends BaseExpression<T, "complicatedExpression"> {
    constructor(public readonly value: BaseExpression<T>) {
        super("strip_surrounding_quotes_in_serialized_output");
    }
}

export class LiteralExpression<T extends PlainObject>
    extends BaseExpression<T, "govaluate"> {
    constructor(public readonly value: T) {
        super("literal");
    }
}

export class AsStringExpression<
    E extends BaseExpression<any, CE>,
    CE extends ExpressionType = ExprC<E>
> extends BaseExpression<string, CE> {
    constructor(public readonly source: E) {
        super("as_string");
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
    ) {
        super("ternary");
    }
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
    ) {
        super("comparison");
    }
}

export class InfixExpression<
    T extends PlainObject,
    CL extends ExpressionType,
    CR extends ExpressionType
> extends BaseExpression<T, WidenExpressionComplexity2<CL, CR>> {
    constructor(
        public readonly operator: "+" | "-" | "*" | "/" | "%" | "||" | "&&" | "in",
        public readonly left: BaseExpression<any, CL>,
        public readonly right: BaseExpression<any, CR>,
        phantom?: TypeToken<T>
    ) {
        super("infix");
    }
}

type PlainRecord = Record<string, NonSerializedPlainObject>;
type ResultOfExpr<E> = E extends BaseExpression<infer U, any> ? U : never;
type KeysOfUnion<T> = T extends any ? keyof T : never;
type MembersWithKey<T, K extends PropertyKey> =
    T extends any ? (K extends keyof T ? T : never) : never;

// Deeply exclude `undefined` from any PlainObject (arrays + records).
type DeepStripUndefined<T> =
// keep MissingField as-is
    T extends MissingField ? MissingField :
        // Serialized payload preserved; scrub inside the payload
        T extends Serialized<infer U> ? Serialized<DeepStripUndefined<U>> :
            // arrays
            T extends readonly (infer U)[] ? readonly DeepStripUndefined<U>[] :
                // objects (including records)
                T extends object ? { [K in keyof T]: DeepStripUndefined<T[K]> } :
                    // primitives / strings / numbers / booleans
                    Exclude<T, undefined>;

export class RecordGetExpression<
    E extends BaseExpression<PlainRecord, any>,
    // infer T from E's result
    T extends PlainRecord = ResultOfExpr<E>,
    // const generic preserves literal-ness for editor autocomplete
    const K extends Extract<keyof NonMissing<T>, string> = Extract<keyof NonMissing<T>, string>
> extends BaseExpression<SegmentsValueStrict<T, readonly [K]>, "complicatedExpression"> {
    constructor(public readonly source: E, public readonly key: K) {
        super("get");
    }
}

// Helper types for dict operations
type NormalizeValue<V> =
    V extends BaseExpression<infer U, infer C> ? BaseExpression<U, C> :
        V extends PlainObject ? SimpleExpression<DeepWiden<V>> :
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

// Helper for dig
// Stop on arrays; recurse only on plain object keys.
type DictKeySegmentsCore<T> =
    T extends readonly any[] ? readonly [] :
        T extends object
            ? {
                [K in Extract<keyof T, string>]:
                readonly [K] | readonly [K, ...DictKeySegmentsCore<PropTypeNoUndef<T, K>>]
            }[Extract<keyof T, string>]
            : readonly [];

// JSON path helper types
export class RecordFieldSelectExpression<T, P, E> extends BaseExpression<any, "complicatedExpression"> {
    constructor(public readonly source: E, public readonly path: P) {
        super("path");
    }
}

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
            ? {
                [K in SegKey<T>]:
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
type DigValue<
    T extends Record<string, NonSerializedPlainObject>,
    S extends readonly unknown[]
> =
    DeepWiden<
        NonMissing<
            SegmentsValueMissing<
                UnwrapSerialize<NonMissing<T>>,
                S
            >
        >
    >;


type ElemFromArrayExpr<A extends BaseExpression<any[], any>> =
    ResultOf<A> extends (infer U)[] ? Extract<U, PlainObject> : never;

export class ArrayIndexExpression<
    A extends BaseExpression<any[], CA>,
    I extends BaseExpression<number, CI>,
    CA extends ExpressionType = ExprC<A>,
    CI extends ExpressionType = ExprC<I>,
    Elem extends PlainObject = ElemFromArrayExpr<A>,
    C extends ExpressionType = WidenComplexity2<CA, CI>
> extends BaseExpression<Elem, C> {
    constructor(public readonly array: A, public readonly index: I) {
        super("array_index");
    }
}

type WidenComplexityOfArray<ES extends readonly BaseExpression<any, any>[]> =
    Extract<ExprC<ES[number]>, "complicatedExpression"> extends never
        ? "govaluate"
        : "complicatedExpression";

export class ArrayMakeExpression<
    ES extends readonly BaseExpression<any, any>[],
    Elem extends PlainObject = ResultOf<ES[number]>
> extends BaseExpression<Elem[], WidenComplexityOfArray<ES>> {
    constructor(public readonly elements: ES) {
        super("array_make");
    }
}

export type ParameterSource =
    | { kind: "workflow", parameterName: string }
    | { kind: "input", parameterName: string }
    | { kind: "steps_output", stepName: string, parameterName: string }
    | { kind: "tasks_output", taskName: string, parameterName: string };

export type WorkflowParameterSource = { kind: "workflow", parameterName: string };
export type InputParameterSource = { kind: "input", parameterName: string };
export type StepOutputSource = { kind: "steps_output", stepName: string, parameterName: string }
export type TaskOutputSource = { kind: "tasks_output", taskName: string, parameterName: string };

export type WORKFLOW_VALUES =
    "name"
    | "mainEntrypoint"
    | "serviceAccountName"
    | "uid"
    | "labels.json"
    | "creationTimestamp"
    | "priority"
    | "duration"
    | "scheduledTime";

export type WrapSerialize<T extends PlainObject> = T extends string ? T : Serialized<T>;
export type UnwrapSerialize<T extends PlainObject> = T extends Serialized<infer U> ? U : T;
type ConditionalWrap<T extends PlainObject, S extends ParameterSource> =
    S extends WorkflowParameterSource | InputParameterSource ? WrapSerialize<T> : T;

export class FromParameterExpression<
    T extends PlainObject,
    S extends ParameterSource
> extends BaseExpression<ConditionalWrap<T, S>, "govaluate"> {
    constructor(
        public readonly source: S,
        public readonly _paramDef?: InputParamDef<T, any> | OutputParamDef<T>
    ) {
        super("parameter");
    }
}

export class TaskDataExpression<T extends PlainObject> extends BaseExpression<T, "govaluate"> {
    constructor(public readonly taskType: TaskType, public readonly name: string, public readonly key: string) {
        super("task_data");
    }
}

export class WorkflowValueExpression extends BaseExpression<string, "govaluate"> {
    constructor(public readonly variable: WORKFLOW_VALUES) {
        super("workflow_value");
    }
}

export class LoopItemExpression<T extends PlainObject>
    extends BaseExpression<T, "complicatedExpression"> {
    constructor() {
        super("loop_item");
    }
}

export class TemplateReplacementExpression extends BaseExpression<string, "complicatedExpression"> {
    constructor(public readonly template: string,
                public readonly replacements: Record<string, BaseExpression<string>>) {
        super("fillTemplate");
    }
}

type UnwrapSerialized<T> = T extends Serialized<infer U>
    ? U
    : T extends PlainObject
        ? T
        : never;

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

// Helper types for toArray
type NormalizeOne<E> =
    E extends BaseExpression<infer U, infer C> ? BaseExpression<U, C>
        : E extends PlainObject ? SimpleExpression<DeepWiden<E>>
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

    cast<FROM extends AllowLiteralOrExpression<any, any>>(v: FROM) {
        return {
            to<TO extends PlainObject>(): BaseExpression<TO, ExprC<FROM>> {
                return v as unknown as BaseExpression<TO, ExprC<FROM>>;
            }
        };
    }

    // Logical
    ternary<
        B extends AllowLiteralOrExpression<boolean, any>,
        L extends AllowLiteralOrExpression<any, any>,
        R extends AllowLiteralOrExpression<ResultOf<L>, any>
    >(cond: B, whenTrue: L, whenFalse: R): BaseExpression<ResultOf<L>, WidenComplexity3<ExprC<B>, ExprC<L>, ExprC<R>>>;
    ternary<
        B extends AllowLiteralOrExpression<boolean, any>,
        R extends AllowLiteralOrExpression<any, any>,
        L extends AllowLiteralOrExpression<ResultOf<R>, any>
    >(cond: B, whenTrue: L, whenFalse: R): BaseExpression<ResultOf<R>, WidenComplexity3<ExprC<B>, ExprC<L>, ExprC<R>>>;
    ternary(cond: any, whenTrue: any, whenFalse: any): BaseExpression<any, any> {
        return new TernaryExpression(cond, whenTrue, whenFalse);
    }

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
        return new ComparisonExpression<number, L, R>("<", l, r);
    }

    greaterThan<
        L extends BaseExpression<number, any>,
        R extends BaseExpression<number, any>
    >(l: L, r: R): BaseExpression<boolean, WidenComplexity2<ExprC<L>, ExprC<R>>> {
        return new ComparisonExpression<number, L, R>(">", l, r);
    }

    not<C extends ExpressionType>(data: AllowLiteralOrExpression<boolean, C>) {
        return fn<boolean, C>("!", toExpression(data));
    }

    isEmpty(data: AllowLiteralOrExpression<any, any>): BaseExpression<boolean, "complicatedExpression"> {
        return this.equals(0, expr.length(expr.asString(data)));
    }

    empty<T extends PlainObject>(): BaseExpression<T> {
        return this.cast(this.literal("")).to<T>();
    }

    add<
        L extends BaseExpression<boolean, CL>,
        R extends BaseExpression<boolean, CR>,
        CL extends ExpressionType = ExprC<L>,
        CR extends ExpressionType = ExprC<R>,
    >(l: L, r: R) {
        return new InfixExpression("+", l, r);
    }

    subtract<
        L extends BaseExpression<boolean, CL>,
        R extends BaseExpression<boolean, CR>,
        CL extends ExpressionType = ExprC<L>,
        CR extends ExpressionType = ExprC<R>,
    >(l: L, r: R) {
        return new InfixExpression("-", l, r);
    }

    and<
        L extends BaseExpression<boolean, CL>,
        R extends BaseExpression<boolean, CR>,
        CL extends ExpressionType = ExprC<L>,
        CR extends ExpressionType = ExprC<R>,
    >(l: L, r: R) {
        return new InfixExpression("&&", l, r);
    }

    or<
        L extends BaseExpression<boolean, CL>,
        R extends BaseExpression<boolean, CR>,
        CL extends ExpressionType = ExprC<L>,
        CR extends ExpressionType = ExprC<R>,
    >(l: L, r: R) {
        return new InfixExpression("||", l, r);
    }

    // String functions
    concat<ES extends readonly BaseExpression<string, any>[]>(...es: ES): BaseExpression<string, "govaluate"> {
        return new ConcatExpression(es);
    }

    concatWith<ES extends readonly BaseExpression<string, any>[]>(sep: string, ...es: ES): BaseExpression<string, "govaluate"> {
        return new ConcatExpression(es, sep);
    }

    split(arr: AllowLiteralOrExpression<string>, delim: AllowLiteralOrExpression<string>) {
        return fn<string[], ExpressionType, "complicatedExpression">("split", toExpression(arr), toExpression(delim));
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

    toLowerCase(data: AllowLiteralOrExpression<string, any>) {
        return fn<string>("lower", toExpression(data));
    }

    toUpperCase(data: AllowLiteralOrExpression<string, any>) {
        return fn<string>("upper", toExpression(data));
    }

    join(data: BaseExpression<string[], any>) {
        return fn<string>("join", data);
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

    // Record Handling

    hasKey<
        T extends Record<string, PlainObject> | MissingField,
        // union of keys across all record members (excluding MissingField)
        const K extends Extract<KeysOfUnion<Exclude<T, MissingField>>, string>
    >(
        obj: BaseExpression<T, any>,
        key: K
    ): BaseExpression<boolean, "complicatedExpression"> {
        return new InfixExpression<boolean, ExprC<typeof obj>, "govaluate">(
            "in",
            this.literal(key),
            obj,
            typeToken<boolean>()
        ) as any;
    }

    keys<T extends Record<string, any>>(obj: BaseExpression<T>) {
        return fn<string[], ExpressionType, "complicatedExpression">("keys", obj);
    }

    // A "loose" version: works with unions, never collapses to `never`
    getLoose<
        E extends BaseExpression<Record<string, any>, any>,
        T  extends Record<string, any> = ResultOf<E>,
        const K extends Extract<KeysOfUnion<NonMissing<T>>, string> =
            Extract<KeysOfUnion<NonMissing<T>>, string>,
        TK extends Record<string, any> = MembersWithKey<NonMissing<T>, K>
    >(
        recordExpr: NoAny<E>,
        key: K
    ): BaseExpression<
        DeepStripUndefined<SegmentsValueStrict<TK, readonly [K]>>,
        "complicatedExpression"
    > {
        // Render as EXPR[KEY], same as your `get` node:
        return new RecordGetExpression<
            BaseExpression<TK, ExprC<E>>,
            TK,
            K
        >(recordExpr as unknown as BaseExpression<TK, ExprC<E>>, key) as any;
    }

    get<
        E extends BaseExpression<PlainRecord, any>,
        T extends PlainRecord = ResultOfExpr<E>,
        const K extends Extract<keyof NonMissing<T>, string> = Extract<keyof NonMissing<T>, string>
    >(
        // NoAny<E> makes this parameter become `never` if ResultOf<E> is `any`
        recordExpr: NoAny<E>,
        key: K
    ): BaseExpression<SegmentsValueStrict<T, readonly [K]>, "complicatedExpression"> {
        return new RecordGetExpression<E, T, K>(recordExpr, key);
    }

    /**
     * sprig.dig — safely look up nested fields by key segments.
     * If a default is provided, it's returned when any segment is missing.
     *
     * Example:
     *   expr.dig(userExpr, ["profile", "email"], "N/A")
     */
    dig<
        T extends Record<string, NonSerializedPlainObject>,
        D extends Record<string, any> = UnwrapSerialize<NonMissing<T>>,
        const S extends DictKeySegmentsCore<D> = DictKeySegmentsCore<D>
    >(
        sourceDict: AllowLiteralOrExpression<T, any>,
        segs: S,
        defaultValue: AllowLiteralOrExpression<DigValue<T, S>>
    ): BaseExpression<DigValue<T, S>, "complicatedExpression"> {
        const source = toExpression(sourceDict);

        const keyExprs = (segs as readonly string[]).map(seg =>
            widenComplexity(this.literal(seg))
        ) as readonly BaseExpression<string, "complicatedExpression">[];

        const argsComp: readonly BaseExpression<any, "complicatedExpression">[] = [
            ...keyExprs,
            widenComplexity(toExpression(defaultValue)),
            widenComplexity(source)
        ];

        return new FunctionExpression<
            DigValue<T, S>, any, "complicatedExpression", "complicatedExpression"
        >("sprig.dig", argsComp);
    }

    jsonPathStrict<
        T extends Record<string, any>,
        K extends Extract<keyof NonMissing<T>, string>
    >(
        source: BaseExpression<Serialized<T>, any>,
        key: K
    ): BaseExpression<SegmentsValueStrict<T, readonly [K]>, "complicatedExpression">;
    jsonPathStrict<
        T extends Record<string, any>,
        S extends KeySegments<T>
    >(
        source: BaseExpression<Serialized<T>, any>,
        ...segs: S
    ): BaseExpression<SegmentsValueStrict<T, S>, "complicatedExpression">;
    jsonPathStrict<
        T extends Serialized<Record<string, any>>,
        K extends Extract<KeysOfUnion<UnwrapSerialized<T>>, string>
    >(
        source: BaseExpression<T, any>,
        key: K
    ): BaseExpression<SegmentsValueStrict<UnwrapSerialized<T>, readonly [K]>, "complicatedExpression">;
    jsonPathStrict(
        source: BaseExpression<Serialized<any>, any>,
        ...segs: readonly unknown[]
    ): BaseExpression<any, "complicatedExpression"> {
        const path = _segmentsToPath(segs);
        return new RecordFieldSelectExpression(source, path) as any;
    }

    omit<
        E extends BaseExpression<PlainRecord, any>,
        T extends PlainRecord = ResultOfExpr<E>,
        const Keys extends readonly (Extract<keyof NonMissing<T>, string>)[] = readonly (Extract<keyof NonMissing<T>, string>)[]
    >(
        recordExpr: NoAny<E>,
        ...keys: Keys
    ): BaseExpression<Omit<NonMissing<T>, Keys[number]>, "complicatedExpression"> {
        const keyExprs = keys.map(k =>
            widenComplexity(this.literal(k))
        ) as readonly BaseExpression<string, "complicatedExpression">[];

        const args: readonly BaseExpression<any, "complicatedExpression">[] = [
            widenComplexity(recordExpr),
            ...keyExprs
        ];

        return new FunctionExpression<
            Omit<NonMissing<T>, Keys[number]>,
            any,
            "complicatedExpression",
            "complicatedExpression"
        >("sprig.omit", args);
    }

    deserializeRecord<R extends PlainObject, CIn extends ExpressionType>(
        data: BaseExpression<Serialized<R>, CIn>
    ): BaseExpression<R, "complicatedExpression">;
    deserializeRecord<T extends Serialized<PlainObject>, CIn extends ExpressionType>(
        data: BaseExpression<T, CIn>
    ): BaseExpression<UnwrapSerialized<T>, "complicatedExpression">;
    deserializeRecord<R extends PlainObject, CIn extends ExpressionType>(
        data: BaseExpression<Serialized<R>, CIn>
    ) {
        return fn<R, CIn, "complicatedExpression">("fromJSON", data);
    }


    serialize<R extends PlainObject, CIn extends ExpressionType>(data: BaseExpression<R,CIn>) {
        return fn<Serialized<R>,CIn,"complicatedExpression">("toJSON", data);
    }

    toString<
        R extends PlainObject,
        CIn extends ExpressionType
    >(data: AllowLiteralOrExpression<R,CIn>) {
        return fn<string,CIn,"complicatedExpression">("string", toExpression(data));
    }

    stringToRecord<
        R extends PlainObject,
        CIn extends ExpressionType
    >(capture: TypeToken<R>, data: AllowLiteralOrExpression<string,CIn>) {
        return fn<R,CIn,"complicatedExpression">("fromJSON", toExpression(data));
    }

    recordToString<T extends AggregateType, CIn extends ExpressionType>(
        data: AllowLiteralOrExpression<T, CIn>
    ): BaseExpression<string, "complicatedExpression"> & BaseExpression<Serialized<T>, "complicatedExpression"> {
        return fn<Serialized<T>,CIn,"complicatedExpression">("toJSON", toExpression(data)) as any;
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
        >("sprig.merge", [left, right] as const);
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

    length<T extends PlainObject>(arr: BaseExpression<T[]|string>) {
        return fn<number, ExpressionType, "complicatedExpression">("len", arr);
    }

    last<T extends PlainObject>(arr: BaseExpression<T[]>) {
        return fn<T, ExpressionType, "complicatedExpression">("last", arr);
    }

    // Parameter functions
    taskData<T extends PlainObject>(
        taskType: TaskType,
        taskName: string,
        key: string
    ): SimpleExpression<T> {
        return new TaskDataExpression<T>(taskType, taskName, key);
    }

    getWorkflowValue(value: WORKFLOW_VALUES) {
        return new WorkflowValueExpression(value);
    }

    // Utility functions
    fromBase64<CIn extends ExpressionType>(data: AllowLiteralOrExpression<string,CIn>) {
        return fn<string,CIn>("fromBase64", toExpression(data));
    }

    toBase64<CIn extends ExpressionType>(data: AllowLiteralOrExpression<string,CIn>) {
        return fn<string,CIn>("toBase64", toExpression(data));
    }
}

export const expr = new ExprBuilder();

export default expr;


// This function and the next tie into the renderer
export function makeDirectTypeProxy<T extends (boolean|number|NonSerializedPlainObject)>(value: BaseExpression<T>): T {
    return new UnquotedTypeWrapper(value) as any as T;
}

// This function and the next tie into the renderer
export function makeStringTypeProxy<T extends string>(value: AllowLiteralOrExpression<T>): T {
    return value as any as T;
}
