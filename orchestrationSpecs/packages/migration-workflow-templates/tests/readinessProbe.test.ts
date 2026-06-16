import * as yaml from "js-yaml";
import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {SetupCapture} from "../src/workflowTemplates/setupCapture";

/** Ensures capture proxy Deployments have a readinessProbe that gates on real init. */
describe("Capture proxy Deployments declare a readinessProbe", () => {
    const setupCapture = renderWorkflowTemplate(SetupCapture) as any;

    function getResourceManifest(rendered: any, templateName: string): any {
        const manifest = getRawManifest(rendered, templateName)
            .replace(/^(\s*)(volumeMounts|volumes): \{\{=.*$/gm, "$1$2: []");
        return yaml.load(manifest);
    }

    function getRawManifest(rendered: any, templateName: string): string {
        const templates: any[] = rendered.spec?.templates ?? [];
        const t = templates.find(x => x.name === templateName);
        expect(t).toBeDefined();
        expect(t.resource?.manifest).toBeDefined();
        return t.resource.manifest as string;
    }

    function getFirstContainer(deployment: any): any {
        expect(deployment.kind).toBe("Deployment");
        const containers = deployment.spec?.template?.spec?.containers;
        expect(Array.isArray(containers)).toBe(true);
        expect(containers.length).toBeGreaterThanOrEqual(1);
        return containers[0];
    }

    function assertMinReadySecondsIsSet(deployment: any) {
        const mrs = deployment.spec?.minReadySeconds;
        expect(typeof mrs).toBe("number");
        expect(mrs).toBeGreaterThanOrEqual(1);
    }

    function getEnv(container: any, name: string): any {
        const env = container.env ?? [];
        expect(Array.isArray(env)).toBe(true);
        return env.find((entry: any) => entry.name === name);
    }

    it("deployProxyDeployment container has a tcpSocket readinessProbe on listenPort with minReadySeconds", () => {
        const deployment = getResourceManifest(setupCapture, "deployproxydeployment");
        const container = getFirstContainer(deployment);
        const probe = container.readinessProbe;
        expect(probe).toBeDefined();
        expect(probe.tcpSocket).toBeDefined();
        expect(probe.tcpSocket.port).toBeDefined();
        const ports = container.ports;
        expect(Array.isArray(ports)).toBe(true);
        expect(ports.length).toBeGreaterThanOrEqual(1);
        expect(probe.tcpSocket.port).toEqual(ports[0].containerPort);
        assertMinReadySecondsIsSet(deployment);
    });

    it("deployProxyDeploymentWithTls container has a tcpSocket readinessProbe on listenPort with minReadySeconds", () => {
        const deployment = getResourceManifest(setupCapture, "deployproxydeploymentwithtls");
        const container = getFirstContainer(deployment);
        const probe = container.readinessProbe;
        expect(probe).toBeDefined();
        expect(probe.tcpSocket).toBeDefined();
        const ports = container.ports;
        expect(probe.tcpSocket.port).toEqual(ports[0].containerPort);
        assertMinReadySecondsIsSet(deployment);
    });

    it("proxy deployment templates escape inline client CA PEM for safe YAML embedding", () => {
        for (const templateName of ["deployproxydeployment", "deployproxydeploymentwithtls"]) {
            const manifest = getRawManifest(setupCapture, templateName);
            const container = getFirstContainer(getResourceManifest(setupCapture, templateName));
            const envEntry = getEnv(container, "CAPTURE_PROXY_SSL_TRUST_CERT_PEM");
            expect(envEntry).toBeDefined();
            expect(envEntry.name).toBe("CAPTURE_PROXY_SSL_TRUST_CERT_PEM");
            // Value must use toBase64 to safely embed PEM in YAML (see #3108)
            expect(manifest).toContain("toBase64");
            expect(manifest).toContain("sslTrustCertPem");
        }
    });

    it("proxy deployment templates append dynamic file-source volumes and mounts", () => {
        for (const templateName of ["deployproxydeployment", "deployproxydeploymentwithtls"]) {
            const manifest = getRawManifest(setupCapture, templateName);
            expect(manifest).toContain("fromJSON(inputs.parameters.fileSourceVolumeMounts)");
            expect(manifest).toContain("fromJSON(inputs.parameters.fileSourceVolumes)");
        }
    });
});
