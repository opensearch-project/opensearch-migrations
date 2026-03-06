import {INLINE, renderWorkflowTemplate, typeToken, WorkflowBuilder} from "../src";

describe("inline template tests", () => {
    it("should support inline container template in steps", () => {
        const wf = WorkflowBuilder.create({ k8sResourceName: "test-inline" })
            .addTemplate("testInline", t => t
                .addSteps(sb => sb
                    .addStep("inline-container", INLINE, b => b
                        .addContainer((cb: any) => cb
                            .addImageInfo("busybox", "Always")
                            .addCommand(["echo", "hello"])
                            .addResources({})
                        )
                    )
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) =>
            t.steps?.some((g: any) => g.some((s: any) => s.name === "inline-container"))
        );
        expect(template).toBeDefined();
        expect(template.steps[0][0].name).toBe("inline-container");
        expect(template.steps[0][0].inline.container.image).toBe("busybox");
        expect(template.steps[0][0].inline.container.command).toEqual(["echo", "hello"]);
    });

    it("should support inline steps template in steps", () => {
        const wf = WorkflowBuilder.create({ k8sResourceName: "test-inline-steps" })
            .addTemplate("testInlineSteps", t => t
                .addSteps(sb => sb
                    .addStep("nested-steps", INLINE, b => b
                        .addSteps((inner: any) => inner
                            .addStep("inner-step", INLINE, (b2: any) => b2
                                .addContainer((cb: any) => cb
                                    .addImageInfo("alpine", "Always")
                                    .addCommand(["ls"])
                                    .addResources({})
                                )
                            )
                        )
                    )
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) =>
            t.steps?.some((g: any) => g.some((s: any) => s.name === "nested-steps"))
        );
        expect(template).toBeDefined();
        expect(template.steps[0][0].name).toBe("nested-steps");
        expect(template.steps[0][0].inline.steps[0][0].name).toBe("inner-step");
        expect(template.steps[0][0].inline.steps[0][0].inline.container.image).toBe("alpine");
    });

    it("should support inline dag template in dag", () => {
        const wf = WorkflowBuilder.create({ k8sResourceName: "test-inline-dag" })
            .addTemplate("testInlineDag", t => t
                .addDag(db => db
                    .addTask("inline-task", INLINE, b => b
                        .addContainer((cb: any) => cb
                            .addImageInfo("nginx", "Always")
                            .addCommand(["nginx"])
                            .addResources({})
                        )
                    )
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) =>
            t.dag?.tasks?.some((task: any) => task.name === "inline-task")
        );
        expect(template).toBeDefined();
        expect(template.dag.tasks[0].name).toBe("inline-task");
        expect(template.dag.tasks[0].inline.container.image).toBe("nginx");
        expect(template.dag.tasks[0].inline.container.command).toEqual(["nginx"]);
    });

    it("should support inline template with inputs", () => {
        const wf = WorkflowBuilder.create({ k8sResourceName: "test-inline-inputs" })
            .addTemplate("testInlineInputs", t => t
                .addSteps(sb => sb
                    .addStep("with-inputs", INLINE, b => b
                        .addRequiredInput("message", typeToken<string>())
                        .addContainer((cb: any) => cb
                            .addImageInfo("busybox", "Always")
                            .addCommand(["echo", cb.inputs.message])
                            .addResources({})
                        ),
                        (ctx: any) => ctx.register({ message: "hello world" })
                    )
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) =>
            t.steps?.some((g: any) => g.some((s: any) => s.name === "with-inputs"))
        );
        expect(template).toBeDefined();
        expect(template.steps[0][0].arguments.parameters).toEqual([
            {name: "message", value: "hello world"}
        ]);
    });

    it("should support inline template with outputs", () => {
        const wf = WorkflowBuilder.create({ k8sResourceName: "test-inline-outputs" })
            .addTemplate("testInlineOutputs", t => t
                .addSteps(sb => sb
                    .addStep("with-outputs", INLINE, b => b
                        .addContainer((cb: any) => cb
                            .addImageInfo("busybox", "Always")
                            .addCommand(["echo", "result"])
                            .addResources({})
                        )
                        .addExpressionOutput("result", () => "success" as string)
                    )
                    .addStep("use-output", INLINE, b => b
                        .addContainer((cb: any) => cb
                            .addImageInfo("busybox", "Always")
                            .addCommand(["echo", "with-outputs"])
                            .addResources({})
                        )
                    )
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) =>
            t.steps?.some((g: any) => g.some((s: any) => s.name === "with-outputs"))
        );
        expect(template).toBeDefined();
        expect(template.steps[0][0].name).toBe("with-outputs");
        expect(template.steps[0][0].inline).toBeDefined();
        expect(template.steps[1][0].name).toBe("use-output");
        expect(template.steps[1][0].inline).toBeDefined();
    });
});
