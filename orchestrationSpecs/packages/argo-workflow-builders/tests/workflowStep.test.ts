import {CallerParams, INTERNAL, typeToken, WorkflowBuilder} from "../src";

describe("paramsFns runtime validation - comprehensive", () => {
    // Shared external templates with different parameter configurations

    // Template with no parameters
    const noParamsTemplate = WorkflowBuilder.create({ k8sResourceName: "no-params"})
        .addTemplate("noParams", b=> b
            .addSteps(x=>x)
            .addExpressionOutput("result", inputs=>"success" as string)
        )
        .getFullScope();

    // Template with only required parameters
    const requiredOnlyTemplate = WorkflowBuilder.create({ k8sResourceName: "required-only"})
        .addTemplate("requiredOnly", b=> b
            .addRequiredInput("reqStr", typeToken<string>())
            .addRequiredInput("reqNum", typeToken<number>())
            .addSteps(x=>x)
            .addExpressionOutput("result", inputs=>"success" as string)
        )
        .getFullScope();

    // Template with only optional parameters
    const optionalOnlyTemplate = WorkflowBuilder.create({ k8sResourceName: "optional-only"})
        .addTemplate("optionalOnly", b=> b
            .addOptionalInput("optStr", s=>"defaultStr")
            .addOptionalInput("optNum", n=>42)
            .addSteps(x=>x)
            .addExpressionOutput("result", inputs=>"success" as string)
        )
        .getFullScope();

    // Template with mixed required and optional parameters
    const mixedParamsTemplate = WorkflowBuilder.create({ k8sResourceName: "mixed-arams"})
        .addTemplate("mixedParams", b=> b
            .addRequiredInput("reqStr", typeToken<string>())
            .addOptionalInput("optNum", n=>0)
            .addRequiredInput("reqBool", typeToken<boolean>())
            .addOptionalInput("optStr", s=>"default")
            .addSteps(x=>x)
            .addExpressionOutput("result", inputs=>"success" as string)
        )
        .getFullScope();

    // Base workflow builder for internal templates
    const baseWorkflow = WorkflowBuilder.create({ k8sResourceName: "test-workflow" })
        // Internal template with no parameters
        .addTemplate("internalNoParams", t => t
            .addSteps(b=>b)
            .addExpressionOutput("result", inputs=>"internal_success" as string)
        )
        // Internal template with only required parameters
        .addTemplate("internalRequiredOnly", t => t
            .addRequiredInput("reqStr", typeToken<string>())
            .addRequiredInput("reqNum", typeToken<number>())
            .addSteps(b=>b)
            .addExpressionOutput("result", inputs=>"internal_success" as string)
        )
        // Internal template with only optional parameters
        .addTemplate("internalOptionalOnly", t => t
            .addOptionalInput("optStr", s=>"defaultStr")
            .addOptionalInput("optNum", n=>42)
            .addSteps(b=>b)
            .addExpressionOutput("result", inputs=>"internal_success" as string)
        )
        // Internal template with mixed parameters
        .addTemplate("internalMixedParams", t => t
            .addRequiredInput("reqStr", typeToken<string>())
            .addOptionalInput("optNum", n=>0)
            .addRequiredInput("reqBool", typeToken<boolean>())
            .addOptionalInput("optStr", s=>"default")
            .addSteps(b=>b)
            .addExpressionOutput("result", inputs=>"internal_success" as string)
        );

    // Tests for External Templates - No Parameters
    describe("External Templates - No Parameters", () => {
        it("should accept valid call with no parameters", () => {
            baseWorkflow.addTemplate("testNoParamsValid", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", noParamsTemplate, "noParams",
                        // @ts-expect-error — spurious property registration should be rejected
                        c => c.register({})
                    );
                    return step;
                })
            );
        });

        it("should reject call with spurious parameters", () => {
            baseWorkflow.addTemplate("testNoParamsSpurious", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", noParamsTemplate, "noParams",
                        // @ts-expect-error — spurious property registration should be rejected
                        c => c.register({spuriousField: "should error"})
                    );
                    return step;
                })
            );
        });
    });

    // Tests for External Templates - Required Only Parameters
    describe("External Templates - Required Only Parameters", () => {
        it("should accept valid call with all required parameters", () => {
            baseWorkflow.addTemplate("testRequiredValid", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", requiredOnlyTemplate, "requiredOnly",
                        c => c.register({
                            reqStr: "validString",
                            reqNum: 123
                        }));
                    return step;
                })
            );
        });

        it("should reject call missing required parameters", () => {
            baseWorkflow.addTemplate("testRequiredMissing", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", requiredOnlyTemplate, "requiredOnly",
                        // @ts-expect-error — missing required parameter reqNum
                        c => c.register({
                            reqStr: "validString"
                            // reqNum is missing
                        }));
                    return step;
                })
            );
        });

        it("should reject call with wrong parameter types", () => {
            baseWorkflow.addTemplate("testRequiredWrongType", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", requiredOnlyTemplate, "requiredOnly",
                        c => c.register({
                            reqStr: "validString",
                            // @ts-expect-error — wrong type for reqNum
                            reqNum: "shouldBeNumber"
                        }));
                    return step;
                })
            );
        });

        it("should reject call with spurious parameters", () => {
            baseWorkflow.addTemplate("testRequiredSpurious", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", requiredOnlyTemplate, "requiredOnly",
                        c => c.register({
                            reqStr: "validString",
                            reqNum: 123,
                            // @ts-expect-error — spurious property should be rejected
                            spuriousField: "should error"
                        }));
                    return step;
                })
            );
        });

        it("debug CallerParams: External RequiredOnly spurious", () => {
            type Inputs = typeof requiredOnlyTemplate.templates.requiredOnly.inputs;
            const _debugInputsCheck: Inputs = {} as any;
            type TestCallerParams = CallerParams<Inputs>;
            // @ts-expect-error — spurious CallerParams property should be rejected
            const _debugCallerParamsBad: TestCallerParams = { reqStr: "validString", reqNum: 123, spuriousField: "should error" };
            const _debugCallerParams: TestCallerParams = { reqStr: "validString", reqNum: 123 };
        });
    });

    // Tests for External Templates - Optional Only Parameters
    describe("External Templates - Optional Only Parameters", () => {
        it("should accept valid call with no paramsFn", () => {
            baseWorkflow.addTemplate("testOptionalNoParamsFn", t => t
                .addSteps(g => {
                    // paramsFn omitted entirely
                    const step = g.addStep("stepNoParamsFn", optionalOnlyTemplate, "optionalOnly");
                    return step;
                })
            );
        });

        it("should accept valid call with no parameters", () => {
            baseWorkflow.addTemplate("testOptionalEmpty", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", optionalOnlyTemplate, "optionalOnly",
                        c=>c.register({}));
                    return step;
                })
            );
        });

        it("should accept valid call with some optional parameters", () => {
            baseWorkflow.addTemplate("testOptionalPartial", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", optionalOnlyTemplate, "optionalOnly",
                        c => c.register({
                            optStr: "customString"
                            // optNum omitted, should use default
                        }));
                    return step;
                })
            );
        });

        it("should accept valid call with all optional parameters", () => {
            baseWorkflow.addTemplate("testOptionalAll", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", optionalOnlyTemplate, "optionalOnly",
                        c => c.register({
                            optStr: "customString",
                            optNum: 999
                        }));
                    return step;
                })
            );
        });

        it("should reject call with wrong parameter types", () => {
            baseWorkflow.addTemplate("testOptionalWrongType", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", optionalOnlyTemplate, "optionalOnly",
                        c => c.register({
                            optStr: "validString",
                            // @ts-expect-error — wrong type for optNum
                            optNum: "shouldBeNumber"
                        })
                    );
                    return step;
                })
            );
        });

        it("should reject call with optional and spurious parameters", () => {
            baseWorkflow.addTemplate("testOptionalSpurious", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", optionalOnlyTemplate, "optionalOnly",
                        c => c.register({
                            optStr: "validString",
                            // @ts-expect-error — spurious property should be rejected
                            spuriousField: "should error"
                        })
                    );
                    return step;
                })
            );
        });

        it("should reject call with spurious parameters", () => {
            baseWorkflow.addTemplate("testOptionalSpurious_2", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", optionalOnlyTemplate, "optionalOnly",
                        c => c.register({
                            // @ts-expect-error — spurious property should be rejected
                            spuriousField: "should error"
                        })
                    );
                    return step;
                })
            );
        });

        it("debug CallerParams: External OptionalOnly spurious-with-optional", () => {
            type Inputs = typeof optionalOnlyTemplate.templates.optionalOnly.inputs;
            const _debugInputsCheck: Inputs = {} as any;
            type TestCallerParams = CallerParams<Inputs>;
            // @ts-expect-error — spurious CallerParams property should be rejected
            const _debugCallerParamsBad: TestCallerParams = { optStr: "validString", spuriousField: "should error" };
            const _debugCallerParams2: TestCallerParams = { optStr: "validString" };
        });

        it("debug CallerParams: External OptionalOnly spurious-only", () => {
            type Inputs = typeof optionalOnlyTemplate.templates.optionalOnly.inputs;
            const _debugInputsCheck: Inputs = {} as any;
            type TestCallerParams = CallerParams<Inputs>;
            // @ts-expect-error — spurious CallerParams property should be rejected
            const _debugCallerParamsBad: TestCallerParams = { spuriousField: "should error" };
            const _debugCallerParams: TestCallerParams = {};
        });
    });

    // Tests for External Templates - Mixed Parameters
    describe("External Templates - Mixed Parameters", () => {
        it("should accept valid call with all required parameters only", () => {
            baseWorkflow.addTemplate("testMixedRequiredOnly", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", mixedParamsTemplate, "mixedParams",
                        c => c.register({
                            reqStr: "validString",
                            reqBool: true
                            // optional parameters omitted
                        }));
                    return step;
                })
            );
        });

        it("should accept valid call with all parameters", () => {
            baseWorkflow.addTemplate("testMixedAll", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", mixedParamsTemplate, "mixedParams",
                        c => c.register({
                            reqStr: "validString",
                            reqBool: true,
                            optNum: 42,
                            optStr: "customOptional"
                        }));
                    return step;
                })
            );
        });

        it("should reject call missing required parameters", () => {
            baseWorkflow.addTemplate("testMixedMissingRequired", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", mixedParamsTemplate, "mixedParams",
                        // @ts-expect-error — missing required parameter reqBool
                        c => c.register({
                            reqStr: "validString",
                            optNum: 42
                            // reqBool is missing
                        }));
                    return step;
                })
            );
        });

        it("should reject call with wrong types", () => {
            baseWorkflow.addTemplate("testMixedWrongTypes", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", mixedParamsTemplate, "mixedParams",
                        c => c.register({
                            // @ts-expect-error — wrong types for multiple parameters
                            reqStr: 123, // should be string
                            // @ts-expect-error — wrong types for multiple parameters
                            reqBool: "notBoolean", // should be boolean
                            // @ts-expect-error — wrong types for multiple parameters
                            optNum: true // should be number
                        })
                    );
                    return step;
                })
            );
        });

        it("should reject call with spurious parameters", () => {
            baseWorkflow.addTemplate("testMixedSpurious", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", mixedParamsTemplate, "mixedParams",
                        c => c.register({
                            reqStr: "validString",
                            reqBool: true,
                            // @ts-expect-error — spurious property should be rejected
                            spuriousField: "should error",
                            anotherBadField: 999
                        }));
                    return step;
                })
            );
        });

        it("debug CallerParams: External Mixed spurious", () => {
            type Inputs = typeof mixedParamsTemplate.templates.mixedParams.inputs;
            const _debugInputsCheck: Inputs = {} as any;
            type TestCallerParams = CallerParams<Inputs>;
            const _debugCallerParamsBad: TestCallerParams = {
                reqStr: "validString",
                reqBool: true,
                // @ts-expect-error — spurious CallerParams property should be rejected
                spuriousField: "should error",
                anotherBadField: 999
            };
            const _debugCallerParams: TestCallerParams = {
                reqStr: "validString",
                reqBool: true
            };
        });
    });

    // Tests for Internal Templates - No Parameters
    describe("Internal Templates - No Parameters", () => {
        it("should accept valid call with no parameters", () => {
            baseWorkflow.addTemplate("testInternalNoParamsValid", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", INTERNAL, "internalNoParams",
                        // @ts-expect-error — spurious property should be rejected
                        c => c.register({}));
                    return step;
                })
            );
        });

        it("should reject call with spurious parameters", () => {
            baseWorkflow.addTemplate("testInternalNoParamsSpurious", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", INTERNAL, "internalNoParams",
                        // @ts-expect-error — spurious property should be rejected
                        c => c.register({
                            spuriousField: "should error"
                        }));
                    return step;
                })
            );
        });
    });

    // Tests for Internal Templates - Required Only Parameters
    describe("Internal Templates - Required Only Parameters", () => {
        it("should accept valid call with all required parameters", () => {
            baseWorkflow.addTemplate("testInternalRequiredValid", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", INTERNAL, "internalRequiredOnly",
                        c => c.register({
                            reqStr: "validString",
                            reqNum: 456
                        }));
                    return step;
                })
            );
        });

        it("should reject call missing required parameters", () => {
            baseWorkflow.addTemplate("testInternalRequiredMissing", t => t
                .addSteps(g => {
                    const step = g.addStep("step1",
                        INTERNAL, "internalRequiredOnly",
                        // @ts-expect-error — missing required parameter reqStr
                        c => c.register({
                            reqNum: 456
                            // reqStr is missing
                        }));
                    return step;
                })
            );
        });

        it("should reject call with wrong parameter types", () => {
            baseWorkflow.addTemplate("testInternalRequiredWrongType", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", INTERNAL, "internalRequiredOnly",
                        c => c.register({
                            // @ts-expect-error — wrong type for reqStr
                            reqStr: 123,
                            reqNum: 456
                        }));
                    return step;
                })
            );
        });

        it("should reject call with spurious parameters", () => {
            baseWorkflow.addTemplate("testInternalRequiredSpurious", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", INTERNAL, "internalRequiredOnly",
                        c => c.register({
                            reqStr: "validString",
                            reqNum: 456,
                            // @ts-expect-error — spurious property should be rejected
                            spuriousField: "should error"
                        }));
                    return step;
                })
            );
        });

        it("debug CallerParams: Internal RequiredOnly spurious", () => {
            type Inputs = typeof requiredOnlyTemplate.templates.requiredOnly.inputs;
            const _debugInputsCheck: Inputs = {} as any;
            type TestCallerParams = CallerParams<Inputs>;
            // @ts-expect-error — spurious CallerParams property should be rejected
            const _debugCallerParamsBad: TestCallerParams = { reqStr: "validString", reqNum: 456, spuriousField: "should error" };
            const _debugCallerParams: TestCallerParams = { reqStr: "validString", reqNum: 456 };
        });
    });

    // Tests for Internal Templates - Optional Only Parameters
    describe("Internal Templates - Optional Only Parameters", () => {
        it("should accept valid call with no paramsFn", () => {
            baseWorkflow.addTemplate("testInternalOptionalNoParamsFn", t => t
                .addSteps(g => {
                    // paramsFn omitted entirely
                    const step = g.addStep("stepNoParamsFn", INTERNAL, "internalOptionalOnly");
                    return step;
                })
            );
        });

        it("should accept valid call with no parameters", () => {
            baseWorkflow.addTemplate("testInternalOptionalEmpty", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", INTERNAL, "internalOptionalOnly", c => c.register({}));
                    return step;
                })
            );
        });

        it("should accept valid call with some optional parameters", () => {
            baseWorkflow.addTemplate("testInternalOptionalPartial", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", INTERNAL, "internalOptionalOnly",
                        c => c.register({
                            optNum: 789
                            // optStr omitted
                        }));
                    return step;
                })
            );
        });

        it("should reject call with wrong parameter types", () => {
            baseWorkflow.addTemplate("testInternalOptionalWrongType", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", INTERNAL, "internalOptionalOnly",
                        c => c.register({
                            // @ts-expect-error — wrong type for optStr
                            optStr: 999,
                            optNum: 789
                        }));
                    return step;
                })
            );
        });

        it("should reject call with spurious parameters", () => {
            baseWorkflow.addTemplate("testInternalOptionalSpurious", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", INTERNAL, "internalOptionalOnly",
                        c => c.register({
                            optStr: "validString",
                            // @ts-expect-error — spurious property should be rejected
                            spuriousField: "should error"
                        }));
                    return step;
                })
            );
        });

        it("debug CallerParams: Internal OptionalOnly spurious", () => {
            type Inputs = typeof baseWorkflow.templateSigScope.internalOptionalOnly.inputs;
            const _debugInputsCheck: Inputs = {} as any;
            type TestCallerParams = CallerParams<Inputs>;
            // @ts-expect-error — spurious CallerParams property should be rejected
            const _debugCallerParamsBad: TestCallerParams = { optStr: "validString", spuriousField: "should error" };
            const _debugCallerParams: TestCallerParams = { optStr: "validString" };
        });
    });

    // Tests for Internal Templates - Mixed Parameters
    describe("Internal Templates - Mixed Parameters", () => {
        it("should accept valid call with all required parameters only", () => {
            baseWorkflow.addTemplate("testInternalMixedRequiredOnly", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", INTERNAL, "internalMixedParams",
                        c => c.register({
                            reqStr: "validString",
                            reqBool: false
                            // optional parameters omitted
                        }));
                    return step;
                })
            );
        });

        it("should accept valid call with all parameters", () => {
            baseWorkflow.addTemplate("testInternalMixedAll", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", INTERNAL, "internalMixedParams",
                        c => c.register({
                            reqStr: "validString",
                            reqBool: false,
                            optNum: 123,
                            optStr: "customOptional"
                        }));
                    return step;
                })
            );
        });

        it("should reject call missing required parameters", () => {
            baseWorkflow.addTemplate("testInternalMixedMissingRequired", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", INTERNAL, "internalMixedParams",
                        // @ts-expect-error — missing required parameter reqStr
                        c => c.register({
                            reqBool: false,
                            optStr: "optional"
                            // reqStr is missing
                        }));
                    return step;
                })
            );
        });

        it("should reject call with wrong types", () => {
            baseWorkflow.addTemplate("testInternalMixedWrongTypes", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", INTERNAL, "internalMixedParams",
                        c => c.register({
                            // @ts-expect-error — wrong types for parameters
                            reqStr: ["array"],
                            // @ts-expect-error — wrong types for parameters
                            reqBool: "notBoolean",
                            // @ts-expect-error — wrong types for parameters
                            optNum: { object: true }
                        }));
                    return step;
                })
            );
        });

        it("should reject invalid callback usage patterns", () => {
            baseWorkflow.addTemplate("testInternalMixedInvalidCallbacks", t => t
                .addSteps(g => {
                    // Valid usage for comparison
                    const validStep = g.addStep("validStep", INTERNAL, "internalMixedParams",
                        c => c.register({reqStr: "valid", reqBool: true}));
                    // TODO - add all of these other negative tests
                    // Invalid patterns are documented here for reference (not executed):
                    // (steps, register) => register({reqBool: true, reqStr: "", spurious: 1})
                    // (steps, register) => register({})
                    // (steps, register) => Symbol('fake')
                    // (steps, register) => {}
                    // (steps, register) => undefined
                    // (steps, register) => 9

                    return validStep;
                })
            );
        });

        it("should reject call with spurious parameters", () => {
            baseWorkflow.addTemplate("testInternalMixedSpurious", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", INTERNAL, "internalMixedParams",
                        c => c.register({
                            reqStr: "validString",
                            reqBool: false,
                            // @ts-expect-error — spurious property should be rejected
                            spuriousField: "should error",
                            anotherInvalidField: true
                        })
                    );
                    return step;
                })
            );
        });

        it("debug CallerParams: Internal Mixed spurious", () => {
            type Inputs = typeof baseWorkflow.templateSigScope.internalMixedParams.inputs;
            const _debugInputsCheck: Inputs = {} as any;
            type TestCallerParams = CallerParams<Inputs>;
            const _debugCallerParamsBad: TestCallerParams = {
                reqStr: "validString",
                reqBool: false,
                // @ts-expect-error — spurious CallerParams property should be rejected
                spuriousField: "should error",
                anotherInvalidField: true
            };
            const _debugCallerParams: TestCallerParams = {
                reqStr: "validString",
                reqBool: false
            };
        });
    });

    // Edge case tests
    describe("Edge Cases", () => {
        it("should handle chaining steps with parameter dependencies", () => {
            baseWorkflow.addTemplate("testChaining", t => t
                .addSteps(g => {
                    const step1 = g.addStep("step1", requiredOnlyTemplate, "requiredOnly",
                        c => c.register({
                            reqStr: "initial",
                            reqNum: 1
                        }));

                    const step2 = step1.addStep("step2", INTERNAL, "internalRequiredOnly",
                        c => c.register({
                            reqStr: c.steps.step1.outputs.result, // using output from previous step
                            reqNum: 2
                        }));

                    return step2;
                })
            );
        });

        it("should reject chaining with wrong output reference types", () => {
            baseWorkflow.addTemplate("testChainingWrongType", t => t
                .addSteps(g => {
                    const step1 = g.addStep("step1", requiredOnlyTemplate, "requiredOnly",
                        c => c.register({
                            reqStr: "initial",
                            reqNum: 1
                        }));
                    const step2 = step1.addStep("step2", INTERNAL, "internalRequiredOnly",
                        c => c.register({
                            reqStr: "valid",
                            // @ts-expect-error — using string output where number is expected
                            reqNum: c.steps.step1.result // result is string, reqNum expects number
                        }));

                    return step2;
                })
            );
        });

        it("should handle multiple spurious fields", () => {
            baseWorkflow.addTemplate("testMultipleSpurious", t => t
                .addSteps(g => {
                    const step = g.addStep("step1", mixedParamsTemplate, "mixedParams",
                        c => c.register({
                            reqStr: "valid",
                            reqBool: true,
                            // @ts-expect-error — spurious properties should be rejected
                            spurious1: "error1",
                            spurious2: 42,
                            spurious3: { nested: "object" },
                            spurious4: ["array", "values"]
                        }));
                    return step;
                })
            );
        });

        it("debug CallerParams: Edge multiple spurious", () => {
            type Inputs = typeof baseWorkflow.templateSigScope.internalMixedParams.inputs;
            const _debugInputsCheck: Inputs = {} as any;
            type TestCallerParams = CallerParams<Inputs>;
            const _debugCallerParamsBad: TestCallerParams = {
                reqStr: "valid",
                reqBool: true,
                // @ts-expect-error — spurious CallerParams properties should be rejected
                spurious1: "error1",
                spurious2: 42,
                spurious3: { nested: "object" },
                spurious4: ["array", "values"]
            };
            const _debugCallerParams: TestCallerParams = {
                reqStr: "valid",
                reqBool: true
            };
        });
    });
});
