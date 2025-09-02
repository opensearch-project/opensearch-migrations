import {expectTypeOf, IsAny} from "expect-type";
import {
    BaseExpression,
    literal,
    equals,
    ternary, SimpleExpression, NoAny,
} from "../src/schemas/expression";
import {expectNever, expectNotType} from "tsd"; // adjust path if needed
import { fi } from "zod/locales";
import {DeepWiden} from "../src/schemas/plainObject";

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
});
