// Wrapper to load kubernetes client dynamically
let k8sClient: any = null;

export async function getK8sClient() {
  if (!k8sClient) {
    k8sClient = await import("@kubernetes/client-node");
  }
  return k8sClient;
}
