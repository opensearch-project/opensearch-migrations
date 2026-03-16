import { expr, INTERNAL, renderWorkflowTemplate, typeToken, WorkflowBuilder } from "../../src";

describe("self-recursive step templates", () => {
    it("renders a step template that can call itself with typed inputs and task-status conditions", () => {
        const wf = WorkflowBuilder.create({ k8sResourceName: "self-step-test" })
            .addTemplate("attempt", t => t
                .addRequiredInput("message", typeToken<string>())
                .addSteps(s => s)
            )
            .addTemplate("retryable", t => t
                .addRequiredInput("message", typeToken<string>())
                .addSteps(s => s
                    .addStep("attempt", INTERNAL, "attempt", c =>
                        c.register({ message: s.inputs.message })
                    )
                    .addStepToSelf("retry", c =>
                        c.register({ message: s.inputs.message }),
                        { when: c => ({ templateExp: expr.equals(c.attempt.status, "Failed") }) }
                    )
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const retryable = rendered.spec.templates.find(template => template.name === "retryable");

        expect(retryable).toBeDefined();
        expect(retryable?.steps?.[0]?.[0]?.name).toBe("attempt");
        expect(retryable?.steps?.[1]?.[0]?.name).toBe("retry");
        expect(retryable?.steps?.[1]?.[0]?.template).toBe("retryable");
        expect(retryable?.steps?.[1]?.[0]?.when).toContain("steps.attempt.status");
    });
});
