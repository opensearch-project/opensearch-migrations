import {expectTypeOf} from "expect-type";
import {BaseExpression, DeepWiden, expr} from "../src";

describe("expression type contracts", () => {
    it("expr.literal() produces the correct value/complexity types", () => {
        const a = expr.literal("a");
        const five = expr.literal(5);

        expectTypeOf(a).toEqualTypeOf<BaseExpression<string, "govaluate">>();
        expectTypeOf(five).toEqualTypeOf<BaseExpression<number, "govaluate">>();
    });

    it("expr.equals() enforces same-scalar comparisons and returns boolean", () => {
        const a = expr.literal("a");// as SimpleExpression<string>;
        const b = expr.literal("b");
        const five = expr.literal(5);// as SimpleExpression<number>;
        const ten = expr.literal(10);

        let ba: BaseExpression<DeepWiden<any>, "govaluate"> = expr.literal({});
        const foo = expr.literal({foo: 2});
        //const foo2: IsAny<typeof ba> = true;

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
        expr.equals(five, expr.literal({ obj: true }));
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
        const obj = expr.literal({
            a: {hello: "world"},
            b: {good: "night"},
            c: [{ c2: { c3: "foundIt" }}]
        });

        const v1 = expr.jsonPathStrict(expr.recordToString(obj), "c");
        const v2 = expr.index(v1, expr.literal(0));
        const v3 = expr.jsonPathStrict(expr.recordToString(v2), "c2", "c3");
        expectTypeOf(v3).toExtend<BaseExpression<string>>();
        expectTypeOf(v3).not.toBeAny();

        const result = expr.jsonPathStrict(expr.recordToString(obj), "a");
        expectTypeOf(result).toExtend<BaseExpression<{hello: string}>>();
    });

    it("dig infers precise value type and enforces default type", () => {
        type PerIndices = {
            metadata?: {
                indices: string[];
                loggingConfigurationOverrideConfigMap?: { mode?: string };
            };
        };

        const cfg = expr.deserializeRecord(
            expr.recordToString(
                expr.literal({} as PerIndices) as BaseExpression<PerIndices>));

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

        // wrong default type is rejected at compile time
        // @ts-expect-error default must be string[]
        expr.dig(cfg, ["metadata", "indices"] as const, expr.literal("nope"));

        // another shape: optional nested string with default
        type Obj = { a?: { hello?: string } };
        const o = expr.literal({} as Obj);
        const v = expr.dig(o, ["a", "hello"] as const, "world");
        expectTypeOf(v).toExtend<BaseExpression<string>>();

        // unknown key is rejected by the tuple path typing
        // @ts-expect-error "metadatas" is not a valid key
        expr.dig(cfg, ["metadatas", "indices"] as const, [] as string[]);
    });
});
