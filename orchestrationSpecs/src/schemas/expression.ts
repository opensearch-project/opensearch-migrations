import {DeepWiden, MissingField, PlainObject} from "@/schemas/plainObject";
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
export type AnyExpression<T extends PlainObject>      = BaseExpression<T, ExpressionType>;

// Helper type to allow both literal values and expressions
export type AllowLiteralOrExpression<T extends PlainObject> =
    T | BaseExpression<T, any>;

export function isExpression(v: unknown): v is BaseExpression<any, any> {
    return v instanceof BaseExpression;
}

export function toExpression<T extends PlainObject>(
    v: AllowLiteralOrExpression<T>
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

// BEGIN basicExpressions.ts


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
        super("null_coalesce");
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

export class NotExpression<C extends ExpressionType> extends BaseExpression<boolean, C> {
    constructor(public readonly boolValue: BaseExpression<boolean, C>) {
        super("not");
    }
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



// Begin jsonExpressions.ts

export class SerializeJson extends BaseExpression<string, "complicatedExpression"> {
    constructor(public readonly data: BaseExpression<Record<any, any>>) {
        super("serialize_json");
    }
}

export class DeserializeJson<T extends PlainObject> extends BaseExpression<T, "complicatedExpression"> {
    constructor(public readonly data: BaseExpression<string>) {
        super("deserialize_json");
    }
}


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

// ===== ADD: expression node for constructing a dict ========================

export class DictMakeExpression<
    N extends Record<string, BaseExpression<any, any>>
> extends BaseExpression<ResultRecord<N>, ComplexityOfRecord<N>> {
    constructor(public readonly entries: N) {
        super("dict_make");
    }
}

/**
 * Strongly-typed Sprig-like dict constructor.
 * Accepts a record whose values can be literals or expressions.
 * - Infers the resulting object shape and value types.
 * - Result complexity is "complicated" if any value is complicated.
 */
export function makeDict<R extends Record<string, AllowLiteralOrExpression<PlainObject>>>(
    entries: R
): BaseExpression<
    { [K in keyof R]: ResultOf<NormalizeValue<R[K]>> },
    ComplexityOfRecord<NormalizeRecord<R>>
> {
    const normalized = Object.fromEntries(
        Object.entries(entries).map(([k, v]) => {
            const e = isExpression(v) ? v : expr.literal(v);
            return [k, e];
        })
    ) as NormalizeRecord<R>;

    return new DictMakeExpression(normalized) as BaseExpression<
        { [K in keyof R]: ResultOf<NormalizeValue<R[K]>> },
        ComplexityOfRecord<NormalizeRecord<R>>
    >;
}

// -------- Type utilities for merging two result records --------

type Keys<A, B> = keyof A | keyof B;

type MergeTwo<A extends Record<string, any>, B extends Record<string, any>> = {
    [K in Keys<A, B>]:
    K extends keyof B
        ? (K extends keyof A ? A[K] | B[K] : B[K])
        : (K extends keyof A ? A[K] : never)
};

// -------- Expression node for merging two dicts --------

export class DictMergeExpression<
    L extends BaseExpression<Record<string, any>, CL>,
    R extends BaseExpression<Record<string, any>, CR>,
    CL extends ExpressionType = ExprC<L>,
    CR extends ExpressionType = ExprC<R>
> extends BaseExpression<
    MergeTwo<ResultOf<L>, ResultOf<R>>,
    "complicatedExpression" // I don't think when expressions can run sprig functions
    //(CL | CR) extends "complicatedExpression" ? "complicatedExpression" : "govaluate"
> {
    constructor(public readonly left: L, public readonly right: R) {
        super("dict_merge");
    }
}

// -------- Public API --------------------------------------------------------

/**
 * Merge two dictionary expressions (right wins at runtime; types become a union where keys overlap).
 * - Keeps precise key sets from both inputs.
 * - Value types for overlapping keys are unioned: A[K] | B[K].
 * - Complexity widens if either side is "complicatedExpression".
 */
export function mergeDicts<
    L extends BaseExpression<Record<string, any>, any>,
    R extends BaseExpression<Record<string, any>, any>
>(
    left: L,
    right: R
): BaseExpression<
    MergeTwo<ResultOf<L>, ResultOf<R>>,
    (ExprC<L> | ExprC<R>) extends "complicatedExpression" ? "complicatedExpression" : "govaluate"
> {
    return new DictMergeExpression(left, right) as any;
}


// Everything else is for jsonPath

export class RecordFieldSelectExpression<T, P, E> extends BaseExpression<any, "complicatedExpression"> {
    constructor(public readonly source: E, public readonly path: P) { super("path"); }
}

/*** SEGMENT / TUPLE NAVIGATION FOR EXPRESSIONS (TYPE-ONLY) ***/
// --- helpers ----------------------------------------------------

// union helpers
type AnyTrue<U> = Extract<U, true> extends never ? false : true;

// Is the key K missing in any member of union T?
type MissingInAny<T, K extends PropertyKey> =
    AnyTrue<T extends any ? (K extends keyof T ? false : true) : never>;

// Is the key K present-but-optional in any member of union T?
type OptionalInAny<T, K extends PropertyKey> =
    AnyTrue<T extends any ? (K extends keyof T ? ({} extends Pick<T, K> ? true : false) : false) : never>;

// already have this, but ensure it's here:
export type NonMissing<T> = Exclude<T, MissingField>;
type HasMissing<T> = Extract<T, MissingField> extends never ? false : true;

// property type with undefined removed (members lacking K contribute never)
type PropTypeNoUndef<T, K extends PropertyKey> =
    T extends any
        ? (K extends keyof T ? StripUndefined<T[K]> : never)
        : never;

// tuple/index helpers unchanged...

// --- segments over NON-MISSING values only ----------------------

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

// --- missing propagation flag (do NOT union MissingField into T) -

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

// ---- STRICT value evaluator over NON-MISSING T -----------------

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
    T,                                      // must be NonMissing<…>
    S extends readonly unknown[],
    AccMissing extends boolean
> =
    S extends []
        ? MaybeMissing<AccMissing, T>
        : S extends readonly [infer H, ...infer R]
            ? H extends number
                ? T extends readonly (infer U)[]
                    ? ValueAtSegsMissing<U, Extract<R, readonly unknown[]>, NextMissingAfterIndex<T, H, AccMissing>>
                    : MissingField                      // indexing non-array
                : H extends PropertyKey
                    ? ValueAtSegsMissing<
                        PropTypeNoUndef<T, H>,
                        Extract<R, readonly unknown[]>,
                        NextMissingAfterProp<T, H, AccMissing>
                    >
                    : MissingField
            : never;

// START with the “source may already be missing” flag; keep T non-missing while recursing.
export type SegmentsValueMissing<T, S extends readonly unknown[]> =
    ValueAtSegsMissing<NonMissing<T>, S, HasMissing<T>>;


/** Render a path string from segments (type is irrelevant; purely cosmetic). */
function _segmentsToPath(segs: readonly unknown[]): string {
    return segs.map(s => typeof s === "number" ? `[${s}]` : String(s)).join(".");
}

export function jsonPathLoose<
    T extends Record<string, any>,
    K extends Extract<keyof NonMissing<T>, string>
>(
    source: BaseExpression<T, any>,
    key: K
): BaseExpression<SegmentsValueMissing<T, readonly [K]>, "complicatedExpression">;

// variadic overload
export function jsonPathLoose<
    T extends Record<string, any>,
    S extends KeySegments<T>
>(
    source: BaseExpression<T, any>,
    ...segs: S
): BaseExpression<SegmentsValueMissing<T, S>, "complicatedExpression">;

// Single implementation
export function jsonPathLoose(
    source: BaseExpression<any, any>,
    ...segs: readonly unknown[]
): BaseExpression<any, "complicatedExpression"> {
    const path = _segmentsToPath(segs);
    return new RecordFieldSelectExpression(source, path) as any;
}


///

export function jsonPathStrict<
    T extends Record<string, any>,
    K extends Extract<keyof NonMissing<T>, string>
>(
    source: BaseExpression<T, any>,
    key: K
): BaseExpression<SegmentsValueStrict<T, readonly [K]>, "complicatedExpression">;

// variadic overload
export function jsonPathStrict<
    T extends Record<string, any>,
    S extends KeySegments<T>
>(
    source: BaseExpression<T, any>,
    ...segs: S
): BaseExpression<SegmentsValueStrict<T, S>, "complicatedExpression">;

// Single implementation
export function jsonPathStrict(
    source: BaseExpression<any, any>,
    ...segs: readonly unknown[]
): BaseExpression<any, "complicatedExpression"> {
    const path = _segmentsToPath(segs);
    return new RecordFieldSelectExpression(source, path) as any;
}


// Begin arrayExpressions.ts

export class ArrayLengthExpression<
    E extends BaseExpression<any[], CE>,
    CE extends ExpressionType = ExprC<E>
> extends BaseExpression<number, CE> {
    constructor(public readonly array: E) { super("array_length"); }
}

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

export type WORKFLOW_VALUES =
    "name"|"mainEntrypoint"|"serviceAccountName"|"uid"|"labels.json"|"creationTimestamp"|"priority"|"duration"|"scheduledTime";

export class FromParameterExpression<T extends PlainObject>
    extends BaseExpression<T, "govaluate"> {
    constructor(
        public readonly source: ParameterSource,
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

export class FromBase64Expression extends BaseExpression<string, "complicatedExpression"> {
    constructor(public readonly data: BaseExpression<string>) {
        super("from_base64");
    }
}

export class ToBase64Expression extends BaseExpression<string, "complicatedExpression"> {
    constructor(public readonly data: BaseExpression<string>) {
        super("to_base64");
    }
}

export const literal = <T extends PlainObject>(v: T): SimpleExpression<DeepWiden<T>> =>
    new LiteralExpression<DeepWiden<T>>(v as DeepWiden<T>);

export const asString = <E extends BaseExpression<any, any>>(e: E): BaseExpression<string, ExprC<E>> =>
    new AsStringExpression(e);

export function nullCoalesce<T extends PlainObject>(
    v: BaseExpression<T | MissingField, any>,
    d: AllowLiteralOrExpression<DeepWiden<T>>
) {
    return new NullCoalesce<T>(v, d);
}

/**
 * Pretty unsafe, but this is here at least until we have a smarter ternary in place.
 * If one knows that something can't be missing, because it's already been checked, this is the way out
 */
export function cast<FROM extends PlainObject>(v: BaseExpression<FROM>) {
    return {
        to<TO extends PlainObject>(): BaseExpression<TO> {
            return v as unknown as BaseExpression<TO>;
        }
    };
}

export const concat = <ES extends readonly BaseExpression<string, any>[]>(...es: ES): BaseExpression<string, "govaluate"> =>
    new ConcatExpression(es);

export const concatWith = <ES extends readonly BaseExpression<string, any>[]>(sep: string, ...es: ES): BaseExpression<string, "govaluate"> =>
    new ConcatExpression(es, sep);

export function ternary<
    B extends BaseExpression<boolean, any>,
    L extends BaseExpression<any, any>,
    R extends BaseExpression<ResultOf<L>, any>
>(cond: B, whenTrue: L, whenFalse: R): BaseExpression<ResultOf<L>, WidenComplexity3<ExprC<B>, ExprC<L>, ExprC<R>>>;
export function ternary<
    B extends BaseExpression<boolean, any>,
    R extends BaseExpression<any, any>,
    L extends BaseExpression<ResultOf<R>, any>
>(cond: B, whenTrue: L, whenFalse: R): BaseExpression<ResultOf<R>, WidenComplexity3<ExprC<B>, ExprC<L>, ExprC<R>>>;
export function ternary(cond: any, whenTrue: any, whenFalse: any): BaseExpression<any, any> {
    return new TernaryExpression(cond, whenTrue, whenFalse);
}

const _equals = <
    T extends Scalar,
    L extends BaseExpression<T, any>,
    R extends BaseExpression<T, any>
>(l: L, r: R): BaseExpression<boolean, WidenExpressionComplexity2<L, R>> =>
    new ComparisonExpression<T, L, R>("==", l, r);

export const equals = _equals as {
    <L extends BaseExpression<number, any>, R extends BaseExpression<number, any>>(
        l: NoAny<L>, r: NoAny<R>
    ): BaseExpression<boolean, WidenExpressionComplexity2<L, R>>;
    <L extends BaseExpression<string, any>, R extends BaseExpression<string, any>>(
        l: NoAny<L>, r: NoAny<R>
    ): BaseExpression<boolean, WidenExpressionComplexity2<L, R>>;
};

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

export function not<C extends ExpressionType>(data: BaseExpression<boolean, C>): BaseExpression<boolean, C> {
    return new NotExpression(data);
}

export const add = <
    L extends BaseExpression<number, any>,
    R extends BaseExpression<number, any>
>(l: L, r: R): BaseExpression<number, WidenComplexity2<ExprC<L>, ExprC<R>>> =>
    new ArithmeticExpression<L, R>("+", l, r);

export const subtract = <
    L extends BaseExpression<number, CL>,
    R extends BaseExpression<number, CR>,
    CL extends ExpressionType = ExprC<L>,
    CR extends ExpressionType = ExprC<R>
>(l: L, r: R): BaseExpression<number, WidenComplexity2<CL, CR>> =>
    new ArithmeticExpression("-", l, r);

export function recordToString(data: BaseExpression<Record<string, any>>) {
    return new SerializeJson(data);
}

export function stringToRecord<T extends PlainObject = never>(typeToken: TypeToken<T>, data: BaseExpression<string>) {
    return new DeserializeJson<T>(data);
}



export const length = <E extends BaseExpression<any[], any>>(arr: E): BaseExpression<number, ExprC<E>> =>
    new ArrayLengthExpression(arr);

export function index<
    A extends BaseExpression<any[], any>,
    I extends BaseExpression<number, any>
>(
    arr: A,
    i: I
): BaseExpression<ElemFromArrayExpr<A>, WidenComplexity2<ExprC<A>, ExprC<I>>> {
    return new ArrayIndexExpression(arr, i);
}

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

export function toArray<ES extends readonly unknown[]>(
    ...items: ES
): BaseExpression<
    ElemOfNormalized<ES>[],
    ComplexityOfNormalized<ES>
> {
    const normalized = items.map((it) =>
        isExpression(it) ? it : literal(it as PlainObject)
    ) as NormalizeTuple<ES>;

    // Keep the precise tuple type (don't let it widen to BaseExpression<any, any>[])
    type N = Extract<typeof normalized, readonly BaseExpression<any, any>[]>;

    // Construct with the precise element tuple so complexity computes correctly
    return new ArrayMakeExpression<N>(normalized as unknown as N) as BaseExpression<
        ElemOfNormalized<ES>[],
        ComplexityOfNormalized<ES>
    >;
}


// Begin parameterExpressions.ts

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


export const taskData = <T extends PlainObject>(
    taskType: TaskType,
    taskName: string,
    key: string
): SimpleExpression<T> =>
    new TaskDataExpression<T>(taskType, taskName, key);

export const taskOutput = <T extends PlainObject, Label extends TaskType>(
    taskType: Label,
    taskName: string,
    parameterName: string,
    def?: OutputParamDef<T>
): SimpleExpression<DeepWiden<T>> =>
    new FromParameterExpression<DeepWiden<T>>(
        {
            kind: `${taskType}_output` as any,
            [`${taskType.substring(0,taskType.length-1)}Name`]: taskName,
            parameterName
        } as ParameterSource,
        def as any
    );

export function taskDataAsExpression(
    taskType: TaskType,
    taskName: string,
    key: "id"|"status"
) {
    return new TaskDataExpression(taskType, taskName, key);
}

export const loopItem = <T extends PlainObject>(): TemplateExpression<T> =>
    new LoopItemExpression<T>();

export function getWorkflowValue(value: WORKFLOW_VALUES) {
    return new WorkflowValueExpression(value);
}

// These go in basicExpressions
export function fromBase64(data: BaseExpression<string>) {
    return new FromBase64Expression(data);
}

export function toBase64(data: BaseExpression<string>) {
    return new ToBase64Expression(data);
}

// begin templateExpression.ts

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

function fillTemplate<T extends string>(
    template: T,
    replacements: TemplateReplacements<T>
): BaseExpression<string, "complicatedExpression"> {
    const normalizedReplacements: NormalizedReplacements<T> = {} as any;

    for (const [key, value] of Object.entries(replacements)) {
        const typedValue = value as AllowLiteralOrExpression<string>;
        normalizedReplacements[key as keyof NormalizedReplacements<T>] =
            typeof typedValue === 'string' ? literal(typedValue) : typedValue;
    }

    return new TemplateReplacementExpression(template, normalizedReplacements);
}

// Namespace object for convenient access
export const expr = {
    // Core functions
    literal,
    asString,
    concat,
    concatWith,
    ternary,
    toArray,
    nullCoalesce,
    cast,

    // Comparisons
    equals,
    lessThan,
    greaterThan,
    not,

    // Arithmetic
    add,
    subtract,

    // JSON Handling
    jsonPathLoose,
    jsonPathStrict,
    stringToRecord,
    recordToString,
    makeDict,
    mergeDicts,

    // Array operations
    length,
    index,

    // Parameter functions
    workflowParam,
    inputParam,
    taskData,
    taskOutput,
    loopItem,

    // utility
    getWorkflowValue,
    fromBase64,
    toBase64,
    fillTemplate
} as const;

export default expr;
