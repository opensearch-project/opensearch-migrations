/**
 * CDC-specific Kubernetes cleanup before eksCleanupStep.
 *
 * Handles resources unique to CDC pipelines that block clean namespace deletion:
 * - Argo workflows (finalizer hangs)
 * - Helm-managed CDC resources (via testAutomation --delete-only)
 * - Kafka/KafkaNodePool finalizers (Strimzi operator)
 * - Capture proxy LoadBalancer service (NLB deprovisioning)
 *
 * Call this BEFORE eksCleanupStep, which handles namespace deletion,
 * instance profiles, and orphaned security groups.
 *
 * Usage:
 *   cdcCleanupStep(kubeContext: env.eksKubeContext)
 */
def call(Map config = [:]) {
    def kubeContext = config.kubeContext
    if (!kubeContext) { error("cdcCleanupStep: 'kubeContext' is required") }

    dir('libraries/testAutomation') {
        sh "pipenv install --deploy"
        sh "kubectl --context=${kubeContext} -n ma get pods || true"
        sh "kubectl --context=${kubeContext} -n ma delete workflows --all --timeout=60s || true"
        sh "pipenv run app --delete-only --kube-context=${kubeContext}"
        echo "List resources not removed by helm uninstall:"
        sh "kubectl --context=${kubeContext} get all,pvc,configmap,secret,workflow -n ma -o wide --ignore-not-found || true"
        sh """
          for resource in \$(kubectl --context=${kubeContext} get kafka,kafkanodepool,workflows -n ma -o name 2>/dev/null); do
            kubectl --context=${kubeContext} patch \$resource -n ma --type=merge -p '{"metadata":{"finalizers":[]}}' || true
          done
        """
        sh "kubectl --context=${kubeContext} delete service capture-proxy -n ma --timeout=30s || true"
    }
}
