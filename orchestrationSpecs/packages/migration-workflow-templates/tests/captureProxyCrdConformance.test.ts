import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {ARGO_PROXY_OPTIONS, generateMigrationCrdsYaml} from "@opensearch-migrations/schemas";
import {parseAllDocuments} from "yaml";
import {ResourceManagement} from "../src/workflowTemplates/resourceManagement";

/**
 * Regression guard for the CaptureProxy mTLS leak (customer-reported: the
 * `upsertcaptureproxyresource` step went to Error when `clientAuth` was set).
 *
 * Root cause: the runtime CR builder (`makeCaptureProxyManifest`) strips the
 * deployment-only fields via `ARGO_PROXY_WORKFLOW_OPTION_KEYS`, but that key set
 * is tuned for the Java-args split in `setupCapture` and intentionally KEEPS
 * `requireClientAuth` / `sslTrustCertPemEnvVar` / `sslTrustCertFile`. So those
 * resolved-only fields pass through into `CaptureProxy.spec`, which the CRD
 * (projected from USER_PROXY_OPTIONS) does not define -> `kubectl apply` rejects
 * the unknown spec fields and the step fails.
 *
 * Invariant: any field that the runtime builder can emit into the CaptureProxy
 * spec must be a field the CaptureProxy CRD actually allows. We derive all three
 * sets from live sources so the test tracks the real code:
 *   - emittable = (keys ARGO_PROXY_OPTIONS can carry) - (keys the rendered
 *                  manifest's sprig.omit removes)
 *   - allowed   = top-level properties on the generated CaptureProxy CRD spec
 */
describe("CaptureProxy CR builder conformance with the generated CRD", () => {
    function getUpsertProxyManifest(): string {
        const rendered = renderWorkflowTemplate(ResourceManagement) as any;
        const templates: any[] = rendered.spec?.templates ?? [];
        const tmpl = templates.find(t => t.name === "upsertcaptureproxyresource");
        expect(tmpl?.resource?.manifest).toBeDefined();
        return tmpl.resource.manifest as string;
    }

    /** Extract the balanced `sprig.omit( ... )` call substring from the manifest. */
    function extractOmitCall(manifest: string): string {
        const start = manifest.indexOf("sprig.omit(");
        expect(start).toBeGreaterThanOrEqual(0);
        let depth = 0;
        const open = manifest.indexOf("(", start);
        for (let i = open; i < manifest.length; i++) {
            const ch = manifest[i];
            if (ch === "(") depth++;
            else if (ch === ")") {
                depth--;
                if (depth === 0) return manifest.slice(open + 1, i);
            }
        }
        throw new Error("unbalanced sprig.omit(...) in rendered manifest");
    }

    /**
     * The keys removed by sprig.omit. The first arg is the proxyConfig accessor
     * (`fromJSON(...)['proxyConfig']`); the rest are single-quoted field names.
     * Split off the first top-level comma, then collect quoted tokens.
     */
    function parseOmittedKeys(manifest: string): Set<string> {
        const omitArgs = extractOmitCall(manifest);
        let depth = 0;
        let firstComma = -1;
        for (let i = 0; i < omitArgs.length; i++) {
            const ch = omitArgs[i];
            if (ch === "(" || ch === "[") depth++;
            else if (ch === ")" || ch === "]") depth--;
            else if (ch === "," && depth === 0) { firstComma = i; break; }
        }
        expect(firstComma).toBeGreaterThanOrEqual(0);
        const fieldList = omitArgs.slice(firstComma + 1);
        const keys = [...fieldList.matchAll(/'([^']+)'/g)].map(m => m[1]);
        expect(keys.length).toBeGreaterThan(0);
        return new Set(keys);
    }

    function captureProxyCrdAllowedSpecKeys(): Set<string> {
        const docs = parseAllDocuments(generateMigrationCrdsYaml()).map(d => d.toJS());
        const crd = docs.find((d: any) => d?.spec?.names?.kind === "CaptureProxy");
        expect(crd).toBeDefined();
        const specSchema = crd.spec.versions[0].schema.openAPIV3Schema.properties.spec;
        // The CaptureProxy spec is a structural (non preserve-unknown) object, so
        // unknown fields are rejected on apply. Guard that assumption explicitly.
        expect(specSchema["x-kubernetes-preserve-unknown-fields"]).toBeFalsy();
        expect(specSchema.additionalProperties).toBeFalsy();
        return new Set(Object.keys(specSchema.properties));
    }

    function argoProxyOptionKeys(): string[] {
        const shape = (ARGO_PROXY_OPTIONS as any).shape;
        return Object.keys(typeof shape === "function" ? shape() : shape);
    }

    it("emits no CaptureProxy spec field that the CRD does not define", () => {
        const manifest = getUpsertProxyManifest();
        const omitted = parseOmittedKeys(manifest);
        const allowed = captureProxyCrdAllowedSpecKeys();

        // Keys that survive the omit and therefore land in CaptureProxy.spec.
        const emittable = argoProxyOptionKeys().filter(k => !omitted.has(k));
        const leaked = emittable.filter(k => !allowed.has(k));

        expect({leaked, omitted: [...omitted].sort()}).toEqual({
            leaked: [],
            omitted: [...omitted].sort(),
        });
    });

    it("strips the resolved mTLS bridge fields that the customer config triggers", () => {
        // These resolved-only fields are produced by clientAuth lowering
        // (trustedClientCaFile/trustedClientCaPem -> sslTrustCert*, requireClientAuth).
        // None are CRD fields, so all must be removed by the runtime builder.
        const mustBeStripped = [
            "requireClientAuth",
            "sslTrustCertPemEnvVar",
            "sslTrustCertFile",
            "fileSourceVolumes",
            "fileSourceVolumeMounts",
        ];
        const omitted = parseOmittedKeys(getUpsertProxyManifest());
        const stillLeaking = mustBeStripped.filter(k => !omitted.has(k));
        expect(stillLeaking).toEqual([]);
    });
});
