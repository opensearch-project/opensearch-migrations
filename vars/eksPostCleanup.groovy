/**
 * eksPostCleanup — one-shot post { always } for EKS pipelines.
 *
 * Builds a dependency-ordered step list and delegates to finalCleanup.
 * Pipelines' post block shrinks to a single invocation. Every pipeline
 * gets the same timeout wrapper, withMigrationsTestAccount, log
 * archiving, and kubectl context cleanup.
 *
 * Arguments:
 *   maStackName         (required) MA CFN stack — deleted LAST by default.
 *   clusterStackName    (optional) source/target cluster CFN stack (null
 *                       for smoke tests / BYOS-only).
 *   clusterInsideMaVpc  (default false) if true, delete cluster BEFORE MA.
 *                       Set for AOSS collections inside the MA VPC.
 *   kubeContext         (optional) EKS kubectl context; omit when no EKS
 *                       cluster was deployed.
 *   eksClusterName      (optional) EKS cluster name; omit if kubeContext
 *                       omitted.
 *   cdkContextFile      (optional) if cluster stack is CDK-managed, use
 *                       cdk-destroy via awsDeployCluster.sh instead of
 *                       raw CFN delete.
 *   cdkStage            (optional) stage name for cdk-destroy.
 *   stepsBeforeMaDelete (optional) extra finalCleanup steps inserted
 *                       between infra cleanup and MA stack delete.
 *   stepsAfterMaDelete  (optional) extra finalCleanup steps appended
 *                       after MA stack delete.
 *   extraVerifyStacks   (optional) additional stack names to verify gone.
 *   timeoutMinutes      (default 75) post-block timeout.
 *   region              (default params.REGION) AWS region.
 *   archiveLogs         (default true) archive libraries/testAutomation/logs.
 *
 * Usage (EKS with independent AOS domains, cluster stack lives outside MA VPC):
 *   eksPostCleanup(
 *       maStackName: env.MA_STACK_NAME,
 *       clusterStackName: env.CLUSTER_STACK,
 *       kubeContext: env.eksKubeContext,
 *       eksClusterName: env.eksClusterName,
 *       cdkContextFile: clusterContextFilePath,
 *       cdkStage: env.maStageName,
 *   )
 *
 * Usage (EKS + AOSS collection inside MA VPC):
 *   eksPostCleanup(
 *       maStackName: env.STACK_NAME,
 *       clusterStackName: env.CLUSTER_STACK,
 *       clusterInsideMaVpc: true,
 *       kubeContext: env.eksKubeContext,
 *       eksClusterName: env.eksClusterName,
 *   )
 */
def call(Map config) {
    def region = config.region ?: params.REGION ?: 'us-east-1'
    def timeoutMinutes = config.timeoutMinutes ?: 75
    def archiveLogs = config.archiveLogs != false
    def maStackName = config.maStackName ?: error("eksPostCleanup: 'maStackName' is required")
    def clusterStackName = config.clusterStackName
    def clusterInsideMaVpc = config.clusterInsideMaVpc ?: false
    def kubeContext = config.kubeContext
    def eksClusterName = config.eksClusterName
    def cdkContextFile = config.cdkContextFile
    def cdkStage = config.cdkStage

    timeout(time: timeoutMinutes, unit: 'MINUTES') {
        script {
            withMigrationsTestAccount(region: region, duration: 4500) { accountId ->
                if (archiveLogs) {
                    sh "mkdir -p libraries/testAutomation/logs"
                    archiveArtifacts artifacts: 'libraries/testAutomation/logs/**', allowEmptyArchive: true
                }

                def steps = []

                // App + infra cleanup: only run if the cluster was actually deployed.
                if (kubeContext && eksClusterName) {
                    steps << [type: 'ma-k8s', kubeContext: kubeContext]
                    steps << [type: 'eks-k8s',
                              stackName: maStackName,
                              eksClusterName: eksClusterName,
                              kubeContext: kubeContext]
                }

                // Caller-supplied steps before any VPC-adjacent delete (e.g. BYOS SG revoke).
                config.stepsBeforeMaDelete?.each { steps << it }

                // Cluster stack BEFORE MA if it lives inside MA's VPC.
                if (clusterStackName && clusterInsideMaVpc) {
                    steps << buildClusterDestroyStep(clusterStackName, cdkContextFile, cdkStage)
                }

                // MA stack.
                steps << [type: 'cfn-destroy', stackName: maStackName, reason: 'MA stack']

                // Cluster stack AFTER MA if independent of MA's VPC.
                if (clusterStackName && !clusterInsideMaVpc) {
                    steps << buildClusterDestroyStep(clusterStackName, cdkContextFile, cdkStage)
                }

                // Caller-supplied steps after MA delete (e.g. isolated-vpc cleanup).
                config.stepsAfterMaDelete?.each { steps << it }

                def verifyStacks = [maStackName]
                if (clusterStackName) { verifyStacks << clusterStackName }
                config.extraVerifyStacks?.each { verifyStacks << it }

                finalCleanup(
                    region: region,
                    steps: steps,
                    verifyStacks: verifyStacks,
                )

                if (kubeContext) {
                    sh """
                        if command -v kubectl >/dev/null 2>&1; then
                            kubectl config delete-context ${kubeContext} 2>/dev/null || true
                        fi
                    """
                }
            }
        }
    }
}

def buildClusterDestroyStep(String stackName, String cdkContextFile, String cdkStage) {
    if (cdkContextFile && cdkStage) {
        return [type: 'cdk-destroy', stage: cdkStage, contextFile: cdkContextFile]
    }
    return [type: 'cfn-destroy', stackName: stackName, reason: 'cluster stack']
}
