import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {OutputParamDef} from "@/schemas/parameterSchemas";
import {TemplateBuilder} from "@/schemas/templateBuilder";
import {expectTypeOf} from "expect-type";
import {DagBuilder} from "@/schemas/dagBuilder";
import {StepsBuilder} from "@/schemas/stepsBuilder";

describe("paramsFns runtime validation", () => {
    const sharedNothingTemplate =
        WorkflowBuilder.create({ k8sResourceName: "SharedDoNothing"})
            .addTemplate("doNothing", b=> {
                const result = b
                    .addOptionalInput("strParam", s=>"str")
                    .addSteps(x=>x)
                    .addExpressionOutput("out1", "outputStr" as string)
                ;
                expectTypeOf(result.outputsScope).toEqualTypeOf<{out1: OutputParamDef<string>}>();
                return result;
            })
            .getFullScope();
    expectTypeOf(sharedNothingTemplate.templates.doNothing.outputs).toEqualTypeOf<{out1: OutputParamDef<string>}>();
    const templateBuilder = new TemplateBuilder({}, {}, {}, {});

    it("Creates empty steps template", () => {
        const empty =
            WorkflowBuilder
                .create({
                    k8sResourceName: "Test"
                })
                .addTemplate("emptyDag", b=> b
                    .addSteps(d => d)
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
                    "body": {"steps": []},
                    "inputs": {},
                    "outputs": {}
                },
            },
            "workflowParameters": {}
        });
    })

    it("addTasks to dag template", () => {
        const d = templateBuilder.addSteps(td => {
                const result = td.addExternalStep("init", sharedNothingTemplate, "doNothing", b => ({
                    strParam: "b"
                }));
                // let result: never;
                expectTypeOf(result).toExtend<StepsBuilder<any,any, { init: any }, any>>();
                expectTypeOf(result).not.toBeAny();
                return result;
            }
        );
    })

    // it("test that a dag workflow can be created", () => {
    //     WorkflowBuilder
    //         .create({
    //             k8sResourceName: "Test"
    //         })
    //         .addTemplate("doNothing", t => t
    //             //.addRequiredInput("str", typeToken<string>())
    //             .addSteps(b=>b) // no steps necessary
    //         )
    //         .addTemplate("dagWF", t=> t
    //             .addRequiredInput("str1", typeToken<string>())
    //             .addDag(b=>b
    //                 .addInternalTask("nothing1", "doNothing", d => ({
    //                     // a: b.inputs.str1
    //                 }))
    //                 .addInternalTask("nothing2", "doNothing", d => ({notReal: "1"}))
    //             )
    //         )
    //         .getFullScope();
    // })

})