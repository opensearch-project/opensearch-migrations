"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
var expect_type_1 = require("expect-type");
var src_1 = require("../src");
describe("expression type contracts", function () {
    it("expr.literal() produces the correct value/complexity types", function () {
        var a = src_1.expr.literal("a");
        var five = src_1.expr.literal(5);
        (0, expect_type_1.expectTypeOf)(a).toEqualTypeOf();
        (0, expect_type_1.expectTypeOf)(five).toEqualTypeOf();
    });
    it("expr.equals() enforces same-scalar comparisons and returns boolean", function () {
        var a = src_1.expr.literal("a"); // as SimpleExpression<string>;
        var b = src_1.expr.literal("b");
        var five = src_1.expr.literal(5); // as SimpleExpression<number>;
        var ten = src_1.expr.literal(10);
        var ba = src_1.expr.literal({});
        var foo = src_1.expr.literal({ foo: 2 });
        //const foo2: IsAny<typeof ba> = true;
        // ok: string vs string
        (0, expect_type_1.expectTypeOf)(src_1.expr.equals(a, b)).toEqualTypeOf();
        // ok: number vs number (complexity type may widen)
        (0, expect_type_1.expectTypeOf)(src_1.expr.equals(five, ten)).toMatchTypeOf();
        // @ts-expect-error — mixed scalar types should be rejected
        src_1.expr.equals(five, ba);
        // @ts-expect-error — mixed scalar types should be rejected
        src_1.expr.equals(a, five);
        // NOT ok: string vs number must fail to type-check
        // @ts-expect-error — mixed scalar types should be rejected
        src_1.expr.equals(a, five);
        // NOT ok: number vs object must fail to type-check
        // @ts-expect-error — non-scalar right-hand side should be rejected
        src_1.expr.equals(five, src_1.expr.literal({ obj: true }));
    });
    it("ternary() preserves branch value type and widens complexity", function () {
        var five = src_1.expr.literal(5);
        var ten = src_1.expr.literal(10);
        var yes = src_1.expr.literal("yes");
        var no = src_1.expr.literal("no");
        var t = src_1.expr.ternary(src_1.expr.equals(five, ten), yes, no);
        // value type is string; complexity depends on children
        (0, expect_type_1.expectTypeOf)(t).toMatchTypeOf();
    });
    it("path works", function () {
        var obj = src_1.expr.literal({
            a: { hello: "world" },
            b: { good: "night" },
            c: [{ c2: { c3: "foundIt" } }]
        });
        var v1 = src_1.expr.jsonPathStrict(src_1.expr.recordToString(obj), "c");
        var v2 = src_1.expr.index(v1, src_1.expr.literal(0));
        var v3 = src_1.expr.jsonPathStrict(src_1.expr.recordToString(v2), "c2", "c3");
        (0, expect_type_1.expectTypeOf)(v3).toExtend();
        (0, expect_type_1.expectTypeOf)(v3).not.toBeAny();
        var result = src_1.expr.jsonPathStrict(src_1.expr.recordToString(obj), "a");
        (0, expect_type_1.expectTypeOf)(result).toExtend();
    });
    it("dig infers precise value type and enforces default type", function () {
        var cfg = src_1.expr.deserializeRecord(src_1.expr.recordToString(src_1.expr.literal({})));
        // precise type from path ["metadata","indices"] → string[]
        var indices = src_1.expr.dig(cfg, ["metadata", "indices"], []);
        (0, expect_type_1.expectTypeOf)(indices).not.toBeAny();
        (0, expect_type_1.expectTypeOf)(indices).toMatchTypeOf();
        (0, expect_type_1.expectTypeOf)().toMatchTypeOf(indices);
        // If your matcher supports exact equality, keep this too:
        // expectTypeOf(indices).toEqualTypeOf<BaseExpression<string[], "complicatedExpression">>();
        // Plain TS assignment check (compile-time only)
        var _ok = indices; // should compile
        // Negative: wrong target type must fail
        // @ts-expect-error - BaseExpression<string[]> is NOT assignable to BaseExpression<number[]>
        var _nope = indices;
        // default can be a raw literal (must be string[])
        var t2 = src_1.expr.dig(cfg, ["metadata", "indices"], []);
        (0, expect_type_1.expectTypeOf)(t2).toExtend();
        // works when source is Serialized<T> (e.g., JSON string → fromJSON)
        var cfgSerialized = src_1.expr.recordToString(cfg); // BaseExpression<string> & BaseExpression<Serialized<PerIndices>>
        var t3 = src_1.expr.dig(src_1.expr.deserializeRecord(cfgSerialized), ["metadata", "indices"], []);
        (0, expect_type_1.expectTypeOf)(t3).toExtend();
        // wrong default type is rejected at compile time
        // @ts-expect-error default must be string[]
        src_1.expr.dig(cfg, ["metadata", "indices"], src_1.expr.literal("nope"));
        var o = src_1.expr.literal({});
        var v = src_1.expr.dig(o, ["a", "hello"], "world");
        (0, expect_type_1.expectTypeOf)(v).toExtend();
        // unknown key is rejected by the tuple path typing
        // @ts-expect-error "metadatas" is not a valid key
        src_1.expr.dig(cfg, ["metadatas", "indices"], []);
    });
});
