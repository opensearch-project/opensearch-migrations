import {InputParamDef, OutputParamDef, TypeToken} from "@/schemas/parameterSchemas";
import {DeepWiden, PlainObject} from "@/schemas/plainObject";

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

export class AsStringExpression<
    E extends BaseExpression<any, CE>,
    CE extends ExpressionType = ExprC<E>
> extends BaseExpression<string, CE> {
    constructor(public readonly source: E) { super("as_string"); }
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

type IndexSeg = `[${number}]`;

type _KeyPathsObj<T> =
    {
        [K in Extract<keyof T, string>]:
        | K
        | (KeyPaths<T[K]> extends never ? never : `${K}.${KeyPaths<T[K]>}`)
    }[Extract<keyof T, string>];

type _KeyPathsArray<T> =
    | IndexSeg
    | (KeyPaths<T> extends never ? never : `${IndexSeg}.${KeyPaths<T>}`);

export type KeyPaths<T> =
    T extends readonly (infer U)[] ? _KeyPathsArray<U> :
        T extends object               ? _KeyPathsObj<T>   :
            never;

export type PathValue<T, P extends string> =
    P extends keyof T ? T[P] :
        P extends `${infer K}.${infer Rest}`
            ? K extends keyof T
                ? T[K] extends Record<string, any>
                    ? T[K] extends readonly any[]
                        ? PathValue<T[K], Rest>
                        : any
                    : PathValue<T[K], Rest>
                : never
            :
            P extends `[${infer _Index}]${infer Rest}`
                ? T extends readonly (infer U)[]
                    ? Rest extends "" ? U
                        : Rest extends `.${infer R}` ? PathValue<U, R> : never
                    : never
                : never;

export class RecordFieldSelectExpression<
    T extends Record<string, any>,    // Explicit record type
    P extends KeyPaths<T>,            // Path constrained to T
    E extends BaseExpression<T, any>  // Source constrained to return T
> extends BaseExpression<PathValue<T, P>, "complicatedExpression"> {
    constructor(public readonly source: E, public readonly path: P) { super("path"); }
}

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
    | { kind: "workflow",   parameterName: string }
    | { kind: "input",      parameterName: string }
    | { kind: "step_output", stepName: string, parameterName: string }
    | { kind: "task_output", taskName: string, parameterName: string };

export type WORKFLOW_VALUES =
    "name"|"mainEntrypoint"|"serviceAccountName"|"uid"|"labels.json"|"creationTimestamp"|"priority"|"duration"|"scheduledTime";

export class FromParameterExpression<T extends PlainObject>
    extends BaseExpression<T, "govaluate"> {
    constructor(
        public readonly source: ParameterSource,
        public readonly paramDef?: InputParamDef<T, any> | OutputParamDef<T>
    ) { super("parameter"); }
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

export function selectField<
    T extends Record<string, any>,
    K extends KeyPaths<T>
>(
    source: BaseExpression<T, any>,
    p: K
): RecordFieldSelectExpression<T, K, BaseExpression<T, any>> {
    return new RecordFieldSelectExpression(source, p);
}

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
        isExpression(it) ? it : literal(it as any)
    ) as NormalizeTuple<ES>;

    // Keep the precise tuple type (don't let it widen to BaseExpression<any, any>[])
    type N = Extract<typeof normalized, readonly BaseExpression<any, any>[]>;

    // Construct with the precise element tuple so complexity computes correctly
    return new ArrayMakeExpression<N>(normalized as unknown as N) as BaseExpression<
        ElemOfNormalized<ES>[],
        ComplexityOfNormalized<ES>
    >;
}

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

export function getWorkflowValue(value: WORKFLOW_VALUES) {
    return new WorkflowValueExpression(value);
}

export function fromBase64(data: BaseExpression<string>) {
    return new FromBase64Expression(data);
}

export function toBase64(data: BaseExpression<string>) {
    return new ToBase64Expression(data);
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

    // Comparisons
    equals,
    lessThan,
    greaterThan,
    not,

    // Arithmetic
    add,
    subtract,

    // JSON Handling
    selectField,
    stringToRecord,
    recordToString,

    // Array operations
    length,
    index,

    // Parameter functions
    workflowParam,
    inputParam,
    stepOutput,
    taskOutput,
    loopItem,

    // utility
    getWorkflowValue,
    fromBase64,
    toBase64,
    fillTemplate
} as const;

// Export the namespace as defaultexport default expr;
export default expr;
