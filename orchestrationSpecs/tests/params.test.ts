import {WorkflowBuilder} from "../src/schemas/workflowBuilder";
import {TemplateBuilder} from "../src/schemas/templateBuilder";
import {z} from "zod";
import {CallerParams, defineParam, InputParamDef, typeToken} from "@/schemas/parameterSchemas";
import {ParamsWithLiteralsOrExpressions} from "@/schemas/workflowTypes";
import {BaseExpression, LiteralExpression} from "@/schemas/expression";
import {expectTypeOf} from "expect-type";

export type SIMPLE_ENUM = "a" | "b" | "c";

describe("paramsFns runtime validation", () => {
    const doNothingTemplate = WorkflowBuilder.create({ k8sResourceName: "DoNothing"})
        .addTemplate("opStr", b=>b
            .addOptionalInput("optionalA", s=>"str")
            .addSteps(x=>x))
        .addTemplate("reqNum", b=>b
            .addRequiredInput("reqNum", typeToken<number>())
            .addSteps(sb=>sb))
        .addTemplate("opEnum", b=>b
            .addOptionalInput("opEnum", s => "a" as SIMPLE_ENUM)
            .addSteps(x=>x))
        .addTemplate("reqEnum", b=>b
            .addRequiredInput("reqEnum", typeToken<SIMPLE_ENUM>())
            .addSteps(sb=>sb))
        .getFullScope();
    const templateBuilder = new TemplateBuilder({}, {}, {}, {});

    it("optional and required are correct", () => {
        templateBuilder.addSteps(sb=> {
            sb.addStep("init", doNothingTemplate, "opStr", steps => ({optionalA: "hi"}));
            return sb;
        });
    });

    it("spurious parameters are rejected", () => {
        templateBuilder.addSteps(sb=> {
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "opStr", steps => ({notReal: "1"}));
            sb.addStep("init", doNothingTemplate, "opStr", steps => ({}));
            return sb;
        });
    });

    it("required param is required and must match type", () => {
        templateBuilder.addSteps(sb=> {
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "reqNum", steps => ({}));
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "reqNum", steps => ({reqNum: "1"}));
            sb.addStep("init", doNothingTemplate, "reqNum", steps => ({reqNum: 1}));
            return sb;
        });
    });

    type cpo = CallerParams<typeof doNothingTemplate.templates.opEnum.inputs>;
    it("optional enum param types work", () => {
        templateBuilder.addSteps(sb => {
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "opEnum", steps => ({opEnum: "aaa"}));
            sb.addStep("init", doNothingTemplate, "opEnum", steps => ({opEnum: "a"} as cpo));
            return sb;
        });
    });

    type cpr = CallerParams<typeof doNothingTemplate.templates.reqEnum.inputs>;
    it("required enum param types work", () => {
        templateBuilder.addSteps(sb=> {
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "reqEnum", steps => ({reqEnum: "aaa"}));
            sb.addStep("init", doNothingTemplate, "reqEnum", steps => ({reqEnum: "a"}));
            return sb;
        });
    });

    it("defineParam widens appropriately", () => {
        expectTypeOf(defineParam({defaultValue: "hi"})).toEqualTypeOf<InputParamDef<string, false>>();
        type e1 = ("hi" | "world");
        // @ts-expect-error — mixed scalar types should be rejected
        expectTypeOf(defineParam({defaultValue: "hi" as e1})).toEqualTypeOf<InputParamDef<string, false>>();
        expectTypeOf(defineParam({defaultValue: "hi" as e1})).toEqualTypeOf<InputParamDef<e1, false>>();
    });

    it("workfllow param types can be used with input params", () => {
        const standaloneParam = defineParam({defaultValue: "str"});
        const wpsBuilder = WorkflowBuilder.create({ k8sResourceName: "WorkflowParamSample"})
            .addParams({
                wpStr: defineParam({defaultValue: "str"}),
                wpEnum: defineParam({defaultValue: "b" as SIMPLE_ENUM}),
                wpStandalone: standaloneParam
            });

        wpsBuilder.addTemplate("test", sb=>sb.addSteps(sb=> {
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "reqEnum", steps => ({reqEnum: sb.workflowInputs.wpStr}));
            sb.addStep("init", doNothingTemplate, "reqEnum", steps => ({reqEnum: sb.workflowInputs.wpEnum}));
            return sb;
        }));
    });

});
