"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
var src_1 = require("../src");
describe("paramsFns runtime validation - comprehensive", function () {
    // Shared external templates with different parameter configurations
    // Template with no parameters
    var noParamsTemplate = src_1.WorkflowBuilder.create({ k8sResourceName: "no-params" })
        .addTemplate("noParams", function (b) { return b
        .addSteps(function (x) { return x; })
        .addExpressionOutput("result", function (inputs) { return "success"; }); })
        .getFullScope();
    // Template with only required parameters
    var requiredOnlyTemplate = src_1.WorkflowBuilder.create({ k8sResourceName: "required-only" })
        .addTemplate("requiredOnly", function (b) { return b
        .addRequiredInput("reqStr", (0, src_1.typeToken)())
        .addRequiredInput("reqNum", (0, src_1.typeToken)())
        .addSteps(function (x) { return x; })
        .addExpressionOutput("result", function (inputs) { return "success"; }); })
        .getFullScope();
    // Template with only optional parameters
    var optionalOnlyTemplate = src_1.WorkflowBuilder.create({ k8sResourceName: "optional-only" })
        .addTemplate("optionalOnly", function (b) { return b
        .addOptionalInput("optStr", function (s) { return "defaultStr"; })
        .addOptionalInput("optNum", function (n) { return 42; })
        .addSteps(function (x) { return x; })
        .addExpressionOutput("result", function (inputs) { return "success"; }); })
        .getFullScope();
    // Template with mixed required and optional parameters
    var mixedParamsTemplate = src_1.WorkflowBuilder.create({ k8sResourceName: "mixed-arams" })
        .addTemplate("mixedParams", function (b) { return b
        .addRequiredInput("reqStr", (0, src_1.typeToken)())
        .addOptionalInput("optNum", function (n) { return 0; })
        .addRequiredInput("reqBool", (0, src_1.typeToken)())
        .addOptionalInput("optStr", function (s) { return "default"; })
        .addSteps(function (x) { return x; })
        .addExpressionOutput("result", function (inputs) { return "success"; }); })
        .getFullScope();
    // Base workflow builder for internal templates
    var baseWorkflow = src_1.WorkflowBuilder.create({ k8sResourceName: "test-workflow" })
        // Internal template with no parameters
        .addTemplate("internalNoParams", function (t) { return t
        .addSteps(function (b) { return b; })
        .addExpressionOutput("result", function (inputs) { return "internal_success"; }); })
        // Internal template with only required parameters
        .addTemplate("internalRequiredOnly", function (t) { return t
        .addRequiredInput("reqStr", (0, src_1.typeToken)())
        .addRequiredInput("reqNum", (0, src_1.typeToken)())
        .addSteps(function (b) { return b; })
        .addExpressionOutput("result", function (inputs) { return "internal_success"; }); })
        // Internal template with only optional parameters
        .addTemplate("internalOptionalOnly", function (t) { return t
        .addOptionalInput("optStr", function (s) { return "defaultStr"; })
        .addOptionalInput("optNum", function (n) { return 42; })
        .addSteps(function (b) { return b; })
        .addExpressionOutput("result", function (inputs) { return "internal_success"; }); })
        // Internal template with mixed parameters
        .addTemplate("internalMixedParams", function (t) { return t
        .addRequiredInput("reqStr", (0, src_1.typeToken)())
        .addOptionalInput("optNum", function (n) { return 0; })
        .addRequiredInput("reqBool", (0, src_1.typeToken)())
        .addOptionalInput("optStr", function (s) { return "default"; })
        .addSteps(function (b) { return b; })
        .addExpressionOutput("result", function (inputs) { return "internal_success"; }); });
    // Tests for External Templates - No Parameters
    describe("External Templates - No Parameters", function () {
        it("should accept valid call with no parameters", function () {
            baseWorkflow.addTemplate("testNoParamsValid", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", noParamsTemplate, "noParams", 
                // @ts-expect-error — spurious property registration should be rejected
                function (c) { return c.register({}); });
                return step;
            }); });
        });
        it("should reject call with spurious parameters", function () {
            baseWorkflow.addTemplate("testNoParamsSpurious", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", noParamsTemplate, "noParams", 
                // @ts-expect-error — spurious property registration should be rejected
                function (c) { return c.register({ spuriousField: "should error" }); });
                return step;
            }); });
        });
    });
    // Tests for External Templates - Required Only Parameters
    describe("External Templates - Required Only Parameters", function () {
        it("should accept valid call with all required parameters", function () {
            baseWorkflow.addTemplate("testRequiredValid", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", requiredOnlyTemplate, "requiredOnly", function (c) { return c.register({
                    reqStr: "validString",
                    reqNum: 123
                }); });
                return step;
            }); });
        });
        it("should reject call missing required parameters", function () {
            baseWorkflow.addTemplate("testRequiredMissing", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", requiredOnlyTemplate, "requiredOnly", 
                // @ts-expect-error — missing required parameter reqNum
                function (c) { return c.register({
                    reqStr: "validString"
                    // reqNum is missing
                }); });
                return step;
            }); });
        });
        it("should reject call with wrong parameter types", function () {
            baseWorkflow.addTemplate("testRequiredWrongType", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", requiredOnlyTemplate, "requiredOnly", function (c) { return c.register({
                    reqStr: "validString",
                    // @ts-expect-error — wrong type for reqNum
                    reqNum: "shouldBeNumber"
                }); });
                return step;
            }); });
        });
        it("should reject call with spurious parameters", function () {
            baseWorkflow.addTemplate("testRequiredSpurious", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", requiredOnlyTemplate, "requiredOnly", function (c) { return c.register({
                    reqStr: "validString",
                    reqNum: 123,
                    // @ts-expect-error — spurious property should be rejected
                    spuriousField: "should error"
                }); });
                return step;
            }); });
        });
        it("debug CallerParams: External RequiredOnly spurious", function () {
            var _debugInputsCheck = {};
            // @ts-expect-error — spurious CallerParams property should be rejected
            var _debugCallerParamsBad = { reqStr: "validString", reqNum: 123, spuriousField: "should error" };
            var _debugCallerParams = { reqStr: "validString", reqNum: 123 };
        });
    });
    // Tests for External Templates - Optional Only Parameters
    describe("External Templates - Optional Only Parameters", function () {
        it("should accept valid call with no paramsFn", function () {
            baseWorkflow.addTemplate("testOptionalNoParamsFn", function (t) { return t
                .addSteps(function (g) {
                // paramsFn omitted entirely
                var step = g.addStep("stepNoParamsFn", optionalOnlyTemplate, "optionalOnly");
                return step;
            }); });
        });
        it("should accept valid call with no parameters", function () {
            baseWorkflow.addTemplate("testOptionalEmpty", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", optionalOnlyTemplate, "optionalOnly", function (c) { return c.register({}); });
                return step;
            }); });
        });
        it("should accept valid call with some optional parameters", function () {
            baseWorkflow.addTemplate("testOptionalPartial", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", optionalOnlyTemplate, "optionalOnly", function (c) { return c.register({
                    optStr: "customString"
                    // optNum omitted, should use default
                }); });
                return step;
            }); });
        });
        it("should accept valid call with all optional parameters", function () {
            baseWorkflow.addTemplate("testOptionalAll", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", optionalOnlyTemplate, "optionalOnly", function (c) { return c.register({
                    optStr: "customString",
                    optNum: 999
                }); });
                return step;
            }); });
        });
        it("should reject call with wrong parameter types", function () {
            baseWorkflow.addTemplate("testOptionalWrongType", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", optionalOnlyTemplate, "optionalOnly", function (c) { return c.register({
                    optStr: "validString",
                    // @ts-expect-error — wrong type for optNum
                    optNum: "shouldBeNumber"
                }); });
                return step;
            }); });
        });
        it("should reject call with optional and spurious parameters", function () {
            baseWorkflow.addTemplate("testOptionalSpurious", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", optionalOnlyTemplate, "optionalOnly", function (c) { return c.register({
                    optStr: "validString",
                    // @ts-expect-error — spurious property should be rejected
                    spuriousField: "should error"
                }); });
                return step;
            }); });
        });
        it("should reject call with spurious parameters", function () {
            baseWorkflow.addTemplate("testOptionalSpurious_2", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", optionalOnlyTemplate, "optionalOnly", function (c) { return c.register({
                    // @ts-expect-error — spurious property should be rejected
                    spuriousField: "should error"
                }); });
                return step;
            }); });
        });
        it("debug CallerParams: External OptionalOnly spurious-with-optional", function () {
            var _debugInputsCheck = {};
            // @ts-expect-error — spurious CallerParams property should be rejected
            var _debugCallerParamsBad = { optStr: "validString", spuriousField: "should error" };
            var _debugCallerParams2 = { optStr: "validString" };
        });
        it("debug CallerParams: External OptionalOnly spurious-only", function () {
            var _debugInputsCheck = {};
            // @ts-expect-error — spurious CallerParams property should be rejected
            var _debugCallerParamsBad = { spuriousField: "should error" };
            var _debugCallerParams = {};
        });
    });
    // Tests for External Templates - Mixed Parameters
    describe("External Templates - Mixed Parameters", function () {
        it("should accept valid call with all required parameters only", function () {
            baseWorkflow.addTemplate("testMixedRequiredOnly", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", mixedParamsTemplate, "mixedParams", function (c) { return c.register({
                    reqStr: "validString",
                    reqBool: true
                    // optional parameters omitted
                }); });
                return step;
            }); });
        });
        it("should accept valid call with all parameters", function () {
            baseWorkflow.addTemplate("testMixedAll", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", mixedParamsTemplate, "mixedParams", function (c) { return c.register({
                    reqStr: "validString",
                    reqBool: true,
                    optNum: 42,
                    optStr: "customOptional"
                }); });
                return step;
            }); });
        });
        it("should reject call missing required parameters", function () {
            baseWorkflow.addTemplate("testMixedMissingRequired", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", mixedParamsTemplate, "mixedParams", 
                // @ts-expect-error — missing required parameter reqBool
                function (c) { return c.register({
                    reqStr: "validString",
                    optNum: 42
                    // reqBool is missing
                }); });
                return step;
            }); });
        });
        it("should reject call with wrong types", function () {
            baseWorkflow.addTemplate("testMixedWrongTypes", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", mixedParamsTemplate, "mixedParams", function (c) { return c.register({
                    // @ts-expect-error — wrong types for multiple parameters
                    reqStr: 123, // should be string
                    // @ts-expect-error — wrong types for multiple parameters
                    reqBool: "notBoolean", // should be boolean
                    // @ts-expect-error — wrong types for multiple parameters
                    optNum: true // should be number
                }); });
                return step;
            }); });
        });
        it("should reject call with spurious parameters", function () {
            baseWorkflow.addTemplate("testMixedSpurious", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", mixedParamsTemplate, "mixedParams", function (c) { return c.register({
                    reqStr: "validString",
                    reqBool: true,
                    // @ts-expect-error — spurious property should be rejected
                    spuriousField: "should error",
                    anotherBadField: 999
                }); });
                return step;
            }); });
        });
        it("debug CallerParams: External Mixed spurious", function () {
            var _debugInputsCheck = {};
            var _debugCallerParamsBad = {
                reqStr: "validString",
                reqBool: true,
                // @ts-expect-error — spurious CallerParams property should be rejected
                spuriousField: "should error",
                anotherBadField: 999
            };
            var _debugCallerParams = {
                reqStr: "validString",
                reqBool: true
            };
        });
    });
    // Tests for Internal Templates - No Parameters
    describe("Internal Templates - No Parameters", function () {
        it("should accept valid call with no parameters", function () {
            baseWorkflow.addTemplate("testInternalNoParamsValid", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", src_1.INTERNAL, "internalNoParams", 
                // @ts-expect-error — spurious property should be rejected
                function (c) { return c.register({}); });
                return step;
            }); });
        });
        it("should reject call with spurious parameters", function () {
            baseWorkflow.addTemplate("testInternalNoParamsSpurious", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", src_1.INTERNAL, "internalNoParams", 
                // @ts-expect-error — spurious property should be rejected
                function (c) { return c.register({
                    spuriousField: "should error"
                }); });
                return step;
            }); });
        });
    });
    // Tests for Internal Templates - Required Only Parameters
    describe("Internal Templates - Required Only Parameters", function () {
        it("should accept valid call with all required parameters", function () {
            baseWorkflow.addTemplate("testInternalRequiredValid", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", src_1.INTERNAL, "internalRequiredOnly", function (c) { return c.register({
                    reqStr: "validString",
                    reqNum: 456
                }); });
                return step;
            }); });
        });
        it("should reject call missing required parameters", function () {
            baseWorkflow.addTemplate("testInternalRequiredMissing", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", src_1.INTERNAL, "internalRequiredOnly", 
                // @ts-expect-error — missing required parameter reqStr
                function (c) { return c.register({
                    reqNum: 456
                    // reqStr is missing
                }); });
                return step;
            }); });
        });
        it("should reject call with wrong parameter types", function () {
            baseWorkflow.addTemplate("testInternalRequiredWrongType", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", src_1.INTERNAL, "internalRequiredOnly", function (c) { return c.register({
                    // @ts-expect-error — wrong type for reqStr
                    reqStr: 123,
                    reqNum: 456
                }); });
                return step;
            }); });
        });
        it("should reject call with spurious parameters", function () {
            baseWorkflow.addTemplate("testInternalRequiredSpurious", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", src_1.INTERNAL, "internalRequiredOnly", function (c) { return c.register({
                    reqStr: "validString",
                    reqNum: 456,
                    // @ts-expect-error — spurious property should be rejected
                    spuriousField: "should error"
                }); });
                return step;
            }); });
        });
        it("debug CallerParams: Internal RequiredOnly spurious", function () {
            var _debugInputsCheck = {};
            // @ts-expect-error — spurious CallerParams property should be rejected
            var _debugCallerParamsBad = { reqStr: "validString", reqNum: 456, spuriousField: "should error" };
            var _debugCallerParams = { reqStr: "validString", reqNum: 456 };
        });
    });
    // Tests for Internal Templates - Optional Only Parameters
    describe("Internal Templates - Optional Only Parameters", function () {
        it("should accept valid call with no paramsFn", function () {
            baseWorkflow.addTemplate("testInternalOptionalNoParamsFn", function (t) { return t
                .addSteps(function (g) {
                // paramsFn omitted entirely
                var step = g.addStep("stepNoParamsFn", src_1.INTERNAL, "internalOptionalOnly");
                return step;
            }); });
        });
        it("should accept valid call with no parameters", function () {
            baseWorkflow.addTemplate("testInternalOptionalEmpty", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", src_1.INTERNAL, "internalOptionalOnly", function (c) { return c.register({}); });
                return step;
            }); });
        });
        it("should accept valid call with some optional parameters", function () {
            baseWorkflow.addTemplate("testInternalOptionalPartial", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", src_1.INTERNAL, "internalOptionalOnly", function (c) { return c.register({
                    optNum: 789
                    // optStr omitted
                }); });
                return step;
            }); });
        });
        it("should reject call with wrong parameter types", function () {
            baseWorkflow.addTemplate("testInternalOptionalWrongType", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", src_1.INTERNAL, "internalOptionalOnly", function (c) { return c.register({
                    // @ts-expect-error — wrong type for optStr
                    optStr: 999,
                    optNum: 789
                }); });
                return step;
            }); });
        });
        it("should reject call with spurious parameters", function () {
            baseWorkflow.addTemplate("testInternalOptionalSpurious", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", src_1.INTERNAL, "internalOptionalOnly", function (c) { return c.register({
                    optStr: "validString",
                    // @ts-expect-error — spurious property should be rejected
                    spuriousField: "should error"
                }); });
                return step;
            }); });
        });
        it("debug CallerParams: Internal OptionalOnly spurious", function () {
            var _debugInputsCheck = {};
            // @ts-expect-error — spurious CallerParams property should be rejected
            var _debugCallerParamsBad = { optStr: "validString", spuriousField: "should error" };
            var _debugCallerParams = { optStr: "validString" };
        });
    });
    // Tests for Internal Templates - Mixed Parameters
    describe("Internal Templates - Mixed Parameters", function () {
        it("should accept valid call with all required parameters only", function () {
            baseWorkflow.addTemplate("testInternalMixedRequiredOnly", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", src_1.INTERNAL, "internalMixedParams", function (c) { return c.register({
                    reqStr: "validString",
                    reqBool: false
                    // optional parameters omitted
                }); });
                return step;
            }); });
        });
        it("should accept valid call with all parameters", function () {
            baseWorkflow.addTemplate("testInternalMixedAll", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", src_1.INTERNAL, "internalMixedParams", function (c) { return c.register({
                    reqStr: "validString",
                    reqBool: false,
                    optNum: 123,
                    optStr: "customOptional"
                }); });
                return step;
            }); });
        });
        it("should reject call missing required parameters", function () {
            baseWorkflow.addTemplate("testInternalMixedMissingRequired", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", src_1.INTERNAL, "internalMixedParams", 
                // @ts-expect-error — missing required parameter reqStr
                function (c) { return c.register({
                    reqBool: false,
                    optStr: "optional"
                    // reqStr is missing
                }); });
                return step;
            }); });
        });
        it("should reject call with wrong types", function () {
            baseWorkflow.addTemplate("testInternalMixedWrongTypes", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", src_1.INTERNAL, "internalMixedParams", function (c) { return c.register({
                    // @ts-expect-error — wrong types for parameters
                    reqStr: ["array"],
                    // @ts-expect-error — wrong types for parameters
                    reqBool: "notBoolean",
                    // @ts-expect-error — wrong types for parameters
                    optNum: { object: true }
                }); });
                return step;
            }); });
        });
        it("should reject invalid callback usage patterns", function () {
            baseWorkflow.addTemplate("testInternalMixedInvalidCallbacks", function (t) { return t
                .addSteps(function (g) {
                // Valid usage for comparison
                var validStep = g.addStep("validStep", src_1.INTERNAL, "internalMixedParams", function (c) { return c.register({ reqStr: "valid", reqBool: true }); });
                // TODO - add all of these other negative tests
                // Invalid patterns are documented here for reference (not executed):
                // (steps, register) => register({reqBool: true, reqStr: "", spurious: 1})
                // (steps, register) => register({})
                // (steps, register) => Symbol('fake')
                // (steps, register) => {}
                // (steps, register) => undefined
                // (steps, register) => 9
                return validStep;
            }); });
        });
        it("should reject call with spurious parameters", function () {
            baseWorkflow.addTemplate("testInternalMixedSpurious", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", src_1.INTERNAL, "internalMixedParams", function (c) { return c.register({
                    reqStr: "validString",
                    reqBool: false,
                    // @ts-expect-error — spurious property should be rejected
                    spuriousField: "should error",
                    anotherInvalidField: true
                }); });
                return step;
            }); });
        });
        it("debug CallerParams: Internal Mixed spurious", function () {
            var _debugInputsCheck = {};
            var _debugCallerParamsBad = {
                reqStr: "validString",
                reqBool: false,
                // @ts-expect-error — spurious CallerParams property should be rejected
                spuriousField: "should error",
                anotherInvalidField: true
            };
            var _debugCallerParams = {
                reqStr: "validString",
                reqBool: false
            };
        });
    });
    // Edge case tests
    describe("Edge Cases", function () {
        it("should handle chaining steps with parameter dependencies", function () {
            baseWorkflow.addTemplate("testChaining", function (t) { return t
                .addSteps(function (g) {
                var step1 = g.addStep("step1", requiredOnlyTemplate, "requiredOnly", function (c) { return c.register({
                    reqStr: "initial",
                    reqNum: 1
                }); });
                var step2 = step1.addStep("step2", src_1.INTERNAL, "internalRequiredOnly", function (c) { return c.register({
                    reqStr: c.steps.step1.outputs.result, // using output from previous step
                    reqNum: 2
                }); });
                return step2;
            }); });
        });
        it("should reject chaining with wrong output reference types", function () {
            baseWorkflow.addTemplate("testChainingWrongType", function (t) { return t
                .addSteps(function (g) {
                var step1 = g.addStep("step1", requiredOnlyTemplate, "requiredOnly", function (c) { return c.register({
                    reqStr: "initial",
                    reqNum: 1
                }); });
                var step2 = step1.addStep("step2", src_1.INTERNAL, "internalRequiredOnly", function (c) { return c.register({
                    reqStr: "valid",
                    // @ts-expect-error — using string output where number is expected
                    reqNum: c.steps.step1.result // result is string, reqNum expects number
                }); });
                return step2;
            }); });
        });
        it("should handle multiple spurious fields", function () {
            baseWorkflow.addTemplate("testMultipleSpurious", function (t) { return t
                .addSteps(function (g) {
                var step = g.addStep("step1", mixedParamsTemplate, "mixedParams", function (c) { return c.register({
                    reqStr: "valid",
                    reqBool: true,
                    // @ts-expect-error — spurious properties should be rejected
                    spurious1: "error1",
                    spurious2: 42,
                    spurious3: { nested: "object" },
                    spurious4: ["array", "values"]
                }); });
                return step;
            }); });
        });
        it("debug CallerParams: Edge multiple spurious", function () {
            var _debugInputsCheck = {};
            var _debugCallerParamsBad = {
                reqStr: "valid",
                reqBool: true,
                // @ts-expect-error — spurious CallerParams properties should be rejected
                spurious1: "error1",
                spurious2: 42,
                spurious3: { nested: "object" },
                spurious4: ["array", "values"]
            };
            var _debugCallerParams = {
                reqStr: "valid",
                reqBool: true
            };
        });
    });
});
