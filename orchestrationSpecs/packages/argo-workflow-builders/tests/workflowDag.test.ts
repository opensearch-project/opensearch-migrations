import {DagBuilder, INTERNAL, OutputParamDef, TemplateBuilder, typeToken, WorkflowBuilder} from "../src";
import {expectTypeOf} from "expect-type";

describe("paramsFns runtime validation", () => {
    const sharedNothingTemplate =
        WorkflowBuilder.create({k8sResourceName: "shared-do-nothing"})
            .addTemplate("doNothing", b => {
                const result = b
                    .addOptionalInput("strParam", s => "str")
                    .addOptionalInput("optNum", n => 42)
                    .addSteps(x => x)
                    .addExpressionOutput("out1", inputs=>"outputStr" as string)
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
                    k8sResourceName: "test"
                })
                .addTemplate("emptyDag", b => b
                    .addDag(d => d)
                )
                .getFullScope();
        expect(empty).toEqual({
            "metadata": {
                "k8sMetadata": {"name": "test"},
                "name": "",
                "parallelism": undefined,
                "serviceAccountName": undefined,
            },
            "templates": {
                "emptyDag": {
                    "body": {"dag": []},
                    "inputs": {},
                    "outputs": {},
                    "retryStrategy": {}
                },
            },
            "workflowParameters": {}
        });
    })

    it("addTasks to dag template", () => {
        const d = templateBuilder.addDag(td => {
                const result = td.addTask("init", sharedNothingTemplate, "doNothing",
                    c => c.register({
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
                k8sResourceName: "test"
            })
            .addTemplate("doNothing", t => t
                //.addRequiredInput("str", typeToken<string>())
                .addSteps(b => b) // no steps necessary
            )
            .addTemplate("dagWF", t => t
                .addRequiredInput("str1", typeToken<string>())
                .addDag(b => b
                    .addTask("first", sharedNothingTemplate, "doNothing",
                        c => c.register({
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
                "k8sMetadata": {"name": "test"},
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
                                    "name": "shared-do-nothing",
                                    "template": "doNothing",
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
                    "retryStrategy": {}
                },
                "doNothing": {
                    "body": {
                        "steps": [],
                    },
                    "inputs": {},
                    "outputs": {},
                    "retryStrategy": {}
                },
            },
            "workflowParameters": {}
        });
    });
})