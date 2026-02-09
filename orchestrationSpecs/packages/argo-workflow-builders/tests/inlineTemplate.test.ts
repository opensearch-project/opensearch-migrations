import {INLINE, renderWorkflowTemplate, typeToken, WorkflowBuilder} from "../src";

describe("inline template tests", () => {
    it("should support inline container template in steps", () => {
        const wf = WorkflowBuilder.create({ k8sResourceName: "test-inline" })
            .addTemplate("testInline", t => t
                .addSteps(sb => sb
                    .addStep("inline-container", INLINE, b => b
                        .addContainer(cb => cb
                            .setImage("busybox")
                            .setCommand(["echo", "hello"])
                        )
                    )
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === "test-inline");
        expect(template.steps[0].steps[0].name).toBe("inline-container");
        expect(template.steps[0].steps[0].inline).toBeDefined();
        expect(template.steps[0].steps[0].inline.container).toBeDefined();
        expect(template.steps[0].steps[0].inline.container.image).toBe("busybox");
    });

    it("should support inline steps template in steps", () => {
        const wf = WorkflowBuilder.create({ k8sResourceName: "test-inline-steps" })
            .addTemplate("testInlineSteps", t => t
                .addSteps(sb => sb
                    .addStep("nested-steps", INLINE, b => b
                        .addSteps(inner => inner
                            .addStep("inner-step", INLINE, b2 => b2
                                .addContainer(cb => cb
                                    .setImage("alpine")
                                    .setCommand(["ls"])
                                )
                            )
                        )
                    )
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === "test-inline-steps");
        expect(template.steps[0].steps[0].name).toBe("nested-steps");
        expect(template.steps[0].steps[0].inline).toBeDefined();
        expect(template.steps[0].steps[0].inline.steps).toBeDefined();
    });

    it("should support inline dag template in dag", () => {
        const wf = WorkflowBuilder.create({ k8sResourceName: "test-inline-dag" })
            .addTemplate("testInlineDag", t => t
                .addDag(db => db
                    .addTask("inline-task", INLINE, b => b
                        .addContainer(cb => cb
                            .setImage("nginx")
                            .setCommand(["nginx"])
                        )
                    )
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === "test-inline-dag");
        expect(template.dag.tasks[0].name).toBe("inline-task");
        expect(template.dag.tasks[0].inline).toBeDefined();
        expect(template.dag.tasks[0].inline.container).toBeDefined();
    });

    it("should support inline template with inputs", () => {
        const wf = WorkflowBuilder.create({ k8sResourceName: "test-inline-inputs" })
            .addTemplate("testInlineInputs", t => t
                .addSteps(sb => sb
                    .addStep("with-inputs", INLINE, b => b
                        .addRequiredInput("message", typeToken<string>())
                        .addContainer(cb => cb
                            .setImage("busybox")
                            .setCommand(["echo", cb.inputs.inputParameters.message])
                        ),
                        ctx => ctx.register({ message: "hello world" })
                    )
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === "test-inline-inputs");
        expect(template.steps[0].steps[0].inline).toBeDefined();
        expect(template.steps[0].steps[0].arguments.parameters[0].name).toBe("message");
        expect(template.steps[0].steps[0].arguments.parameters[0].value).toBe("hello world");
    });

    it("should support inline template with outputs", () => {
        const wf = WorkflowBuilder.create({ k8sResourceName: "test-inline-outputs" })
            .addTemplate("testInlineOutputs", t => t
                .addSteps(sb => sb
                    .addStep("with-outputs", INLINE, b => b
                        .addContainer(cb => cb
                            .setImage("busybox")
                            .setCommand(["echo", "result"])
                        )
                        .addExpressionOutput("result", () => "success" as string)
                    )
                    .addStep("use-output", INLINE, b => b
                        .addContainer(cb => cb
                            .setImage("busybox")
                            .setCommand(["echo", cb.inputs.workflowParameters.steps["with-outputs"].outputs.result])
                        )
                    )
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === "test-inline-outputs");
        expect(template.steps[0].steps[0].inline).toBeDefined();
        expect(template.steps[1].steps[0].inline).toBeDefined();
    });
});
