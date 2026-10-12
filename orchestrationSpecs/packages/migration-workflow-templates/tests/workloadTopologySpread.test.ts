import * as yaml from "js-yaml";
import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {SetupCapture} from "../src/workflowTemplates/setupCapture";
import {Replayer} from "../src/workflowTemplates/replayer";

function resourceManifest(rendered: any, templateName: string): any {
    const template = (rendered.spec?.templates ?? []).find((candidate: any) =>
        candidate.name === templateName
    );
    expect(template?.resource?.manifest).toBeDefined();

    const manifest = (template.resource.manifest as string)
        .replace(
            /^(\s*)(nodeSelector|tolerations|volumeMounts|volumes): \{\{=.*$/gm,
            "$1$2: []",
        );
    return yaml.load(manifest);
}

function expectStrictZoneSpread(deployment: any, selectorKey: string) {
    const constraints = deployment.spec?.template?.spec?.topologySpreadConstraints;
    expect(constraints).toEqual([{
        maxSkew: 1,
        topologyKey: "topology.kubernetes.io/zone",
        whenUnsatisfiable: "DoNotSchedule",
        labelSelector: {},
        matchLabelKeys: [selectorKey],
    }]);
}

describe("capture/replay workloads spread across availability zones", () => {
    it.each(["deployproxydeployment", "deployproxydeploymentwithtls"])(
        "adds strict per-proxy zone spread to %s",
        templateName => {
            const deployment = resourceManifest(renderWorkflowTemplate(SetupCapture), templateName);
            expectStrictZoneSpread(deployment, "migrations/proxy");
        }
    );

    it("adds strict per-replayer zone spread", () => {
        const deployment = resourceManifest(renderWorkflowTemplate(Replayer), "createdeployment");
        expectStrictZoneSpread(deployment, "migrations/replayer");
    });
});
