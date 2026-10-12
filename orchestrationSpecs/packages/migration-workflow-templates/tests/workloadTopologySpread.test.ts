import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {SetupCapture} from "../src/workflowTemplates/setupCapture";
import {Replayer} from "../src/workflowTemplates/replayer";
import {SetupKafka} from "../src/workflowTemplates/setupKafka";

function template(rendered: any, templateName: string): any {
    const found = (rendered.spec?.templates ?? []).find((candidate: any) => candidate.name === templateName);
    expect(found?.resource?.manifest).toBeDefined();
    return found;
}

function expectZoneSpreadFromProviderConfig(found: any) {
    expect(found.inputs.parameters.find((parameter: any) => parameter.name === "workloadZoneSpread")).toEqual({
        name: "workloadZoneSpread",
        value: "preferred",
        valueFrom: {configMapKeyRef: {name: "provider-config", key: "workloadZoneSpread"}},
    });
}

// The constraint is an Argo expression resolved at pod creation, so assert on the three policies it encodes.
function expectZoneSpreadExpression(expression: string, selectorKeys: string[]) {
    expect(expression).toContain("(inputs.parameters.workloadZoneSpread == 'none') ? ([])");
    expect(expression).toContain(
        "(inputs.parameters.workloadZoneSpread == 'required') ? ('DoNotSchedule') : ('ScheduleAnyway')"
    );
    expect(expression).toContain(`"topologyKey", 'topology.kubernetes.io/zone'`);
    expect(expression).toContain(`"nodeTaintsPolicy", 'Honor'`);
    expect(expression).toContain(`"matchLabelKeys", ${JSON.stringify(selectorKeys)}`);
}

function topologySpreadLine(manifest: string): string {
    const line = manifest.split("\n").find(candidate => candidate.includes("topologySpreadConstraints:"));
    expect(line).toBeDefined();
    return line!;
}

describe("capture/replay workloads spread across availability zones per the deployment setting", () => {
    it.each(["deployproxydeployment", "deployproxydeploymentwithtls"])(
        "derives per-proxy zone spread in %s from provider-config",
        templateName => {
            const found = template(renderWorkflowTemplate(SetupCapture), templateName);
            expectZoneSpreadFromProviderConfig(found);
            expectZoneSpreadExpression(topologySpreadLine(found.resource.manifest), ["migrations/proxy"]);
        }
    );

    it("derives per-replayer zone spread from provider-config", () => {
        const found = template(renderWorkflowTemplate(Replayer), "createdeployment");
        expectZoneSpreadFromProviderConfig(found);
        expectZoneSpreadExpression(topologySpreadLine(found.resource.manifest), ["migrations/replayer"]);
    });

    it("generates Kafka broker spread only beneath the configuration's nodePoolSpecOverrides", () => {
        const found = template(renderWorkflowTemplate(SetupKafka), "deploykafkanodepool");
        expectZoneSpreadFromProviderConfig(found);
        const spec = found.resource.manifest.split("\n").find((line: string) => line.startsWith("spec: "));
        expect(spec).toBeDefined();
        expectZoneSpreadExpression(spec, ["strimzi.io/cluster", "strimzi.io/pool-name"]);
        expect(spec).toContain(`"topologyKey", 'kubernetes.io/hostname', "whenUnsatisfiable", 'ScheduleAnyway'`);
        // sprig.merge gives its first argument precedence: the overrides must be merged before the generated
        // spread, so an explicit template.pod.topologySpreadConstraints replaces it rather than losing to it.
        const overrides = spec.indexOf("fromJSON(inputs.parameters.nodePoolSpec)");
        const generated = spec.indexOf("topologySpreadConstraints");
        expect(overrides).toBeGreaterThan(-1);
        expect(generated).toBeGreaterThan(overrides);
    });
});
