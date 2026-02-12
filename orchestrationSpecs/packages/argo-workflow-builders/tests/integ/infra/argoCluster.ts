import { K3sContainer, StartedK3sContainer } from "@testcontainers/k3s";
import { KubeConfig, CoreV1Api, AppsV1Api } from "@kubernetes/client-node";
import * as fs from "fs";
import * as path from "path";
import * as yaml from "yaml";

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
    metadata: { name: ARGO_NAMESPACE },
  });
  
  await coreApi.createNamespace({
    metadata: { name: TEST_NAMESPACE },
  });

  console.log("Installing Argo Workflows...");
  
  // Read and apply Argo manifest using kubectl inside the container
  const manifestPath = path.join(__dirname, "../fixtures/quick-start-minimal.yaml");
  const manifestContent = fs.readFileSync(manifestPath, "utf-8");
  
  // Write manifest to container
  await container.exec(["sh", "-c", `cat > /tmp/argo-manifest.yaml << 'EOF'\n${manifestContent}\nEOF`]);
  
  // Apply manifest
  await container.exec(["kubectl", "apply", "-f", "/tmp/argo-manifest.yaml", "-n", ARGO_NAMESPACE]);

  console.log("Waiting for Argo controller to be ready...");
  
  const appsApi = kc.makeApiClient(AppsV1Api);
  const timeout = Date.now() + 120_000;
  
  while (Date.now() < timeout) {
    try {
      const deployment = await appsApi.readNamespacedDeployment(
        "workflow-controller",
        ARGO_NAMESPACE
      );
      
      if ((deployment.body.status?.readyReplicas ?? 0) >= 1) {
        console.log("Argo controller is ready");
        break;
      }
    } catch (err) {
      // Deployment might not exist yet
    }
    
    await new Promise(resolve => setTimeout(resolve, 2000));
  }

  // Create ServiceAccount and ClusterRoleBinding for test workflows
  await coreApi.createNamespacedServiceAccount(TEST_NAMESPACE, {
    metadata: { name: "test-runner" },
  });

  const rbacApi = kc.makeApiClient(require("@kubernetes/client-node").RbacAuthorizationV1Api);
  await rbacApi.createClusterRoleBinding({
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
