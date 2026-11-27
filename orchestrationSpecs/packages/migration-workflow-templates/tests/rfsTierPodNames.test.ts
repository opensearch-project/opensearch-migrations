import * as fs from "fs";
import * as path from "path";
import {parse as parseYaml} from "yaml";
import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {DocumentBulkLoad} from "../src/workflowTemplates/documentBulkLoad";

// Container metrics from cAdvisor carry no pod labels, so the OTel collector derives each
// RFS worker's memory tier from its pod name (valuesEks.yaml metric_relabel_configs). This pins that regex
// against the Deployment names these templates actually render, plus Kubernetes' pod-name suffixes, so a
// rename on either side fails here instead of silently dropping workers from the per-tier dashboards.

const VALUES_EKS = path.resolve(__dirname,
    "../../../../deployment/k8s/charts/aggregates/migrationAssistantWithArgo/valuesEks.yaml");

// The rendered name is an Argo expression such as
// {{=toJSON(inputs.parameters.sessionName+'-rfs'+'-t'+inputs.parameters.memoryTier)}}; evaluate it with
// real parameter values to get the exact name Kubernetes will see.
function renderedDeploymentName(templateName: string, parameters: Record<string, string>): string {
    const rendered = renderWorkflowTemplate(DocumentBulkLoad as any) as any;
    const template = rendered.spec.templates.find((candidate: any) => candidate.name === templateName);
    const nameLine = (template.resource.manifest as string).split("\n").find(line => line.startsWith("  name: "))!;
    const expression = nameLine.slice("  name: ".length).match(/^\{\{=(.*)\}\}$/)![1];
    const evaluate = new Function("toJSON", "inputs", `return ${expression};`);
    return JSON.parse(evaluate(JSON.stringify, {parameters}));
}

// Kubernetes appends "-<pod-template-hash>-<5-char suffix>" to a Deployment's pod names, drawn from this
// alphabet; the hash is a safe-encoded 32-bit value (up to 10 characters).
const K8S_ALPHABET = "bcdfghjklmnpqrstvwxz2456789";
function podNames(deploymentName: string): string[] {
    const pick = (seed: number, length: number) =>
        Array.from({length}, (_, i) => K8S_ALPHABET[(seed * 7 + i * 13) % K8S_ALPHABET.length]).join("");
    return [1, 2, 3].flatMap(seed => [6, 9, 10].map(hashLength =>
        `${deploymentName}-${pick(seed, hashLength)}-${pick(seed + 11, 5)}`));
}

const SESSIONS = ["rfs-0006fca8c38d4f89", "my-migration", "rfs-t9-looks-like-a-tier"];

describe("RFS pod names carry the memory tier the collector labels them with", () => {
    const values = parseYaml(fs.readFileSync(VALUES_EKS, "utf-8").replace(/\{\{.*?\}\}/g, ""));
    const config = parseYaml(values.metrics.collectorConfig);
    const cadvisor = config.receivers.prometheus.config.scrape_configs.find((job: any) => job.job_name === "cadvisor");
    const tierRules = cadvisor.metric_relabel_configs.filter((rule: any) => rule.target_label === "tier");
    const podRule = tierRules.find((rule: any) => rule.source_labels.join(",") === "pod");
    // Prometheus relabel regexes are fully anchored.
    const tierRegex = new RegExp(`^(?:${podRule.regex})$`);

    it.each(SESSIONS.flatMap(session => ["0", "1", "2", "12"].map(tier => [session, tier])))(
        "session %s, tier %s: every worker pod name yields its tier", (sessionName, memoryTier) => {
            const deployment = renderedDeploymentName("createrfstierdeployment", {sessionName, memoryTier});
            for (const pod of podNames(deployment)) {
                expect(pod.match(tierRegex)?.[1]).toBe(memoryTier);
            }
        });

    it.each(SESSIONS)("session %s without memory scaling: no tier from the name (the collector defaults "
        + "bulk-loader to tier 0)", sessionName => {
        const deployment = renderedDeploymentName("createrfsdeployment", {sessionName});
        for (const pod of podNames(deployment)) {
            expect(pod).not.toMatch(tierRegex);
        }
    });

    it("defaults an unlabeled bulk-loader to tier 0 after matching the pod name", () => {
        expect(tierRules).toEqual([
            expect.objectContaining({source_labels: ["pod"], replacement: "$$1"}),
            expect.objectContaining({source_labels: ["container", "tier"], regex: "bulk-loader;", replacement: "0"}),
        ]);
    });
});
