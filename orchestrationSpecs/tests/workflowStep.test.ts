import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {OutputParamDef, typeToken} from "@/schemas/parameterSchemas";
import {TemplateBuilder} from "@/schemas/templateBuilder";
import {expectTypeOf} from "expect-type";
import {DagBuilder} from "@/schemas/dagBuilder";
import {StepsBuilder} from "@/schemas/stepsBuilder";
import { CallerParams } from "@/schemas/parameterSchemas";

describe("paramsFns runtime validation - comprehensive", () => {
    // Shared external templates with different parameter configurations

    // Template with no parameters
    const noParamsTemplate = WorkflowBuilder.create({ k8sResourceName: "NoParams"})
        .addTemplate("noParams", b=> b
            .addSteps(x=>x)
            .addExpressionOutput("result", "success" as string)
        )
        .getFullScope();

    // Template with only required parameters
    const requiredOnlyTemplate = WorkflowBuilder.create({ k8sResourceName: "RequiredOnly"})
        .addTemplate("requiredOnly", b=> b
            .addRequiredInput("reqStr", typeToken<string>())
            .addRequiredInput("reqNum", typeToken<number>())
            .addSteps(x=>x)
            .addExpressionOutput("result", "success" as string)
        )
        .getFullScope();

    // Template with only optional parameters
    const optionalOnlyTemplate = WorkflowBuilder.create({ k8sResourceName: "OptionalOnly"})
        .addTemplate("optionalOnly", b=> b
            .addOptionalInput("optStr", s=>"defaultStr")
            .addOptionalInput("optNum", n=>42)
            .addSteps(x=>x)
            .addExpressionOutput("result", "success" as string)
        )
        .getFullScope();

    // Template with mixed required and optional parameters
    const mixedParamsTemplate = WorkflowBuilder.create({ k8sResourceName: "MixedParams"})
        .addTemplate("mixedParams", b=> b
            .addRequiredInput("reqStr", typeToken<string>())
            .addOptionalInput("optNum", n=>0)
            .addRequiredInput("reqBool", typeToken<boolean>())
            .addOptionalInput("optStr", s=>"default")
            .addSteps(x=>x)
            .addExpressionOutput("result", "success" as string)
        )
        .getFullScope();

    // Base workflow builder for internal templates
    const baseWorkflow = WorkflowBuilder.create({ k8sResourceName: "TestWorkflow" })
        // Internal template with no parameters
        .addTemplate("internalNoParams", t => t
            .addSteps(b=>b)
            .addExpressionOutput("result", "internal_success" as string)
        )
        // Internal template with only required parameters
        .addTemplate("internalRequiredOnly", t => t
            .addRequiredInput("reqStr", typeToken<string>())
            .addRequiredInput("reqNum", typeToken<number>())
            .addSteps(b=>b)
            .addExpressionOutput("result", "internal_success" as string)
        )
        // Internal template with only optional parameters
        .addTemplate("internalOptionalOnly", t => t
            .addOptionalInput("optStr", s=>"defaultStr")
            .addOptionalInput("optNum", n=>42)
            .addSteps(b=>b)
            .addExpressionOutput("result", "internal_success" as string)
        )
        // Internal template with mixed parameters
        .addTemplate("internalMixedParams", t => t
            .addRequiredInput("reqStr", typeToken<string>())
            .addOptionalInput("optNum", n=>0)
            .addRequiredInput("reqBool", typeToken<boolean>())
            .addOptionalInput("optStr", s=>"default")
            .addSteps(b=>b)
            .addExpressionOutput("result", "internal_success" as string)
        );

    // Tests for External Templates - No Parameters
    describe("External Templates - No Parameters", () => {
        it("should accept valid call with no parameters", () => {
            baseWorkflow.addTemplate("testNoParamsValid", t => t
                .addSteps(g => {
                    const step = g.addExternalStep("step1", noParamsTemplate, "noParams", s => ({}));
                    return step;
                })
            );
        });

        it("should reject call with spurious parameters", () => {
            baseWorkflow.addTemplate("testNoParamsSpurious", t => t
                .addSteps(g => {
                    // @ts-expect-error — spurious property should be rejected
                    const step = g.addExternalStep("step1", noParamsTemplate, "noParams", s => ({
                        spuriousField: "should error"
                    }));
                    return step;
                })
            );
        });

        it("debug CallerParams: External NoParams spurious", () => {
            type Inputs = typeof noParamsTemplate.templates.noParams.inputs;
            const _debugInputsCheck: Inputs = {} as any;
            type TestCallerParams = CallerParams<Inputs>;
            // @ts-expect-error — spurious CallerParams property should be rejected
            const _debugCallerParamsBad: TestCallerParams = { spuriousField: "should error" };
            console.log("Debug caller params (External NoParams):", _debugCallerParamsBad);
            const _debugCallerParams: TestCallerParams = {};
            console.log("Debug caller params (External NoParams):", _debugCallerParams);
        });
    });

    // Tests for External Templates - Required Only Parameters
    describe("External Templates - Required Only Parameters", () => {
        it("should accept valid call with all required parameters", () => {
            baseWorkflow.addTemplate("testRequiredValid", t => t
                .addSteps(g => {
                    const step = g.addExternalStep("step1", requiredOnlyTemplate, "requiredOnly", s => ({
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
                    // @ts-expect-error — missing required parameter reqNum
                    const step = g.addExternalStep("step1", requiredOnlyTemplate, "requiredOnly", s => ({
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
                    const step = g.addExternalStep("step1", requiredOnlyTemplate, "requiredOnly", s => ({
                        reqStr: "validString",
                        // @ts-expect-error — wrong type for reqNum
                        reqNum: "shouldBeNumber" // wrong type
                    }));
                    return step;
                })
            );
        });

        it("should reject call with spurious parameters", () => {
            baseWorkflow.addTemplate("testRequiredSpurious", t => t
                .addSteps(g => {
                    // @ts-expect-error — spurious property should be rejected
                    const step = g.addExternalStep("step1", requiredOnlyTemplate, "requiredOnly", s => ({
                        reqStr: "validString",
                        reqNum: 123,
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
            console.log("Debug caller params (External RequiredOnly):", _debugCallerParamsBad);
            const _debugCallerParams: TestCallerParams = { reqStr: "validString", reqNum: 123 };
            console.log("Debug caller params (External RequiredOnly):", _debugCallerParams);
        });
    });

    // Tests for External Templates - Optional Only Parameters
    describe("External Templates - Optional Only Parameters", () => {
        it("should accept valid call with no parameters", () => {
            baseWorkflow.addTemplate("testOptionalEmpty", t => t
                .addSteps(g => {
                    const step = g.addExternalStep("step1", optionalOnlyTemplate, "optionalOnly", s => ({}));
                    return step;
                })
            );
        });

        it("should accept valid call with some optional parameters", () => {
            baseWorkflow.addTemplate("testOptionalPartial", t => t
                .addSteps(g => {
                    const step = g.addExternalStep("step1", optionalOnlyTemplate, "optionalOnly", s => ({
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
                    const step = g.addExternalStep("step1", optionalOnlyTemplate, "optionalOnly", s => ({
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
                    const step = g.addExternalStep("step1", optionalOnlyTemplate, "optionalOnly", s => ({
                        optStr: "validString",
                        // @ts-expect-error — wrong type for optNum
                        optNum: "shouldBeNumber" // wrong type
                    }));
                    return step;
                })
            );
        });

        it("should reject call with optional and spurious parameters", () => {
            baseWorkflow.addTemplate("testOptionalSpurious", t => t
                .addSteps(g => {
                    const step = g.addExternalStep("step1", optionalOnlyTemplate, "optionalOnly", s => ({
                        optStr: "validString",
                        // @ts-expect-error — spurious property should be rejected
                        spuriousField: "should error"
                    }));
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
            console.log("Debug caller params (External OptionalOnly opt+spurious):", _debugCallerParamsBad);
            const _debugCallerParams2: TestCallerParams = { optStr: "validString" };
            console.log("Debug caller params (External OptionalOnly opt+spurious):", _debugCallerParams2);
        });

        it("debug CallerParams: External OptionalOnly spurious-only", () => {
            type Inputs = typeof optionalOnlyTemplate.templates.optionalOnly.inputs;
            const _debugInputsCheck: Inputs = {} as any;
            type TestCallerParams = CallerParams<Inputs>;
            // @ts-expect-error — spurious CallerParams property should be rejected
            const _debugCallerParamsBad: TestCallerParams = { spuriousField: "should error" };
            console.log("Debug caller params (External OptionalOnly spurious-only):", _debugCallerParamsBad);
            const _debugCallerParams: TestCallerParams = {};
            console.log("Debug caller params (External OptionalOnly spurious-only):", _debugCallerParams);
        });

        it("should reject call with spurious parameters", () => {
            baseWorkflow.addTemplate("testOptionalSpurious", t => t
                .addSteps(g => {
                    // @ts-expect-error — spurious property should be rejected
                    const step = g.addExternalStep("step1", optionalOnlyTemplate, "optionalOnly", s => ({
                        spuriousField: "should error"
                    }));
                    return step;
                })
            );
        });
    });

    // Tests for External Templates - Mixed Parameters
    describe("External Templates - Mixed Parameters", () => {
        it("should accept valid call with all required parameters only", () => {
            baseWorkflow.addTemplate("testMixedRequiredOnly", t => t
                .addSteps(g => {
                    const step = g.addExternalStep("step1", mixedParamsTemplate, "mixedParams", s => ({
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
                    const step = g.addExternalStep("step1", mixedParamsTemplate, "mixedParams", s => ({
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
                    // @ts-expect-error — missing required parameter reqBool
                    const step = g.addExternalStep("step1", mixedParamsTemplate, "mixedParams", s => ({
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
                    const step = g.addExternalStep("step1", mixedParamsTemplate, "mixedParams", s => ({
                        // @ts-expect-error — wrong types for multiple parameters
                        reqStr: 123, // should be string
                        // @ts-expect-error — wrong types for multiple parameters
                        reqBool: "notBoolean", // should be boolean
                        // @ts-expect-error — wrong types for multiple parameters
                        optNum: true // should be number
                    }));
                    return step;
                })
            );
        });

        it("should reject call with spurious parameters", () => {
            baseWorkflow.addTemplate("testMixedSpurious", t => t
                .addSteps(g => {
                    // @ts-expect-error — spurious property should be rejected
                    const step = g.addExternalStep("step1", mixedParamsTemplate, "mixedParams", s => ({
                        reqStr: "validString",
                        reqBool: true,
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
            console.log("Debug caller params (External Mixed):", _debugCallerParamsBad);
            const _debugCallerParams: TestCallerParams = {
                reqStr: "validString",
                reqBool: true
            };
            console.log("Debug caller params (External Mixed):", _debugCallerParams);
        });
    });

    // Tests for Internal Templates - No Parameters
    describe("Internal Templates - No Parameters", () => {
        it("should accept valid call with no parameters", () => {
            baseWorkflow.addTemplate("testInternalNoParamsValid", t => t
                .addSteps(g => {
                    const step = g.addInternalStep("step1", "internalNoParams", s => ({}));
                    return step;
                })
            );
        });

        it("should reject call with spurious parameters", () => {
            baseWorkflow.addTemplate("testInternalNoParamsSpurious", t => t
                .addSteps(g => {
                    // @ts-expect-error — spurious property should be rejected
                    const step = g.addInternalStep("step1", "internalNoParams", s => ({
                        spuriousField: "should error"
                    }));
                    return step;
                })
            );
        });

        it("debug CallerParams: Internal NoParams spurious", () => {
            type Inputs = typeof baseWorkflow.templateSigScope.internalNoParams.inputs;
            const _debugInputsCheck: Inputs = {} as any;
            type TestCallerParams = CallerParams<Inputs>;
            // @ts-expect-error — spurious CallerParams property should be rejected
            const _debugCallerParamsBad: TestCallerParams = { spuriousField: "should error" };
            console.log("Debug caller params (Internal NoParams):", _debugCallerParamsBad);
            const _debugCallerParams: TestCallerParams = { };
            console.log("Debug caller params (Internal NoParams):", _debugCallerParams);
        });
    });

    // Tests for Internal Templates - Required Only Parameters
    describe("Internal Templates - Required Only Parameters", () => {
        it("should accept valid call with all required parameters", () => {
            baseWorkflow.addTemplate("testInternalRequiredValid", t => t
                .addSteps(g => {
                    const step = g.addInternalStep("step1", "internalRequiredOnly", s => ({
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
                    // @ts-expect-error — missing required parameter reqStr
                    const step = g.addInternalStep("step1", "internalRequiredOnly", s => ({
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
                    const step = g.addInternalStep("step1", "internalRequiredOnly", s => ({
                        // @ts-expect-error — wrong type for reqStr
                        reqStr: 123, // should be string
                        reqNum: 456
                    }));
                    return step;
                })
            );
        });

        it("should reject call with spurious parameters", () => {
            baseWorkflow.addTemplate("testInternalRequiredSpurious", t => t
                .addSteps(g => {
                    // @ts-expect-error — spurious property should be rejected
                    const step = g.addInternalStep("step1", "internalRequiredOnly", s => ({
                        reqStr: "validString",
                        reqNum: 456,
                        spuriousField: "should error"
                    }));
                    return step;
                })
            );
        });

        it("debug CallerParams: Internal RequiredOnly spurious", () => {
            type Inputs = typeof baseWorkflow.templateSigScope.internalRequiredOnly.inputs;
            const _debugInputsCheck: Inputs = {} as any;
            type TestCallerParams = CallerParams<Inputs>;
            // @ts-expect-error — spurious CallerParams property should be rejected
            const _debugCallerParamsBad: TestCallerParams = { reqStr: "validString", reqNum: 456, spuriousField: "should error" };
            console.log("Debug caller params (Internal RequiredOnly):", _debugCallerParamsBad);
            const _debugCallerParams: TestCallerParams = { reqStr: "validString", reqNum: 456 };
            console.log("Debug caller params (Internal RequiredOnly):", _debugCallerParams);
        });
    });

    // Tests for Internal Templates - Optional Only Parameters
    describe("Internal Templates - Optional Only Parameters", () => {
        it("should accept valid call with no parameters", () => {
            baseWorkflow.addTemplate("testInternalOptionalEmpty", t => t
                .addSteps(g => {
                    const step = g.addInternalStep("step1", "internalOptionalOnly", s => ({}));
                    return step;
                })
            );
        });

        it("should accept valid call with some optional parameters", () => {
            baseWorkflow.addTemplate("testInternalOptionalPartial", t => t
                .addSteps(g => {
                    const step = g.addInternalStep("step1", "internalOptionalOnly", s => ({
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
                    const step = g.addInternalStep("step1", "internalOptionalOnly", s => ({
                        // @ts-expect-error — wrong type for optStr
                        optStr: 999, // should be string
                        optNum: 789
                    }));
                    return step;
                })
            );
        });

        it("should reject call with spurious parameters", () => {
            baseWorkflow.addTemplate("testInternalOptionalSpurious", t => t
                .addSteps(g => {
                    // @ts-expect-error — spurious property should be rejected
                    const step = g.addInternalStep("step1", "internalOptionalOnly", s => ({
                        optStr: "validString",
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
            console.log("Debug caller params (Internal OptionalOnly):", _debugCallerParamsBad);
            const _debugCallerParams: TestCallerParams = { optStr: "validString" };
            console.log("Debug caller params (Internal OptionalOnly):", _debugCallerParams);
        });
    });

    // Tests for Internal Templates - Mixed Parameters
    describe("Internal Templates - Mixed Parameters", () => {
        it("should accept valid call with all required parameters only", () => {
            baseWorkflow.addTemplate("testInternalMixedRequiredOnly", t => t
                .addSteps(g => {
                    const step = g.addInternalStep("step1", "internalMixedParams", s => ({
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
                    const step = g.addInternalStep("step1", "internalMixedParams", s => ({
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
                    // @ts-expect-error — missing required parameter reqStr
                    const step = g.addInternalStep("step1", "internalMixedParams", s => ({
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
                    const step = g.addInternalStep("step1", "internalMixedParams", s => ({
                        // @ts-expect-error — wrong types for parameters
                        reqStr: ["array"], // should be string
                        // @ts-expect-error — wrong types for parameters
                        reqBool: "notBoolean", // should be boolean
                        // @ts-expect-error — wrong types for parameters
                        optNum: { object: true } // should be number
                    }));
                    return step;
                })
            );
        });

        it("should reject call with spurious parameters", () => {
            baseWorkflow.addTemplate("testInternalMixedSpurious", t => t
                .addSteps(g => {
                    // @ts-expect-error — spurious property should be rejected
                    const step = g.addInternalStep("step1", "internalMixedParams", s => ({
                        reqStr: "validString",
                        reqBool: false,
                        spuriousField: "should error",
                        anotherInvalidField: true
                    }));
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
            console.log("Debug caller params (Internal Mixed):", _debugCallerParamsBad);
            const _debugCallerParams: TestCallerParams = {
                reqStr: "validString",
                reqBool: false
            };
            console.log("Debug caller params (Internal Mixed):", _debugCallerParams);
        });
    });

    // Edge case tests
    describe("Edge Cases", () => {
        it("should handle chaining steps with parameter dependencies", () => {
            baseWorkflow.addTemplate("testChaining", t => t
                .addSteps(g => {
                    const step1 = g.addExternalStep("step1", requiredOnlyTemplate, "requiredOnly", s => ({
                        reqStr: "initial",
                        reqNum: 1
                    }));

                    const step2 = step1.addInternalStep("step2", "internalRequiredOnly", d => ({
                        reqStr: d.tasks.step1.result, // using output from previous step
                        reqNum: 2
                    }));

                    return step2;
                })
            );
        });

        it("should reject chaining with wrong output reference types", () => {
            baseWorkflow.addTemplate("testChainingWrongType", t => t
                .addSteps(g => {
                    const step1 = g.addExternalStep("step1", requiredOnlyTemplate, "requiredOnly", s => ({
                        reqStr: "initial",
                        reqNum: 1
                    }));

                    const step2 = step1.addInternalStep("step2", "internalRequiredOnly", d => ({
                        reqStr: "valid",
                        // @ts-expect-error — using string output where number is expected
                        reqNum: d.tasks.step1.result // result is string, reqNum expects number
                    }));

                    return step2;
                })
            );
        });

        it("should handle multiple spurious fields", () => {
            baseWorkflow.addTemplate("testMultipleSpurious", t => t
                .addSteps(g => {
                    // @ts-expect-error — spurious properties should be rejected
                    const step = g.addExternalStep("step1", mixedParamsTemplate, "mixedParams", s => ({
                        reqStr: "valid",
                        reqBool: true,
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
            console.log("Debug caller params (Edge multiple spurious):", _debugCallerParamsBad);
            const _debugCallerParams: TestCallerParams = {
                reqStr: "valid",
                reqBool: true
            };
            console.log("Debug caller params (Edge multiple spurious):", _debugCallerParams);
        });
    });
});
