import {InputParamDef, defineParam, defineRequiredParam} from "../src/schemas/parameterSchemas";
import {expectTypeOf} from "expect-type";
import {DeepWiden} from "../src/schemas/plainObject";
import {expectNotType} from "tsd";
import {WorkflowBuilder} from "../src/schemas/workflowBuilder";
import {TemplateBuilder} from "../src/schemas/templateBuilder";
import {z, ZodType} from "zod";
import {NamedTask} from "../src/schemas/taskBuilder";
import {TargetLatchHelpers} from "../src/workflowTemplates/targetLatchHelpers";
import {FullMigration} from "../src/workflowTemplates/fullMigration";
import {StepsBuilder} from "@/schemas/stepsBuilder";

export const SIMPLE_ZOD_ENUM = z.enum(["A", "B", "C"]);
export type SIMPLE_ENUM = "a" | "b" | "c";

describe("paramsFns runtime validation", () => {
    const doNothingTemplate = WorkflowBuilder.create({ k8sResourceName: "DoNothing"})
        .addTemplate("opStr", b=>b
            .addOptionalInput("optionalA", s=>"str")
            .addSteps(x=>x))
        .addTemplate("reqNum", b=>b
            .addRequiredInput("reqNum", z.number())
            .addSteps(sb=>sb))
        .addTemplate("opEnum", b=>b
            .addOptionalInput("opEnum", s => "a" as SIMPLE_ENUM)
            .addSteps(x=>x))
        .addTemplate("reqEnum", b=>b
            .addRequiredInput("reqEnum", SIMPLE_ZOD_ENUM)
            .addSteps(sb=>sb))
        .getFullScope();
    doNothingTemplate.templates.reqEnum.inputs;
    doNothingTemplate.templates.opEnum.inputs;
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


    it("optional enum param types work", () => {
        templateBuilder.addSteps(sb=> {
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "opEnum", steps => ({opEnum: "aaa"}));
            sb.addStep("init", doNothingTemplate, "opEnum", steps => ({opEnum: "a"}));
            return sb;
        });
        templateBuilder.addSteps(sb=> {
            // @ts-expect-error — mixed scalar types should be rejected
            sb.addStep("init", doNothingTemplate, "reqEnum", steps => ({reqEnum: "aaa"}));
            sb.addStep("init", doNothingTemplate, "reqEnum", steps => ({reqEnum: "a"}));
            return sb;
        });
    });
});
