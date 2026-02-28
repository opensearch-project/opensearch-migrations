import { K3sContainer, StartedK3sContainer } from "@testcontainers/k3s";
import { KubeConfig, CoreV1Api, AppsV1Api, RbacAuthorizationV1Api } from "@kubernetes/client-node";
import * as fs from "fs";

const KUBECONFIG_PATH = "/tmp/integ-test-kubeconfig.yaml";
const META_PATH = "/tmp/integ-test-meta.json";
const TEST_NAMESPACE = "integ-test";
const ARGO_NAMESPACE = "argo";

let container: StartedK3sContainer | null = null;
let kubeConfigContent: string | null = null;

export async function startCluster(): Promise<void> {
  console.log("Starting K3s container...");
  
  container = await new K3sContainer("rancher/k3s:v1.31.6-k3s1")
    .withCommand(["server", "--disable=traefik"])
    .start();

  kubeConfigContent = container.getKubeConfig();
  
  // Write kubeconfig to temp file for test processes
  fs.writeFileSync(KUBECONFIG_PATH, kubeConfigContent);
  fs.writeFileSync(META_PATH, JSON.stringify({
    namespace: TEST_NAMESPACE,
    argoNamespace: ARGO_NAMESPACE,
  }));

  console.log("K3s started, creating namespaces...");
  
  const kc = new KubeConfig();
  kc.loadFromString(kubeConfigContent);
  const coreApi = kc.makeApiClient(CoreV1Api);

  // Create namespaces
  await coreApi.createNamespace({
    body: {
      metadata: { name: ARGO_NAMESPACE },
    },
  });
  
  await coreApi.createNamespace({
    body: {
      metadata: { name: TEST_NAMESPACE },
    },
  });

  console.log("Installing Argo Workflows...");
  
  // Download Argo manifest on the host and copy into the container
  // (K3s BusyBox wget doesn't support HTTPS)
  const argoVersion = "v4.0.0";
  const manifestUrl = `https://github.com/argoproj/argo-workflows/releases/download/${argoVersion}/quick-start-minimal.yaml`;
  const { execSync } = await import("child_process");
  const tempFile = "/tmp/argo-install-manifest.yaml";
  execSync(`curl -sL -o ${tempFile} ${manifestUrl}`);
  
  await container.copyFilesToContainer([{
    source: tempFile,
    target: "/tmp/argo-manifest.yaml",
  }]);
  
  console.log(`Argo ${argoVersion} manifest downloaded and copied to container`);
  
  // Apply manifest with server-side apply (required for large CRDs in Argo 4.0.0)
  const applyResult = await container.exec(["kubectl", "apply", "--server-side", "-f", "/tmp/argo-manifest.yaml"]);
  console.log("Manifest applied");
  if (applyResult.exitCode !== 0) {
    console.error("kubectl apply failed:", applyResult.output);
    throw new Error(`Failed to apply Argo manifest: ${applyResult.output}`);
  }
  
  // Verify CRDs are installed
  const crdCheck = await container.exec(["kubectl", "get", "crd", "workflows.argoproj.io"]);
  if (crdCheck.exitCode !== 0) {
    console.error("Workflow CRD not found:", crdCheck.output);
    throw new Error("Workflow CRD was not installed");
  }
  console.log("Workflow CRD verified");

  console.log("Waiting for Argo controller to be ready...");
  
  const appsApi = kc.makeApiClient(AppsV1Api);
  const timeout = Date.now() + 120_000;
  
  while (Date.now() < timeout) {
    try {
      const deployment = await appsApi.readNamespacedDeployment({
        name: "workflow-controller",
        namespace: ARGO_NAMESPACE,
      });
      
      if ((deployment.status?.readyReplicas ?? 0) >= 1) {
        console.log("Argo controller is ready");
        break;
      }
    } catch (err) {
      // Deployment might not exist yet
    }
    
    await new Promise(resolve => setTimeout(resolve, 2000));
  }

  // Create ServiceAccount and ClusterRoleBinding for test workflows
  await coreApi.createNamespacedServiceAccount({
    namespace: TEST_NAMESPACE,
    body: {
      metadata: { name: "test-runner" },
    },
  });

  const rbacApi = kc.makeApiClient(RbacAuthorizationV1Api);
  await rbacApi.createClusterRoleBinding({
    body: {
      metadata: { name: "test-runner-admin" },
      roleRef: {
        apiGroup: "rbac.authorization.k8s.io",
        kind: "ClusterRole",
        name: "cluster-admin",
      },
      subjects: [{
        kind: "ServiceAccount",
        name: "test-runner",
        namespace: TEST_NAMESPACE,
      }],
    },
  });

  console.log("Cluster setup complete");
}

export async function stopCluster(): Promise<void> {
  if (container) {
    console.log("Stopping K3s container...");
    await container.stop();
    container = null;
  }
  
  // Clean up temp files
  if (fs.existsSync(KUBECONFIG_PATH)) {
    fs.unlinkSync(KUBECONFIG_PATH);
  }
  if (fs.existsSync(META_PATH)) {
    fs.unlinkSync(META_PATH);
  }
  
  console.log("Cluster stopped");
}

export function getKubeConfig(): string {
  if (kubeConfigContent) {
    return kubeConfigContent;
  }
  
  if (fs.existsSync(KUBECONFIG_PATH)) {
    return fs.readFileSync(KUBECONFIG_PATH, "utf-8");
  }
  
  throw new Error("Kubeconfig not available. Cluster may not be started.");
}

export function getTestNamespace(): string {
  return TEST_NAMESPACE;
}

export function getArgoNamespace(): string {
  return ARGO_NAMESPACE;
}
