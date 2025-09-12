import {expectTypeOf, IsAny} from "expect-type";
import {
    BaseExpression,
    literal,
    jsonPathLoose,
    equals,
    ternary, SimpleExpression, NoAny, expr as EXPR,
} from "../src/schemas/expression";
import {DeepWiden} from "../src/schemas/plainObject";
import {selectInputsForRegister} from "@/schemas/taskBuilder";

describe("expression type contracts", () => {
    it("literal() produces the correct value/complexity types", () => {
        const a = literal("a");
        const five = literal(5);

        expectTypeOf(a).toEqualTypeOf<BaseExpression<string, "govaluate">>();
        expectTypeOf(five).toEqualTypeOf<BaseExpression<number, "govaluate">>();
    });

    it("equals() enforces same-scalar comparisons and returns boolean", () => {
        const a = literal("a");// as SimpleExpression<string>;
        const b = literal("b");
        const five = literal(5);// as SimpleExpression<number>;
        const ten = literal(10);

        let ba: BaseExpression<DeepWiden<any>, "govaluate"> = literal({});
        const foo = literal({foo: 2});
        //const foo2: IsAny<typeof ba> = true;

        // ok: string vs string
        expectTypeOf(equals(a, b)).toEqualTypeOf<BaseExpression<boolean, "govaluate">>();
        // ok: number vs number (complexity type may widen)
        expectTypeOf(equals(five, ten)).toMatchTypeOf<BaseExpression<boolean, any>>();

        // @ts-expect-error — mixed scalar types should be rejected
        equals(five, ba);
        // @ts-expect-error — mixed scalar types should be rejected
        equals(a, five);

        // NOT ok: string vs number must fail to type-check
        // @ts-expect-error — mixed scalar types should be rejected
        equals(a, five);

        // NOT ok: number vs object must fail to type-check
        // @ts-expect-error — non-scalar right-hand side should be rejected
        equals(five, literal({ obj: true }));
    });

    it("ternary() preserves branch value type and widens complexity", () => {
        const five = literal(5);
        const ten = literal(10);
        const yes = literal("yes");
        const no = literal("no");

        const t = ternary(equals(five, ten), yes, no);
        // value type is string; complexity depends on children
        expectTypeOf(t).toMatchTypeOf<BaseExpression<string, any>>();
    });

    it("path works", () => {
        const obj = literal({
            a: {hello: "world"},
            b: {good: "night"},
            c: [{ c2: { c3: "foundIt" }}]
        });

        const v1 = EXPR.jsonPathLoose(obj, "c");
        const v2 = EXPR.index(v1, EXPR.literal(0));
        const v3 = EXPR.jsonPathLoose(EXPR.jsonPathLoose(v2, "c2"), "c3");
        expectTypeOf(v3).toExtend<BaseExpression<string>>();
        expectTypeOf(v3).not.toBeAny();

        const result = jsonPathLoose(obj, "a");
        expectTypeOf(result).toExtend<BaseExpression<{hello: string}>>();
        console.log(result);
    });
});
