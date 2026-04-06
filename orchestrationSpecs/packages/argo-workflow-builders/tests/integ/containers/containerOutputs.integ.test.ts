import { WorkflowBuilder, renderWorkflowTemplate } from "../../../src";
import { submitRenderedWorkflow } from "../infra/probeHelper";
import { getServiceAccountName } from "../infra/argoCluster";

describe("Container Output Tests", () => {
    test("artifact output is written and retrievable", async () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: "integ-artifact-out",
            serviceAccountName: getServiceAccountName(),
        })
            .addTemplate("main", t => t
                .addContainer(c => c
                    .addImageInfo("alpine", "IfNotPresent")
                    .addCommand(["sh", "-c"])
                    .addArgs(["echo 'hello from artifact' > /tmp/result.txt"])
                    .addResources({ requests: { cpu: "50m", memory: "32Mi" }, limits: { cpu: "50m", memory: "32Mi" } })
                    .addArtifactOutput("result", "/tmp/result.txt")
                )
            )
            .setEntrypoint("main")
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);

        // Verify the rendered YAML includes artifact output
        const tmpl = rendered.spec.templates.find((t: any) => t.name === "main");
        expect(tmpl.outputs.artifacts).toEqual([
            { name: "result", path: "/tmp/result.txt", archive: { none: {} } },
        ]);

        const result = await submitRenderedWorkflow(rendered);
        expect(result.phase).toBe("Succeeded");
    });

    test("both parameter and artifact outputs coexist", async () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: "integ-mixed-out",
            serviceAccountName: getServiceAccountName(),
        })
            .addTemplate("main", t => t
                .addContainer(c => c
                    .addImageInfo("alpine", "IfNotPresent")
                    .addCommand(["sh", "-c"])
                    .addArgs(["echo done > /tmp/phase.txt && echo 'detailed status' > /tmp/status.txt"])
                    .addResources({ requests: { cpu: "50m", memory: "32Mi" }, limits: { cpu: "50m", memory: "32Mi" } })
                    .addPathOutput("phase", "/tmp/phase.txt", {} as any)
                    .addArtifactOutput("statusOutput", "/tmp/status.txt")
                )
            )
            .setEntrypoint("main")
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const result = await submitRenderedWorkflow(rendered);
        expect(result.phase).toBe("Succeeded");

        // Verify both output types are present in rendered template
        const tmpl = rendered.spec.templates.find((t: any) => t.name === "main");
        expect(tmpl.outputs.parameters).toBeDefined();
        expect(tmpl.outputs.artifacts).toEqual([
            { name: "statusOutput", path: "/tmp/status.txt", archive: { none: {} } },
        ]);
    });
});
