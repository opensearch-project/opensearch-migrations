import {expr, renderWorkflowTemplate, typeToken, WorkflowBuilder} from "../../src";

describe("artifact outputs", () => {
    it("renders explicit s3 keys", () => {
        const wf = WorkflowBuilder.create({k8sResourceName: "artifact-key"})
            .addTemplate("main", t => t
                .addContainer(c => c
                    .addImageInfo("alpine", "IfNotPresent")
                    .addCommand(["sh", "-c"])
                    .addArgs(["echo keyed > /tmp/result.txt"])
                    .addResources({requests: {cpu: "50m", memory: "32Mi"}, limits: {cpu: "50m", memory: "32Mi"}})
                    .addArtifactOutput("result", "/tmp/result.txt", {
                        s3Key: "migration-outputs/datasnapshot/source-snap1/snapshot/workflow.log"
                    })
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === "main");

        expect(template?.outputs.artifacts).toEqual([
            {
                name: "result",
                path: "/tmp/result.txt",
                archive: {none: {}},
                s3: {key: "migration-outputs/datasnapshot/source-snap1/snapshot/workflow.log"},
            },
        ]);
    });

    it("rejects raw Argo template strings in s3 keys", () => {
        expect(() => WorkflowBuilder.create({k8sResourceName: "artifact-key"})
            .addTemplate("main", t => t
                .addContainer(c => c
                    .addImageInfo("alpine", "IfNotPresent")
                    .addCommand(["sh", "-c"])
                    .addArgs(["echo keyed > /tmp/result.txt"])
                    .addResources({requests: {cpu: "50m", memory: "32Mi"}, limits: {cpu: "50m", memory: "32Mi"}})
                    .addArtifactOutput("result", "/tmp/result.txt", {
                        s3Key: "migration-outputs/{{workflow.name}}.log"
                    })
                )
            )).toThrow("Use expression helpers");
    });

    it("renders expression s3 keys", () => {
        const wf = WorkflowBuilder.create({k8sResourceName: "artifact-key-expression"})
            .addTemplate("main", t => t
                .addRequiredInput("resourceName", typeToken<string>())
                .addContainer(c => c
                    .addImageInfo("alpine", "IfNotPresent")
                    .addCommand(["sh", "-c"])
                    .addArgs(["echo keyed > /tmp/result.txt"])
                    .addResources({requests: {cpu: "50m", memory: "32Mi"}, limits: {cpu: "50m", memory: "32Mi"}})
                    .addArtifactOutput("result", "/tmp/result.txt", {
                        s3Key: expr.concat(
                            expr.literal("migration-outputs/"),
                            c.inputs.resourceName,
                            expr.literal(".log")
                        )
                    })
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === "main");

        expect(template?.outputs.artifacts).toEqual([
            {
                name: "result",
                path: "/tmp/result.txt",
                archive: {none: {}},
                s3: {key: "{{='migration-outputs/'+inputs.parameters.resourceName+'.log'}}"},
            },
        ]);
    });
});
