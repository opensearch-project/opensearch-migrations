import { expr, INTERNAL, renderWorkflowTemplate, typeToken, WorkflowBuilder } from "../../src";

describe("self-recursive dag templates", () => {
    it("renders a dag template that can call itself with typed inputs and task-status conditions", () => {
        const wf = WorkflowBuilder.create({ k8sResourceName: "self-dag-test" })
            .addTemplate("attempt", t => t
                .addRequiredInput("message", typeToken<string>())
                .addDag(d => d)
            )
            .addTemplate("retryable", t => t
                .addRequiredInput("message", typeToken<string>())
                .addDag(d => d
                    .addTask("attempt", INTERNAL, "attempt", c =>
                        c.register({ message: d.inputs.message })
                    )
                    .addTaskToSelf("retry", c =>
                        c.register({ message: d.inputs.message }),
                        {
                            dependencies: ["attempt"],
                            when: c => ({ templateExp: expr.equals(c.attempt.status, "Failed") })
                        }
                    )
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const retryable = rendered.spec.templates.find(template => template.name === "retryable");

        expect(retryable).toBeDefined();
        expect(retryable?.dag?.tasks?.[0]?.name).toBe("attempt");
        expect(retryable?.dag?.tasks?.[1]?.name).toBe("retry");
        expect(retryable?.dag?.tasks?.[1]?.template).toBe("retryable");
        expect(retryable?.dag?.tasks?.[1]?.dependencies).toEqual(["attempt"]);
        expect(retryable?.dag?.tasks?.[1]?.when).toContain("tasks.attempt.status");
    });
});
