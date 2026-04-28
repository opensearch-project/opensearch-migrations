import {expectTypeOf} from "expect-type";
import {BaseExpression, expr, INTERNAL, makeParameterLoop, typeToken, WorkflowBuilder} from '../../src';

describe("expression type contracts", () => {
    it("expr.literal() produces the correct value/complexity types", () => {
        const a = expr.literal("a");
        const five = expr.literal(5);
        const arr = expr.literal(["a", "b"]);

        expectTypeOf(a).toEqualTypeOf<BaseExpression<string, "govaluate">>();
        expectTypeOf(five).toEqualTypeOf<BaseExpression<number, "govaluate">>();
        expectTypeOf(arr).toEqualTypeOf<BaseExpression<string[], "govaluate">>();
    });

    it("expr.literal() rejects record values", () => {
        // Use expr.makeDict(...) for Argo object dictionaries so the renderer emits
        // sprig.dict(...) instead of naked JSON object syntax inside expressions.
        // @ts-expect-error - record literals must use expr.makeDict(...)
        expr.literal({foo: 2});

        // @ts-expect-error - nested record literals must use expr.makeDict(...)
        expr.literal([{foo: 2}]);
    });

    it("expr.equals() enforces same-scalar comparisons and returns boolean", () => {
        const a = expr.literal("a");// as SimpleExpression<string>;
        const b = expr.literal("b");
        const five = expr.literal(5);// as SimpleExpression<number>;
        const ten = expr.literal(10);

        const ba = expr.makeDict({});

        // ok: string vs string
        expectTypeOf(expr.equals(a, b)).toEqualTypeOf<BaseExpression<boolean, "govaluate">>();
        // ok: number vs number (complexity type may widen)
        expectTypeOf(expr.equals(five, ten)).toMatchTypeOf<BaseExpression<boolean, any>>();

        // @ts-expect-error — mixed scalar types should be rejected
        expr.equals(five, ba);
        // @ts-expect-error — mixed scalar types should be rejected
        expr.equals(a, five);

        // NOT ok: string vs number must fail to type-check
        // @ts-expect-error — mixed scalar types should be rejected
        expr.equals(a, five);

        // NOT ok: number vs object must fail to type-check
        // @ts-expect-error — non-scalar right-hand side should be rejected
        expr.equals(five, expr.makeDict({obj: true}));
    });

    it("ternary() preserves branch value type and widens complexity", () => {
        const five = expr.literal(5);
        const ten = expr.literal(10);
        const yes = expr.literal("yes");
        const no = expr.literal("no");

        const t = expr.ternary(expr.equals(five, ten), yes, no);
        // value type is string; complexity depends on children
        expectTypeOf(t).toMatchTypeOf<BaseExpression<string, any>>();
    });

    it("path works", () => {
        const obj = expr.makeDict({
            a: expr.makeDict({hello: "world"}),
            b: expr.makeDict({good: "night"}),
            c: expr.toArray(expr.makeDict({c2: expr.makeDict({c3: "foundIt"})}))
        });

        const v1 = expr.jsonPathStrict(expr.recordToString(obj), "c");
        const v2 = expr.index(v1, expr.literal(0));
        const v3 = expr.jsonPathStrict(expr.recordToString(v2), "c2", "c3");
        expectTypeOf(v3).toExtend<BaseExpression<string>>();
        expectTypeOf(v3).not.toBeAny();

        const result = expr.jsonPathStrict(expr.recordToString(obj), "a");
        expectTypeOf(result).toExtend<BaseExpression<{hello: string}>>();
    });

    it("dig infers precise value type and unions in the default type", () => {
        type PerIndices = {
            metadata?: {
                indices: string[];
                loggingConfigurationOverrideConfigMap?: { mode?: string };
            };
        };

        const cfg = expr.cast(expr.makeDict({})).to<PerIndices>();

        // precise type from path ["metadata","indices"] → string[]
        const indices= expr.dig(
            cfg,
            ["metadata", "indices"],
            [] as string[]
        );
        expectTypeOf(indices).not.toBeAny();
        expectTypeOf(indices).toMatchTypeOf<BaseExpression<string[], any>>();
        expectTypeOf<BaseExpression<string[], "complicatedExpression">>().toMatchTypeOf(indices);
        // If your matcher supports exact equality, keep this too:
        // expectTypeOf(indices).toEqualTypeOf<BaseExpression<string[], "complicatedExpression">>();

        // Plain TS assignment check (compile-time only)
        const _ok: BaseExpression<string[]> = indices; // should compile

        // Negative: wrong target type must fail
        // @ts-expect-error - BaseExpression<string[]> is NOT assignable to BaseExpression<number[]>
        const _nope: BaseExpression<number[]> = indices;



        // default can be a raw literal (must be string[])
        const t2 = expr.dig(cfg, ["metadata", "indices"] as const, [] as string[]);
        expectTypeOf(t2).toExtend<BaseExpression<string[]>>();

        // works when source is Serialized<T> (e.g., JSON string → fromJSON)
        const cfgSerialized = expr.recordToString(cfg); // BaseExpression<string> & BaseExpression<Serialized<PerIndices>>
        const t3 = expr.dig(
            expr.deserializeRecord(cfgSerialized),
            ["metadata", "indices"] as const,
            [] as string[]
        );
        expectTypeOf(t3).toExtend<BaseExpression<string[]>>();

        // A default can intentionally differ from the path type. Runtime sprig.dig
        // returns either the value at the path or the default when the path is absent.
        const t4 = expr.dig(cfg, ["metadata", "indices"] as const, expr.literal("nope"));
        expectTypeOf(t4).toExtend<BaseExpression<string[] | string>>();

        // another shape: optional nested string with default
        type Obj = { a?: { hello?: string } };
        const o = expr.cast(expr.makeDict({})).to<Obj>();
        const v = expr.dig(o, ["a", "hello"] as const, "world");
        expectTypeOf(v).toExtend<BaseExpression<string>>();

        // unknown key is rejected by the tuple path typing
        // @ts-expect-error "metadatas" is not a valid key
        expr.dig(cfg, ["metadatas", "indices"] as const, [] as string[]);
    });

    it("withParam hybrid loop items cannot be serialized directly", () => {
        const wf = WorkflowBuilder.create({ k8sResourceName: "withparam-hybrid-negative" })
            .addTemplate("consumer", t => t
                .addRequiredInput("payload", typeToken<{ name: string; nested: { value: string } }>())
                .addSteps(s => s)
            )
            .addTemplate("main", t => t
                .addSteps(s => s
                    .addStep("loop", INTERNAL, "consumer", c => c.register({
                        // @ts-expect-error - withParam hybrid item must be normalized before serialize
                        payload: expr.serialize(c.item),
                    }), {
                        loopWith: makeParameterLoop(
                            expr.toArray(expr.makeDict({name: "a", nested: expr.makeDict({value: "b"})}))
                        )
                    })
                )
            );

        expectTypeOf(wf).not.toBeAny();
    });

    it("jsonPath outputs cannot be passed to deserializeRecord", () => {
        const src = expr.recordToString(expr.makeDict({
            outer: expr.makeDict({inner: "x"}),
            raw: "{\"a\":1}"
        }));

        // @ts-expect-error - fromJSON(jsonpath(...)) is disallowed
        expr.deserializeRecord(expr.jsonPathStrict(src, "outer"));

        // @ts-expect-error - fromJSON(jsonpath(...)) is disallowed, even with serialized hint
        expr.deserializeRecord(expr.jsonPathStrictSerialized(src, "outer"));
    });
});
