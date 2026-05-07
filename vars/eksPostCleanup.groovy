/**
 * post { always { ... } } convenience wrapper for EKS pipelines.
 *
 * Composes the identical ~30-line post-cleanup boilerplate that used to be
 * copy-pasted across every EKS pipeline into a single call. Handles:
 *
 *   - `timeout(...)` + inner `script { ... }` block
 *   - `withMigrationsTestAccount(region, duration)` role assumption
 *   - `mkdir -p libraries/testAutomation/logs`
 *   - Building the finalCleanup() step list for the standard pipeline shape:
 *       (cdc-k8s → eks-k8s) → (cluster cfn-destroy OR cdk-destroy)
 *       → <extras before MA> → MA cfn-destroy
 *       → <extras after MA> → kube-context
 *   - verifyStacks = [maStackName] + [clusterStackName?] + extraVerifyStacks
 *   - `archiveArtifacts libraries/testAutomation/logs/**`
 *
 * The cluster stack is ordered BEFORE the MA stack iff `clusterInsideMaVpc: true`.
 * When the CDK context file is supplied instead, an explicit `cdk-destroy` step
 * runs `./awsDeployCluster.sh --destroy` in the same slot.
 *
 * Minimum viable call (eksIntegPipeline / eksCdcIntegPipeline):
 *
 *   eksPostCleanup(
 *       maStackName: env.MA_STACK_NAME ?: "Migration-Assistant-Infra-Create-VPC-eks-${env.maStageName}-${params.REGION}",
 *       clusterStackName: "OpenSearch-${env.maStageName}-${params.REGION}",
 *       cdkContextFile: clusterContextFilePath,
 *   )
 *
 * AOSS-in-MA-VPC call (eksAOSSIntegPipeline / eksCdcAossIntegPipeline):
 *
 *   eksPostCleanup(
 *       maStackName: env.STACK_NAME,
 *       clusterStackName: env.CLUSTER_STACK,
 *       clusterInsideMaVpc: true,
 *       timeoutMinutes: 60,
 *   )
 *
 * BYOS (paired SG revoke before MA delete):
 *
 *   eksPostCleanup(
 *       maStackName: env.MA_STACK_NAME ?: "...",
 *       clusterStackName: "OpenSearch-${env.maStageName}-${params.REGION}",
 *       cdkContextFile: clusterContextFilePath,
 *       stepsBeforeMaDelete: [[type: 'revoke-sg', sgId: targetSgId, sourceSg: env.clusterSecurityGroup]],
 *   )
 *
 * Isolated (isolated-vpc + build-stack confirm after MA delete):
 *
 *   eksPostCleanup(
 *       maStackName: isolatedStackName,
 *       clusterStackName: "OpenSearch-${env.maStageName}-${params.REGION}",
 *       cdkContextFile: clusterContextFilePath,
 *       stepsAfterMaDelete: [
 *           [type: 'isolated-vpc', vpcId: isolatedVpcId, stage: env.maStageName],
 *           [type: 'cfn-destroy', stackName: buildStackName, reason: 'build stack confirm'],
 *       ],
 *       extraVerifyStacks: [buildStackName],
 *   )
 *
 * Config keys:
 *   maStackName          (required) — name of the MA CFN stack (owns the VPC)
 *   clusterStackName     (optional) — adjacent cluster stack; included in verifyStacks
 *   clusterInsideMaVpc   (optional, default false) — order cluster BEFORE MA
 *   cdkContextFile       (optional) — if set (and clusterInsideMaVpc=false), runs
 *                                      ./awsDeployCluster.sh --destroy for AOS domains
 *   includeK8sCleanup    (optional, default true) — include cdc-k8s + eks-k8s steps
 *   eksClusterName       (optional) — override env.eksClusterName (for pipelines that
 *                                      don't call bootstrapMA, e.g. eksSolutionsCFNTest)
 *   eksKubeContext       (optional) — override env.eksKubeContext
 *   stepsBeforeMaDelete  (optional list) — extra finalCleanup steps inserted between
 *                                           cluster cleanup and MA cfn-destroy
 *   stepsAfterMaDelete   (optional list) — extra steps inserted after MA cfn-destroy
 *                                           (e.g. isolated-vpc, build-stack confirmation)
 *   extraVerifyStacks    (optional list) — additional stack names to verify
 *   region               (optional) — defaults to params.REGION then 'us-east-1'
 *   timeoutMinutes       (optional, default 75)
 *   sessionSeconds       (optional, default 4500) — IAM role session duration
 *   logsDir              (optional, default 'libraries/testAutomation/logs')
 */
def call(Map config = [:]) {
    if (!config.maStackName) { error("eksPostCleanup: 'maStackName' is required") }

    def region = config.region ?: params.REGION ?: 'us-east-1'
    def timeoutMin = config.timeoutMinutes ?: 75
    def sessionSec = config.sessionSeconds ?: 4500
    def logsDir = config.logsDir ?: 'libraries/testAutomation/logs'
    def eksClusterName = config.eksClusterName ?: env.eksClusterName
    def eksKubeContext = config.eksKubeContext ?: env.eksKubeContext

    timeout(time: timeoutMin, unit: 'MINUTES') {
        script {
            withMigrationsTestAccount(region: region, duration: sessionSec) { accountId ->
                sh "mkdir -p '${logsDir}'"

                def steps = buildStepList(config, eksClusterName, eksKubeContext)

                def verify = [config.maStackName]
                if (config.clusterStackName) { verify << config.clusterStackName }
                if (config.extraVerifyStacks) { verify.addAll(config.extraVerifyStacks) }

                finalCleanup(region: region, steps: steps, verifyStacks: verify)
            }
        }
    }
    archiveArtifacts artifacts: "${logsDir}/**", allowEmptyArchive: true
}

private List buildStepList(Map config, String eksClusterName, String eksKubeContext) {
    def steps = []

    // 1. K8s-level cleanup (while EKS cluster still alive) — cdc-k8s also handles
    //    non-CDC pipelines cleanly because 'workflow reset' is a no-op when no
    //    CDC CRDs exist.
    if (config.includeK8sCleanup != false && eksClusterName && eksKubeContext) {
        steps << [type: 'cdc-k8s', kubeContext: eksKubeContext]
        steps << [type: 'eks-k8s',
                  stackName: config.maStackName,
                  eksClusterName: eksClusterName,
                  kubeContext: eksKubeContext]
    }

    // 2. Cluster stack. If it lives inside the MA VPC (AOSS collection, isolated
    //    AOS domains) it MUST be deleted BEFORE the MA stack to avoid
    //    DELETE_FAILED from a VPC still holding ENIs. Otherwise (AOS domains
    //    deployed via CDK as public-access stacks) use cdk-destroy which calls
    //    ./awsDeployCluster.sh --destroy.
    if (config.clusterInsideMaVpc && config.clusterStackName) {
        steps << [type: 'cfn-destroy',
                  stackName: config.clusterStackName,
                  reason: 'cluster stack inside MA VPC — MUST precede MA stack delete']
    } else if (config.cdkContextFile) {
        steps << [type: 'cdk-destroy',
                  stage: env.maStageName,
                  contextFile: config.cdkContextFile]
    }

    // 3. Pipeline-specific extras BEFORE MA delete (e.g. BYOS revoke-sg — breaks
    //    cross-SG references so ENIs release before we delete the VPC).
    if (config.stepsBeforeMaDelete) { steps.addAll(config.stepsBeforeMaDelete) }

    // 4. MA stack — always last so the VPC is released.
    steps << [type: 'cfn-destroy', stackName: config.maStackName, reason: 'MA stack owns the VPC']

    // 5. Pipeline-specific extras AFTER MA delete (e.g. isolated-vpc teardown,
    //    test-VPC stack deletion, build-stack confirmation).
    if (config.stepsAfterMaDelete) { steps.addAll(config.stepsAfterMaDelete) }

    // 6. Local kubeconfig entry cleanup.
    if (eksKubeContext) { steps << [type: 'kube-context', name: eksKubeContext] }

    return steps
}
