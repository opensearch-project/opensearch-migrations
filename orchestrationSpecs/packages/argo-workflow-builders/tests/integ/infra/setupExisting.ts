import * as fs from "fs";

const KUBECONFIG_PATH = "/tmp/integ-test-kubeconfig.yaml";
const META_PATH = "/tmp/integ-test-meta.json";

/**
 * Setup for running integ tests against an existing cluster.
 * The namespace must already exist with Argo running in it.
 *
 * Environment variables:
 *   KUBECONFIG            - path to kubeconfig (default: ~/.kube/config)
 *   INTEG_TEST_NAMESPACE  - namespace to submit test workflows (default: ma)
 *   INTEG_ARGO_NAMESPACE  - namespace where Argo is installed (default: ma)
 */
export default async function globalSetup() {
    const kubeconfigSrc = process.env.KUBECONFIG ?? `${process.env.HOME}/.kube/config`;
    const namespace = process.env.INTEG_TEST_NAMESPACE ?? "ma";
    const argoNamespace = process.env.INTEG_ARGO_NAMESPACE ?? "ma";

    if (!fs.existsSync(kubeconfigSrc)) {
        throw new Error(`Kubeconfig not found at ${kubeconfigSrc}. Set KUBECONFIG env var.`);
    }

    fs.copyFileSync(kubeconfigSrc, KUBECONFIG_PATH);
    fs.writeFileSync(META_PATH, JSON.stringify({
        namespace,
        argoNamespace,
        serviceAccountName: process.env.INTEG_SERVICE_ACCOUNT ?? "argo-workflow-executor"
    }));

    console.log(`Using existing cluster (${kubeconfigSrc}), namespace: ${namespace}`);
}
