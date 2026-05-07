/**
 * Kubernetes cleanup step for the Migration Assistant deployment.
 *
 * Delegates to 'pipenv run app --delete-only' (libraries/testAutomation), which
 * is the single source of truth for the MA teardown sequence. That Python
 * cleanup_deployment() runs the CUSTOMER-FACING flow:
 *
 *   cleanup_ack_dashboard_crs      (ACK controller alive → finalise AWS dashboards)
 *   delete_argo_workflows           (argo-workflow-controller alive)
 *   workflow reset --all --include-proxies --delete-storage
 *                                   (MA CRDs + children + Kafka PVCs, correct
 *                                    order, strips stuck finalizers at the end)
 *   helm uninstall ma --timeout 10m (umbrella chart — kills all operators)
 *   cleanup_clusters                (test-only source/target helm releases)
 *   delete_all_pvcs                 (residual PVCs, 300s budget)
 *   delete_namespace                (webhooks + kubectl delete namespace)
 *   wait_for_namespace_deleted      (warn-only, never raises)
 *
 * This helper was historically named 'cdcCleanupStep' because CDC pipelines were
 * the first callers. It's now used by every EKS pipeline (including non-CDC
 * ones like eksAOSSIntegPipeline and eksIntegPipeline) — the Python cleanup
 * handles both CDC and non-CDC workloads uniformly via workflow reset.
 *
 * Historical bug (already fixed upstream): the 'pipenv run app --delete-only'
 * call used to be a plain `sh "..."`, so when the Python raised TimeoutError
 * (from wait_for_namespace_deleted on stuck Strimzi / cert-manager finalizers),
 * the exception unwound out of the post block and every downstream delete-stack
 * / eksCleanupStep / cleanupIsolatedVpc call was skipped. That cascade is the
 * root cause of the chronic MA-CDC-AOSS-* / Migration-Assistant-Infra-Create-
 * VPC-eks-* stack leaks. Fixed in two layers:
 *   (a) The Python no longer raises on namespace-delete timeout.
 *   (b) This helper still uses sh(returnStatus: true) as defense in depth so a
 *       genuine pipenv error (import failure, missing pod, etc.) doesn't abort
 *       the rest of finalCleanup either.
 *
 * Previously this helper also duplicated the 'workflow reset' and
 * 'kubectl delete workflows' commands that Python now handles internally. Those
 * explicit kubectl calls are gone — running them twice was wasting ~2-3 min
 * per pipeline on redundant reset passes and argo workflow deletion.
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

        // Single-call cleanup — Python owns the ordered teardown sequence.
        // returnStatus so genuine Python errors (import failure, pod not found)
        // are logged and the rest of finalCleanup still runs.
        def rc = sh(returnStatus: true,
                    script: "pipenv run app --delete-only --kube-context=${kubeContext}")
        if (rc != 0) {
            echo "cdcCleanupStep: 'pipenv run app --delete-only' exited ${rc} — continuing so downstream EKS/CFN cleanup still runs"
        }

        // Post-mortem diagnostics (informational). These help debug leaked
        // resources on runs where cleanup didn't fully drain — typically a
        // stuck finaliser on a Strimzi / cert-manager CR that an operator
        // no longer exists to process. All guarded with || true; the outer
        // finalCleanup helper will mark UNSTABLE if a CFN stack lingers.
        echo "Post-cleanup state in 'ma' namespace (expected empty):"
        sh """
          kubectl --context=${kubeContext} api-resources --namespaced=true -o name --verbs=list 2>/dev/null | \\
            grep -v '^events' | sort -u | \\
            xargs -n1 -I{} sh -c 'echo "=== {} ==="; kubectl --context='"${kubeContext}"' get {} -n ma --ignore-not-found 2>/dev/null' | \\
            awk '/^=== / {hdr=\$0; buf=""; next} {buf = buf ? buf ORS \$0 : \$0} END{if(buf) print hdr ORS buf}' || true
        """
        echo "Orphaned PVs still bound to 'ma' namespace (expected empty):"
        sh """
          kubectl --context=${kubeContext} get pv -o json 2>/dev/null | \\
            jq -r '.items[]? | select(.spec.claimRef.namespace == "ma") | .metadata.name' || true
        """
    }
}
