import * as fs from "fs";
import * as path from "path";
import {parse as parseYaml} from "yaml";
import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {DocumentBulkLoad, RFS_TERMINATION_GRACE_PERIOD_SECONDS} from "../src/workflowTemplates/documentBulkLoad";
import {CONTAINER_NAMES} from "../src/containerNames";
import {
    COMPONENT_LABEL,
    COMPONENTS,
    MIGRATION_RESOURCE_UID_LABEL,
    RFS_MEMORY_TIER_LABEL,
} from "../src/workflowTemplates/commonUtils/resourceLabels";

// Rendered manifests hold Argo expressions as bare `{{...}}` scalars, which YAML would read as
// flow mappings. Quote them so the manifest parses; the expression text is kept for comparison.
function parseManifest(manifest: string): any {
    const quoted = manifest.replace(/: (\{\{.*\}\})$/gm, (_, expression) => `: ${JSON.stringify(expression)}`);
    return parseYaml(quoted);
}

function rawManifestFor(templateName: string): string {
    const rendered = renderWorkflowTemplate(DocumentBulkLoad as any) as any;
    const found = rendered.spec.templates.find((candidate: any) => candidate.name === templateName);
    expect(found?.resource?.manifest).toBeDefined();
    return found.resource.manifest;
}

describe("per-tier RFS deployment manifest", () => {
    const deployment = parseManifest(rawManifestFor("createrfstierdeployment"));
    const pdb = parseManifest(rawManifestFor("createrfstierpoddisruptionbudget"));

    it("names the tier deployment <session>-rfs-t<n>", () => {
        expect(deployment.metadata.name).toContain("'-rfs'+'-t'");
        expect(deployment.metadata.name).toContain("memoryTier");
    });

    it("puts the tier label in the IMMUTABLE spec.selector, the pod-template labels, and the PDB selector", () => {
        for (const selector of [deployment.spec.selector.matchLabels, pdb.spec.selector.matchLabels]) {
            expect(Object.keys(selector).sort()).toEqual(
                [COMPONENT_LABEL, MIGRATION_RESOURCE_UID_LABEL, RFS_MEMORY_TIER_LABEL].sort());
            expect(selector[RFS_MEMORY_TIER_LABEL]).toMatch(/memoryTier/);
        }
        const podLabels = deployment.spec.template.metadata.labels;
        Object.entries(deployment.spec.selector.matchLabels).forEach(
            ([key, value]) => expect(podLabels[key]).toBe(value));
    });

    it("keeps component+uid in the selector so component-wide (tier-less) selection still works", () => {
        expect(deployment.spec.selector.matchLabels[COMPONENT_LABEL]).toBe(COMPONENTS.RFS_WORKER);
        expect(deployment.spec.selector.matchLabels[MIGRATION_RESOURCE_UID_LABEL]).toMatch(/crdUid/);
    });

    it("runs only the bulk loader: crash handling lives in the container's own wrapper", () => {
        const names = deployment.spec.template.spec.containers.map((c: any) => c.name);
        expect(names).toEqual([CONTAINER_NAMES.BULK_LOADER]);
        const raw = rawManifestFor("createrfstierdeployment");
        expect(raw).not.toContain("crash-witness");
    });

    it("tells the worker its tier, which also turns on the wrapper's supervised restarts", () => {
        const bulkLoader = deployment.spec.template.spec.containers[0];
        const env = Object.fromEntries(bulkLoader.env.map((e: any) => [e.name, e.value]));
        expect(env.RFS_WORKER_MEMORY_TIER).toMatch(/memoryTier/);
        expect(env).not.toHaveProperty("RFS_CRASH_VERDICT_FILE");
    });

    it("gives the supervisor a pod-lifetime state dir and the monitor's kernel-OOM evidence file", () => {
        const bulkLoader = deployment.spec.template.spec.containers[0];
        const env = Object.fromEntries(bulkLoader.env.map((e: any) => [e.name, e.value]));
        expect(env.RFS_STATE_DIR).toBe("/var/run/rfs-state");
        expect(env.RFS_CRASH_EVIDENCE_FILE).toBe("/var/run/rfs-evidence/predecessor-crashes");

        // Volumes and mounts are folded into the file-source concat expressions, so they appear
        // in the rendered manifest text. The state dir survives container restarts (emptyDir =
        // pod lifetime); the evidence file is a downward-API projection of the monitor's witness
        // annotation.
        const raw = rawManifestFor("createrfstierdeployment");
        expect(raw).toContain("rfs-state");
        expect(raw).toContain("emptyDir");
        expect(raw).toContain("rfs-crash-evidence");
        expect(raw).toContain("downwardAPI");
        // The fieldPath's inner quotes are escaped in the rendered sprig expression, so match the
        // annotation name and the fieldRef separately.
        expect(raw).toContain("fieldPath");
        expect(raw).toContain("migrations.opensearch.org/rfs-predecessor-crashes");
    });

    it("sets the tier's memory for both requests and limits (Guaranteed QoS stays intact)", () => {
        const resources = deployment.spec.template.spec.containers[0].resources;
        expect(resources).toContain("tierMemoryQuantity");
        // requests.memory and limits.memory are overridden with the SAME tier value.
        const overrides = (resources.match(/tierMemoryQuantity/g) ?? []).length;
        expect(overrides).toBe(2);
    });

    it("raises the termination grace period to cover the worst-case clean shutdown", () => {
        expect(deployment.spec.template.spec.terminationGracePeriodSeconds)
            .toBe(RFS_TERMINATION_GRACE_PERIOD_SECONDS);
        expect(RFS_TERMINATION_GRACE_PERIOD_SECONDS).toBe(390);
    });
});

describe("feature-off byte-identity", () => {
    // Captured from the pre-memory-scaling rendering of these templates. The feature-off path
    // must keep producing exactly these bytes: same Deployment name, selectors, labels, pod spec.
    const frozen = JSON.parse(fs.readFileSync(
        path.join(__dirname, "fixtures", "rfsFeatureOffManifests.json"), "utf-8"));

    it.each(Object.keys(frozen))("%s renders byte-identically to the pre-feature manifest", templateName => {
        expect(rawManifestFor(templateName)).toBe(frozen[templateName]);
    });

    it("the feature-off deployment has no tier label, sidecar, or grace-period override", () => {
        const raw = rawManifestFor("createrfsdeployment");
        expect(raw).not.toContain(RFS_MEMORY_TIER_LABEL);
        expect(raw).not.toContain("crash-witness");
        expect(raw).not.toContain("terminationGracePeriodSeconds");
        expect(raw).not.toContain("RFS_WORKER_MEMORY_TIER");
    });
});
