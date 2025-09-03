import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";
import {typeToken} from "@/schemas/parameterSchemas";
import {TemplateBuilder} from "@/schemas/templateBuilder";
import {expectTypeOf} from "expect-type";
import {DagBuilder} from "@/schemas/dagBuilder";


describe("paramsFns runtime validation", () => {
    const sharedNothingTemplate =
        WorkflowBuilder.create({ k8sResourceName: "SharedDoNothing"})
        .addTemplate("doNothing", b=>b
            .addOptionalInput("strParam", s=>"str")
            .addSteps(x=>x)
            .addExpressionOutput("o", "outputStr"))
            .getFullScope();
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
            // expectTypeOf(result).toEqualTypeOf<DagBuilder<any,any,any,any>>();
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