import {INTERNAL, renderWorkflowTemplate, typeToken, WorkflowBuilder} from '../../src';

describe("onExit template support", () => {
    it("renders onExit on a steps template referencing an internal template", () => {
        const wf = WorkflowBuilder.create({k8sResourceName: "on-exit-test"})
            .addTemplate("cleanup", t => t
                .addSteps(b => b)
            )
            .addTemplate("main", t => t
                .addSteps(b => b)
                .addOnExit(INTERNAL, "cleanup")
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const mainTemplate = rendered.spec.templates.find((t: Record<string, unknown>) => t.name === "main");
        expect(mainTemplate).toBeDefined();
        expect(mainTemplate?.onExit).toBe("cleanup");
    });

    it("renders onExit on a container template", () => {
        const wf = WorkflowBuilder.create({k8sResourceName: "on-exit-container-test"})
            .addTemplate("cleanup", t => t
                .addSteps(b => b)
            )
            .addTemplate("work", t => t
                .addContainer(b => b
                    .addImageInfo("busybox", "Always")
                    .addCommand(["/bin/sh", "-c"])
                    .addArgs(["echo hello"])
                    .addResources({requests: {cpu: "100m", memory: "64Mi"}, limits: {cpu: "100m", memory: "64Mi"}})
                )
                .addOnExit(INTERNAL, "cleanup")
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const workTemplate = rendered.spec.templates.find((t: Record<string, unknown>) => t.name === "work");
        expect(workTemplate?.onExit).toBe("cleanup");
    });

    it("renders onExit on a resource template", () => {
        const wf = WorkflowBuilder.create({k8sResourceName: "on-exit-resource-test"})
            .addTemplate("cleanup", t => t
                .addSteps(b => b)
            )
            .addTemplate("createThing", t => t
                .addResourceTask(b => b
                    .setDefinition({
                        action: "create",
                        manifest: {apiVersion: "v1", kind: "ConfigMap", metadata: {name: "test"}}
                    })
                )
                .addOnExit(INTERNAL, "cleanup")
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: Record<string, unknown>) => t.name === "creatething");
        expect(template?.onExit).toBe("cleanup");
    });

    it("does not render onExit when not set", () => {
        const wf = WorkflowBuilder.create({k8sResourceName: "no-exit-test"})
            .addTemplate("main", t => t
                .addSteps(b => b)
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const mainTemplate = rendered.spec.templates.find((t: Record<string, unknown>) => t.name === "main");
        expect(mainTemplate?.onExit).toBeUndefined();
    });
});
