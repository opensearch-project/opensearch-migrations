"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
var expect_type_1 = require("expect-type");
var src_1 = require("../src");
describe("paramsFns runtime validation", function () {
    var doNothingTemplate = src_1.WorkflowBuilder.create({ k8sResourceName: "do-nothing" })
        .addTemplate("opStr", function (b) { return b
        .addOptionalInput("optionalA", function (s) { return "str"; })
        .addSteps(function (x) { return x; }); })
        .addTemplate("reqNum", function (b) { return b
        .addRequiredInput("reqNum", (0, src_1.typeToken)())
        .addSteps(function (sb) { return sb; }); })
        .addTemplate("opEnum", function (b) { return b
        .addOptionalInput("opEnum", function (s) { return "a"; })
        .addSteps(function (x) { return x; }); })
        .addTemplate("reqEnum", function (b) { return b
        .addRequiredInput("reqEnum", (0, src_1.typeToken)())
        .addSteps(function (sb) { return sb; }); })
        .getFullScope();
    var templateBuilder = new src_1.TemplateBuilder({}, {}, {}, {});
    it("optional and required are correct", function () {
        templateBuilder.addSteps(function (sb) {
            sb.addStep("init", doNothingTemplate, "opStr", function (c) { return c.register({ optionalA: "hi" }); });
            return sb;
        });
    });
    it("spurious parameters are rejected", function () {
        templateBuilder.addSteps(function (sb) {
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "opStr", function (c) { return c.register({ notReal: "1" }); });
            var next = sb.addStep("init", doNothingTemplate, "opStr", function (c) { return c.register({}); });
            return next;
        });
    });
    it("required param is required and must match type", function () {
        templateBuilder.addSteps(function (sb) {
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "reqNum", function (c) { return c.register({}); });
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "reqNum", function (c) { return c.register({ reqNum: "1" }); });
            sb.addStep("init", doNothingTemplate, "reqNum", function (c) { return c.register({ reqNum: 1 }); });
            return sb;
        });
    });
    it("optional enum param types work", function () {
        templateBuilder.addSteps(function (sb) {
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "opEnum", function (c) { return c.register({ opEnum: "aaa" }); });
            sb.addStep("init", doNothingTemplate, "opEnum", function (c) { return c.register({ opEnum: "a" }); });
            return sb;
        });
    });
    it("required enum param types work", function () {
        templateBuilder.addSteps(function (sb) {
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "reqEnum", function (c) { return c.register({ reqEnum: "aaa" }); });
            sb.addStep("init", doNothingTemplate, "reqEnum", function (c) { return c.register({ reqEnum: "a" }); });
            sb.addStep("init", doNothingTemplate, "reqEnum", function (c) { return c.register({ reqEnum: src_1.expr.literal("a") }); });
            return sb;
        });
    });
    it("defineParam widens appropriately", function () {
        (0, expect_type_1.expectTypeOf)((0, src_1.defineParam)({ expression: "hi" })).toEqualTypeOf();
        // @ts-expect-error — mixed scalar types should be rejected
        (0, expect_type_1.expectTypeOf)((0, src_1.defineParam)({ expression: "hi" })).toEqualTypeOf();
        (0, expect_type_1.expectTypeOf)((0, src_1.defineParam)({ expression: "hi" })).toEqualTypeOf();
    });
    it("workflow param types can be used with input params", function () {
        var standaloneParam = (0, src_1.defineParam)({ expression: "str" });
        var wpsBuilder = src_1.WorkflowBuilder.create({ k8sResourceName: "workflow-param-sample" })
            .addParams({
            wpStr: (0, src_1.defineParam)({ expression: "str" }),
            wpEnum: (0, src_1.defineParam)({ expression: "b" }),
            wpStandalone: standaloneParam
        });
        wpsBuilder.addTemplate("test", function (sb) { return sb.addSteps(function (sb) {
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "reqEnum", function (c) { return c.register({ reqEnum: sb.workflowInputs.wpStr }); });
            sb.addStep("init", doNothingTemplate, "reqEnum", function (c) { return c.register({ reqEnum: sb.workflowInputs.wpEnum }); });
            return sb;
        }); });
    });
});
