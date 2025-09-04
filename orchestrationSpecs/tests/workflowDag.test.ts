import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {OutputParamDef, typeToken} from "@/schemas/parameterSchemas";
import {TemplateBuilder} from "@/schemas/templateBuilder";
import {expectTypeOf} from "expect-type";
import {DagBuilder} from "@/schemas/dagBuilder";

describe("paramsFns runtime validation", () => {
    const sharedNothingTemplate =
        WorkflowBuilder.create({ k8sResourceName: "SharedDoNothing"})
        .addTemplate("doNothing", b=> {
            const result = b
                .addOptionalInput("strParam", s=>"str")
                .addOptionalInput("optNum", n=>42)
                .addSteps(x=>x)
                .addExpressionOutput("out1", "outputStr" as string)
            ;
            expectTypeOf(result.outputsScope).toEqualTypeOf<{out1: OutputParamDef<string>}>();
            return result;
        })
            .getFullScope();
    expectTypeOf(sharedNothingTemplate.templates.doNothing.outputs).toEqualTypeOf<{out1: OutputParamDef<string>}>();
    const templateBuilder = new TemplateBuilder({}, {}, {}, {});

    it("Creates empty dag template", () => {
        const empty =
            WorkflowBuilder
                .create({
                    k8sResourceName: "Test"
                })
                .addTemplate("emptyDag", b=> b
                    .addDag(d => d)
                )
                .getFullScope();
        expect(empty).toEqual({
            "metadata": {
                "k8sMetadata": {"name": "Test"},
                "name": "",
                "parallelism": undefined,
                "serviceAccountName": undefined,
            },
            "templates": {
                "emptyDag": {
                    "body": {"dag": {}},
                    "inputs": {},
                    "outputs": {}
                },
            },
            "workflowParameters": {}
        });
    })

    it("addTasks to dag template", () => {
        const d = templateBuilder.addDag(td => {
            const result = td.addExternalTask("init", sharedNothingTemplate, "doNothing", b => ({
                strParam: "b"
            }));
            expectTypeOf(result).toExtend<DagBuilder<any,any,any,any>>();
            expectTypeOf(result).not.toBeAny();
            return result;
            }
        );
    })

    it("test that a dag workflow can be created", () => {
        var wf = WorkflowBuilder
            .create({
                k8sResourceName: "Test"
            })
            .addTemplate("doNothing", t => t
                //.addRequiredInput("str", typeToken<string>())
                .addSteps(b=>b) // no steps necessary
            )
            .addTemplate("dagWF", t=> t
                .addRequiredInput("str1", typeToken<string>())
                .addDag(b=>b
                    .addExternalTask("first", sharedNothingTemplate, "doNothing", d => ({
                        strParam: "",
                        notReal: ""
                    }))
                    .addInternalTask("nothing1", "doNothing", d => ({
                        notreal: b.inputs.str1
                    }))
                    .addInternalTask("nothing2", "doNothing", d => ({notReal: "1"}))
                )
            )
            .getFullScope();
        expectTypeOf(wf).not.toBeAny();
    })


    it("test that a step workflow can be created", () => {
        var wf = WorkflowBuilder
            .create({
                k8sResourceName: "Test"
            })
            .addTemplate("doNothing", t => t
                // .addRequiredInput("str", typeToken<string>())
                .addSteps(b=>b) // no steps necessary
            )
            .addTemplate("dagWF", t => t
                .addSteps(b=> {
                    // @ ts-expect-error — excess property should be rejected
                    const b1 = b.addExternalStep("first", sharedNothingTemplate, "doNothing", d => ({
                            notReal: ""
                        }));
                    const b2 = b1.addInternalStep("nothing1", "doNothing", d => ({
                        notreal: 9
                    }));
                    return b2.addInternalStep("nothing2", "doNothing", d => ({str: "", notReal: "1"}))
                    }
                )
            )
            .getFullScope();
        expectTypeOf(wf).not.toBeAny();
    })
})