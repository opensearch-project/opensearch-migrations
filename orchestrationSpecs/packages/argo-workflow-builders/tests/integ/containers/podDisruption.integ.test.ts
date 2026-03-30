import { WorkflowBuilder, renderWorkflowTemplate, defineParam } from "../../../src";
import { submitRenderedWorkflow } from "../infra/probeHelper";
import { getKubeConfig, getTestNamespace } from "../infra/argoCluster";
import { KubeConfig, CoreV1Api } from "@kubernetes/client-node";

const TINY_RESOURCES = {
    requests: { cpu: "10m", memory: "16Mi" },
    limits: { cpu: "50m", memory: "32Mi" }
};

describe("allowDisruption pod annotation", () => {
    let coreApi: CoreV1Api;
    let namespace: string;

    beforeAll(() => {
        const kc = new KubeConfig();
        kc.loadFromString(getKubeConfig());
        coreApi = kc.makeApiClient(CoreV1Api);
        namespace = getTestNamespace();
    });

    async function getPodAnnotations(workflowName: string): Promise<Record<string, string>> {
        // Argo pods are labeled with workflows.argoproj.io/workflow matching the workflow name
        const pods = await coreApi.listNamespacedPod({
            namespace,
            labelSelector: `workflows.argoproj.io/workflow=${workflowName}`
        });
        const pod = pods.items[0];
        return pod?.metadata?.annotations ?? {};
    }

    test("container pod gets karpenter do-not-disrupt by default", async () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: "test-default-nodisrupt",
            serviceAccountName: "test-runner"
        })
        .addTemplate("main", t => t
            .addContainer(c => c
                .addImageInfo("busybox:1.36", "IfNotPresent")
                .addCommand(["/bin/sh", "-c", "echo done"])
                .addResources(TINY_RESOURCES)
            )
        )
        .setEntrypoint("main")
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const result = await submitRenderedWorkflow(rendered);

        expect(result.phase).toBe("Succeeded");

        const annotations = await getPodAnnotations(result.raw.metadata.name);
        expect(annotations["karpenter.sh/do-not-disrupt"]).toBe("true");
    });

    test("allowDisruption removes karpenter do-not-disrupt annotation", async () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: "test-allow-disrupt",
            serviceAccountName: "test-runner"
        })
        .addTemplate("main", t => t
            .addContainer(c => c
                .addImageInfo("busybox:1.36", "IfNotPresent")
                .addCommand(["/bin/sh", "-c", "echo done"])
                .addResources(TINY_RESOURCES)
                .allowDisruption()
            )
        )
        .setEntrypoint("main")
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const result = await submitRenderedWorkflow(rendered);

        expect(result.phase).toBe("Succeeded");

        const annotations = await getPodAnnotations(result.raw.metadata.name);
        expect(annotations["karpenter.sh/do-not-disrupt"]).toBeUndefined();
    });
});
