import {expectTypeOf, IsAny} from "expect-type";
import {
    BaseExpression,
    expr,
} from "../src/schemas/expression";
import {DeepWiden} from "../src/schemas/plainObject";

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

        const v1 = expr.jsonPathLoose(obj, "c");
        const v2 = expr.index(v1, expr.literal(0));
        const v3 = expr.jsonPathLoose(expr.jsonPathLoose(v2, "c2"), "c3");
        expectTypeOf(v3).toExtend<BaseExpression<string>>();
        expectTypeOf(v3).not.toBeAny();

        const result = expr.jsonPathLoose(obj, "a");
        expectTypeOf(result).toExtend<BaseExpression<{hello: string}>>();
        console.log(result);
    });
});
