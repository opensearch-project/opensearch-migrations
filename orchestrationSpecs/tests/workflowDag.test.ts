import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {OutputParamDef, typeToken} from "@/schemas/parameterSchemas";
import {TemplateBuilder} from "@/schemas/templateBuilder";
import {expectTypeOf} from "expect-type";
import {DagBuilder} from "@/schemas/dagBuilder";
import {INTERNAL} from "@/schemas/taskBuilder";

describe("paramsFns runtime validation", () => {
    const sharedNothingTemplate =
        WorkflowBuilder.create({k8sResourceName: "SharedDoNothing"})
            .addTemplate("doNothing", b => {
                const result = b
                    .addOptionalInput("strParam", s => "str")
                    .addOptionalInput("optNum", n => 42)
                    .addSteps(x => x)
                    .addExpressionOutput("out1", "outputStr" as string)
                ;
                expectTypeOf(result.outputsScope).toEqualTypeOf<{ out1: OutputParamDef<string> }>();
                return result;
            })
            .getFullScope();
    expectTypeOf(sharedNothingTemplate.templates.doNothing.outputs).toEqualTypeOf<{ out1: OutputParamDef<string> }>();
    const templateBuilder = new TemplateBuilder({}, {}, {}, {});

    it("Creates empty dag template", () => {
        const empty =
            WorkflowBuilder
                .create({
                    k8sResourceName: "Test"
                })
                .addTemplate("emptyDag", b => b
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
                    "body": {"dag": []},
                    "inputs": {},
                    "outputs": {}
                },
            },
            "workflowParameters": {}
        });
    })

    it("addTasks to dag template", () => {
        const d = templateBuilder.addDag(td => {
                const result = td.addTask("init", sharedNothingTemplate, "doNothing",
                    (tasks, register) => register({
                        strParam: "b"
                    }));
                expectTypeOf(result).toExtend<DagBuilder<any, any, any, any>>();
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
                .addSteps(b => b) // no steps necessary
            )
            .addTemplate("dagWF", t => t
                .addRequiredInput("str1", typeToken<string>())
                .addDag(b => b
                    .addTask("first", sharedNothingTemplate, "doNothing",
                        (tasks, register) => register({
                            strParam: ""
                        }))
                    .addTask("second", INTERNAL, "doNothing", {dependencies: ["first"]})
                    .addTask("third", INTERNAL, "doNothing", {dependencies: ["first", "second"]})
                )
            )
            .getFullScope();

        expectTypeOf(wf).not.toBeAny();
        expect(wf).toEqual({
            "metadata": {
                "k8sMetadata": {"name": "Test"},
                "name": "",
                "parallelism": undefined,
                "serviceAccountName": undefined,
            },
            "templates": {
                "dagWF": {
                    "body": {
                        "dag": [
                            {
                                "args": {
                                    "strParam": "",
                                },
                                "name": "first",
                                "templateRef": {
                                    "name": "doNothing",
                                    "template": "SharedDoNothing",
                                },
                            },
                            {
                                "args": {},
                                "dependencies": [
                                    "first",
                                ],
                                "name": "second",
                                "template": "doNothing",
                            },
                            {
                                "args": {},
                                "dependencies": [
                                    "first",
                                    "second",
                                ],
                                "name": "third",
                                "template": "doNothing",
                            },
                        ],
                    },
                    "inputs": {
                        "str1": {
                            "description": undefined,
                        },
                    },
                    "outputs": {},
                },
                "doNothing": {
                    "body": {
                        "steps": [],
                    },
                    "inputs": {},
                    "outputs": {}
                },
            },
            "workflowParameters": {}
        });
    });
})