import {renderWorkflowTemplate, typeToken, WorkflowBuilder} from "../src";

describe("WaitForResourceBuilder", () => {
    it("creates a createResource step and passes through resource inputs to both inline steps", () => {
        const wf = WorkflowBuilder.create({k8sResourceName: "wait-for-resource"})
            .addTemplate("wait", t => t
                .addRequiredInput("resourceName", typeToken<string>())
                .addRequiredInput("resourceNamespace", typeToken<string>())
                .addWaitForResource(w => w
                    .setDefinition({
                        action: "create",
                        manifest: {
                            apiVersion: "v1",
                            kind: "ConfigMap",
                            metadata: {
                                name: "{{inputs.parameters.resourceName}}",
                                namespace: "{{inputs.parameters.resourceNamespace}}"
                            }
                        }
                    })
                    .setWaitForCreation({
                        kubectlImage: "bitnami/kubectl:latest",
                        kubectlImagePullPolicy: "IfNotPresent"
                    })
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const waitTemplate = rendered.spec.templates.find((t: any) => t.name === "wait");

        expect(waitTemplate.steps).toHaveLength(2);

        const createStep = waitTemplate.steps[0][0];
        const waitStep = waitTemplate.steps[1][0];

        expect(createStep.name).toBe("waitForCreate");
        expect(createStep.inline.container).toBeDefined();

        expect(waitStep.name).toBe("createResource");
        expect(waitStep.inline.resource).toBeDefined();

        expect(createStep.arguments.parameters).toEqual([
            {name: "resourceName", value: "{{inputs.parameters.resourceName}}"},
            {name: "resourceNamespace", value: "{{inputs.parameters.resourceNamespace}}"}
        ]);
        expect(waitStep.arguments.parameters).toEqual([
            {name: "resourceName", value: "{{inputs.parameters.resourceName}}"},
            {name: "resourceNamespace", value: "{{inputs.parameters.resourceNamespace}}"}
        ]);
    });
});
