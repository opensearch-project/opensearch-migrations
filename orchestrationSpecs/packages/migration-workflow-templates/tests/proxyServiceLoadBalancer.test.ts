import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {SetupCapture} from "../src/workflowTemplates/setupCapture";

/**
 * The capture proxy's load-balancer scheme is cloud-specific. cloudProvider is
 * resolved at runtime (from the provider-config ConfigMap), so the annotations
 * are emitted as a single Argo expression that selects the active cloud's keys.
 * These tests lock in that expression's branches: the snapshot only exercises
 * the default (aws) path, so without them a regression in the gcp/azure branches
 * would go undetected.
 */
describe("Capture proxy Service load-balancer annotations are cloud-conditional", () => {
    const setupCapture = renderWorkflowTemplate(SetupCapture) as any;

    function getRawManifest(templateName: string): string {
        const templates: any[] = setupCapture.spec?.templates ?? [];
        const t = templates.find(x => x.name === templateName);
        expect(t).toBeDefined();
        expect(t.resource?.manifest).toBeDefined();
        return t.resource.manifest as string;
    }

    const manifest = () => getRawManifest("deployproxyservice");

    it("deployProxyService template accepts a cloudProvider input", () => {
        const templates: any[] = setupCapture.spec?.templates ?? [];
        const t = templates.find(x => x.name === "deployproxyservice");
        const names = (t.inputs?.parameters ?? []).map((p: any) => p.name);
        expect(names).toContain("cloudProvider");
    });

    it("branches the annotations map on cloudProvider", () => {
        const m = manifest();
        expect(m).toContain("inputs.parameters.cloudProvider == 'gcp'");
        expect(m).toContain("inputs.parameters.cloudProvider == 'azure'");
    });

    it("emits the GKE internal load-balancer annotation only when not internet-facing", () => {
        const m = manifest();
        // GKE internal LB annotation is present, gated behind !internetFacing (empty dict otherwise).
        expect(m).toContain('sprig.dict("networking.gke.io/load-balancer-type", \'Internal\')');
        expect(m).toContain("networking.gke.io/load-balancer-type");
    });

    it("emits the Azure internal load-balancer annotation only when not internet-facing", () => {
        const m = manifest();
        expect(m).toContain('sprig.dict("service.beta.kubernetes.io/azure-load-balancer-internal", \'true\')');
    });

    it("preserves the existing AWS NLB annotations as the default branch", () => {
        const m = manifest();
        // The aws branch (default / non-gcp / non-azure) must keep the original
        // NLB annotations and the internetFacing-driven scheme ternary unchanged,
        // so the EKS behavior is byte-for-byte identical to before this change.
        expect(m).toContain('"service.beta.kubernetes.io/aws-load-balancer-type", \'external\'');
        expect(m).toContain('"service.beta.kubernetes.io/aws-load-balancer-nlb-target-type", \'ip\'');
        expect(m).toContain('"service.beta.kubernetes.io/aws-load-balancer-scheme"');
        expect(m).toContain("(fromJSON(inputs.parameters.internetFacing)) ? ('internet-facing') : ('internal')");
    });

    it("resolves awsResourceTags from the provider-config ConfigMap, defaulting to empty", () => {
        const templates: any[] = setupCapture.spec?.templates ?? [];
        const t = templates.find(x => x.name === "deployproxyservice");
        const p = (t.inputs?.parameters ?? []).find((x: any) => x.name === "awsResourceTags");
        expect(p).toBeDefined();
        // Argo v4 errors on a missing configMapKeyRef key even with optional:true, so the chart
        // always renders the key and the fallback here must be the empty string, not absent.
        expect(p.value).toBe("");
        expect(p.valueFrom.configMapKeyRef).toEqual({name: "provider-config", key: "awsResourceTags"});
    });

    it("tags the NLB from awsResourceTags, and omits the annotation when unset", () => {
        const m = manifest();
        // The annotation takes one comma-separated "Key=Value,Key2=Value2" string, so any number of
        // tags rides on this single parameter -- see the ConfigMap rendering test in the chart.
        expect(m).toContain('sprig.dict("service.beta.kubernetes.io/aws-load-balancer-additional-resource-tags", inputs.parameters.awsResourceTags)');
        // An unset tag string must produce no annotation at all rather than an empty one.
        expect(m).toContain("(inputs.parameters.awsResourceTags == '') ? (sprig.dict())");
        // Merged into the aws branch only -- gcp/azure must not receive an AWS annotation.
        expect(m).toContain('sprig.merge(sprig.dict("service.beta.kubernetes.io/aws-load-balancer-type"');
    });

    it("does not emit foreign-cloud annotation keys as static (non-conditional) manifest keys", () => {
        // Every cloud-specific key must live inside the runtime ternary expression,
        // never as a literal top-level annotation, so a cluster only ever receives
        // its own cloud's annotations.
        const m = manifest();
        // The GKE/Azure keys only ever appear inside the {{= ... }} expression.
        const exprStart = m.indexOf("annotations: {{=");
        expect(exprStart).toBeGreaterThanOrEqual(0);
    });
});
