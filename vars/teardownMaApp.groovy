/**
 * teardownMaApp — Application-layer teardown for Migration Assistant.
 *
 * Delegates to 'pipenv run app --delete-only', which runs the
 * customer-facing sequence (workflow reset -> helm uninstall -> PVCs ->
 * namespace). Call this FIRST. Safe for minikube and EKS.
 * See libraries/testAutomation/testAutomation/test_runner.py::cleanup_deployment.
 *
 * Uses sh(returnStatus:) so Python failures don't cascade past the rest
 * of the Jenkins post block.
 *
 * Usage:
 *   teardownMaApp(kubeContext: env.eksKubeContext)
 */
def call(Map config = [:]) {
    def kubeContext = config.kubeContext
    if (!kubeContext) { error("teardownMaApp: 'kubeContext' is required") }

    dir('libraries/testAutomation') {
        sh "pipenv install --deploy"
        sh "kubectl --context=${kubeContext} -n ma get pods || true"

        def rc = sh(returnStatus: true,
                    script: "pipenv run app --delete-only --kube-context=${kubeContext}")
        if (rc == 2) {
            unstable("teardownMaApp: residual cluster state (PVCs and/or 'ma' namespace) — infra teardown will remove residue (rc=2)")
        } else if (rc != 0) {
            unstable("teardownMaApp: 'pipenv run app --delete-only' exited ${rc} — continuing so downstream EKS/CFN cleanup still runs")
        }

        // Post-mortem dump (|| true; outer helpers flag stuck stacks).
        echo "Residual resources in 'ma' (expected empty):"
        sh "kubectl --context=${kubeContext} get all,pvc,cm,secret,crd -n ma 2>/dev/null || true"
        echo "Orphaned PVs bound to 'ma' (expected empty):"
        sh """
          kubectl --context=${kubeContext} get pv -o json 2>/dev/null | \\
            jq -r '.items[]? | select(.spec.claimRef.namespace == "ma") | .metadata.name' || true
        """
    }
}
