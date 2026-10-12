import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {Replayer} from "../src/workflowTemplates/replayer";
import {SetupCapture} from "../src/workflowTemplates/setupCapture";
import {SetupKafka} from "../src/workflowTemplates/setupKafka";

const POOL_LABEL = "migrations.opensearch.org/workload-pool";

function template(rendered: any, name: string): any {
    const result = (rendered.spec?.templates ?? []).find((candidate: any) =>
        candidate.name === name
    );
    expect(result).toBeDefined();
    return result;
}

function expectPoolPlacement(
    rendered: any,
    templateName: string,
    configMapKey: string,
) {
    const resourceTemplate = template(rendered, templateName);
    const nodePoolInput = resourceTemplate.inputs.parameters.find(
        (parameter: any) => parameter.name === "workloadNodePoolName"
    );
    expect(nodePoolInput).toEqual({
        name: "workloadNodePoolName",
        value: "",
        valueFrom: {
            configMapKeyRef: {
                name: "provider-config",
                key: configMapKey,
            },
        },
    });

    const manifest = resourceTemplate.resource.manifest as string;
    expect(manifest).toContain("nodeSelector");
    expect(manifest).toContain(POOL_LABEL);
    expect(manifest).toContain("tolerations");
    expect(manifest).toContain("NoSchedule");
}

describe("capture/replay workloads resolve Helm-selected NodePools", () => {
    it.each(["deployproxydeployment", "deployproxydeploymentwithtls"])(
        "places %s from the proxy pool setting",
        templateName => {
            expectPoolPlacement(
                renderWorkflowTemplate(SetupCapture),
                templateName,
                "proxyNodePoolName",
            );
        },
    );

    it("places replayers from the replayer pool setting", () => {
        expectPoolPlacement(
            renderWorkflowTemplate(Replayer),
            "createdeployment",
            "replayerNodePoolName",
        );
    });

    it("places Strimzi broker pods from the Kafka pool setting", () => {
        expectPoolPlacement(
            renderWorkflowTemplate(SetupKafka),
            "deploykafkanodepool",
            "kafkaNodePoolName",
        );
    });
});
