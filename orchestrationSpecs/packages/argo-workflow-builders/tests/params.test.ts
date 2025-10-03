import {expectTypeOf} from "expect-type";
import {CallerParams, defineParam, expr, InputParamDef, TemplateBuilder, typeToken, WorkflowBuilder} from "../src";

export type SIMPLE_ENUM = "a" | "b" | "c";

describe("paramsFns runtime validation", () => {
    const doNothingTemplate = WorkflowBuilder.create({ k8sResourceName: "do-nothing"})
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
            sb.addStep("init", doNothingTemplate, "opStr", c=>c.register({optionalA: "hi"}));
            return sb;
        });
    });

    it("spurious parameters are rejected", () => {
        templateBuilder.addSteps(sb=> {
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "opStr", c => c.register({notReal: "1"}));
            const next = sb.addStep("init", doNothingTemplate, "opStr", c => c.register({}));
            return next;
        });
    });

    it("required param is required and must match type", () => {
        templateBuilder.addSteps(sb=> {
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "reqNum", c => c.register({}));
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "reqNum", c => c.register({reqNum: "1"}));
            sb.addStep("init", doNothingTemplate, "reqNum", c => c.register({reqNum: 1}));
            return sb;
        });
    });

    type cpo = CallerParams<typeof doNothingTemplate.templates.opEnum.inputs>;
    it("optional enum param types work", () => {
        templateBuilder.addSteps(sb => {
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "opEnum", c => c.register({opEnum: "aaa"}));
            sb.addStep("init", doNothingTemplate, "opEnum", c => c.register({opEnum: "a"} as cpo));
            return sb;
        });
    });

    type cpr = CallerParams<typeof doNothingTemplate.templates.reqEnum.inputs>;
    it("required enum param types work", () => {
        templateBuilder.addSteps(sb=> {
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "reqEnum", c => c.register({reqEnum: "aaa"}));
            sb.addStep("init", doNothingTemplate, "reqEnum", c => c.register({reqEnum: "a"}));
            sb.addStep("init", doNothingTemplate, "reqEnum", c => c.register({reqEnum: expr.literal("a" as SIMPLE_ENUM)}));
            return sb;
        });
    });

    it("defineParam widens appropriately", () => {
        expectTypeOf(defineParam({expression: "hi"})).toEqualTypeOf<InputParamDef<string, false>>();
        type e1 = ("hi" | "world");
        // @ts-expect-error — mixed scalar types should be rejected
        expectTypeOf(defineParam({expression: "hi" as e1})).toEqualTypeOf<InputParamDef<string, false>>();
        expectTypeOf(defineParam({expression: "hi" as e1})).toEqualTypeOf<InputParamDef<e1, false>>();
    });

    it("workflow param types can be used with input params", () => {
        const standaloneParam = defineParam({expression: "str"});
        const wpsBuilder = WorkflowBuilder.create({ k8sResourceName: "workflow-param-sample"})
            .addParams({
                wpStr: defineParam({expression: "str"}),
                wpEnum: defineParam({expression: "b" as SIMPLE_ENUM}),
                wpStandalone: standaloneParam
            });

        wpsBuilder.addTemplate("test", sb=>sb.addSteps(sb=> {
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "reqEnum", c => c.register({reqEnum: sb.workflowInputs.wpStr}));
            sb.addStep("init", doNothingTemplate, "reqEnum", c => c.register({reqEnum: sb.workflowInputs.wpEnum}));
            return sb;
        }));
    });

});
