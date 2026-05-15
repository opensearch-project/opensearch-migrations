/**
 * finalCleanup — dependency-ordered cleanup engine for EKS pipeline post blocks.
 *
 * Runs each step in isolation (catchError) so a failure on step N doesn't
 * prevent step N+1. Every CFN delete uses sh(returnStatus:) with explicit
 * exit-code checks — no '|| true' masking. After all steps, verifyStacks
 * probes each expected CFN stack name and marks the build UNSTABLE with
 * the concrete stack+status pair if anything lingers.
 *
 * Step types:
 *   ma-k8s       — run teardownMaApp(kubeContext:)
 *   eks-k8s      — run cleanupEksInfra(stackName:, eksClusterName:, kubeContext:)
 *   cdk-destroy  — awsDeployCluster.sh --destroy (fileExists-guarded)
 *   cfn-destroy  — aws cloudformation delete-stack + wait
 *   isolated-vpc — cleanupIsolatedVpc(region:, vpcId:, stage:)
 *   revoke-sg    — aws ec2 revoke-security-group-ingress (idempotent)
 *
 * Usage:
 *   finalCleanup(
 *       region: 'us-east-1',
 *       steps: [
 *           [type: 'ma-k8s',      kubeContext: env.eksKubeContext],
 *           [type: 'eks-k8s',     stackName: env.MA_STACK_NAME,
 *                                 eksClusterName: env.eksClusterName,
 *                                 kubeContext: env.eksKubeContext],
 *           [type: 'cfn-destroy', stackName: env.CLUSTER_STACK,
 *                                 reason: 'AOSS collection inside MA VPC'],
 *           [type: 'cfn-destroy', stackName: env.MA_STACK_NAME,
 *                                 reason: 'MA stack owns the VPC'],
 *       ],
 *       verifyStacks: [env.MA_STACK_NAME, env.CLUSTER_STACK],
 *   )
 */
def call(Map config) {
    def region = config.region ?: error("finalCleanup: 'region' is required")
    def steps = config.steps ?: []
    def verifyStacks = (config.verifyStacks ?: []).findAll { it }

    for (step in steps) {
        def label = step.reason ? "${step.type} — ${step.reason}" : step.type
        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE',
                   message: "finalCleanup step '${label}' failed") {
            echo "finalCleanup: ${label}"
            runStep(step, region)
        }
    }

    def failures = []
    for (name in verifyStacks) {
        def status = stackStatus(name, region)
        if (status != 'GONE' && status != 'DELETE_COMPLETE') {
            failures << "${name}: ${status}"
        }
    }
    if (failures) {
        currentBuild.result = 'UNSTABLE'
        unstable("finalCleanup: stacks still present after cleanup: ${failures.join(', ')}")
    }
}

def runStep(Map step, String region) {
    switch (step.type) {
        case 'ma-k8s':
            teardownMaApp(kubeContext: step.kubeContext)
            break

        case 'eks-k8s':
            cleanupEksInfra(
                stackName: step.stackName,
                eksClusterName: step.eksClusterName,
                kubeContext: step.kubeContext,
            )
            break

        case 'cdk-destroy':
            if (!fileExists("test/${step.contextFile}")) {
                echo "finalCleanup: cdk-destroy skipped — context file 'test/${step.contextFile}' not present (pipeline may have died before deploy)"
                return
            }
            dir('test') {
                def rc = sh(returnStatus: true,
                            script: "./awsDeployCluster.sh --stage ${step.stage} --context-file ${step.contextFile} --destroy")
                if (rc != 0) {
                    error("cdk-destroy failed (rc=${rc}) for stage ${step.stage}")
                }
            }
            break

        case 'cfn-destroy':
            def rc = sh(returnStatus: true, script: """set +e
                aws cloudformation delete-stack --stack-name '${step.stackName}' --region '${region}'
                aws cloudformation wait stack-delete-complete --stack-name '${step.stackName}' --region '${region}'
            """)
            if (rc != 0) {
                sh(returnStatus: true, script: """
                    aws cloudformation describe-stack-events --stack-name '${step.stackName}' --region '${region}' \\
                        --max-items 25 --query "StackEvents[?ResourceStatus=='DELETE_FAILED']" 2>&1 || true
                """)
                error("cfn-destroy failed (rc=${rc}) for stack ${step.stackName}")
            }
            break

        case 'isolated-vpc':
            cleanupIsolatedVpc(region: region, vpcId: step.vpcId, stage: step.stage)
            break

        case 'revoke-sg':
            // Idempotent — revoke is a no-op if the rule doesn't exist.
            sh(returnStatus: true, script: """
                aws ec2 revoke-security-group-ingress --group-id '${step.sgId}' \\
                    --protocol -1 --port -1 --source-group '${step.sourceSg}' \\
                    --region '${region}' 2>/dev/null || true
            """)
            break

        default:
            error("finalCleanup: unknown step type '${step.type}'")
    }
}

def stackStatus(String name, String region) {
    return sh(returnStdout: true, script: """
        aws cloudformation describe-stacks --stack-name '${name}' --region '${region}' \\
            --query 'Stacks[0].StackStatus' --output text 2>/dev/null || echo 'GONE'
    """).trim()
}
