/**
 * Shared cleanup entry point for EKS pipelines. Call from post { always }.
 *
 * Problems this helper fixes (see root-cause analysis in the cleanup audit):
 *
 *   B1  cdcCleanupStep → pipenv run app --delete-only raises TimeoutError when the
 *       'ma' namespace is stuck on finalizers. The exception used to unwind out of
 *       the Groovy closure and skip every downstream delete-stack / eksCleanup /
 *       cleanupIsolatedVpc call. Here each step runs inside its own try/catch so
 *       a single failure never short-circuits the rest.
 *
 *   B2  Parallel `aws cloudformation delete-stack` of two CFN stacks where one
 *       owns the VPC the other sits inside (AOSS collection inside MA VPC) races
 *       into DELETE_FAILED. Here the caller supplies an ordered list of steps;
 *       dependents go before their owners.
 *
 *   B3  `aws cloudformation delete-stack ... || true` and `aws cloudformation wait
 *       stack-delete-complete ... || true` silently swallow every failure. Here we
 *       use sh(returnStatus: true) and check exit codes explicitly, surfacing
 *       failures as UNSTABLE so operators see the leak immediately.
 *
 *   B4  No post-cleanup verification. Here the helper probes describe-stacks for
 *       every stack listed in verifyStacks at the end and marks the build UNSTABLE
 *       if any is still present.
 *
 * The helper intentionally does NOT rethrow. Using unstable() lets subsequent
 * post-block statements (archiveArtifacts, notifications) still run.
 *
 * Supported step types:
 *   [type: 'cdc-k8s',     kubeContext: '...']
 *   [type: 'eks-k8s',     stackName: '...', eksClusterName: '...', kubeContext: '...']
 *   [type: 'cfn-destroy', stackName: '...', reason: '...']
 *   [type: 'cdk-destroy', dir: 'test', stage: '...', contextFile: '...']
 *   [type: 'revoke-sg',   sgId: '...', sourceSg: '...']
 *   [type: 'isolated-vpc', vpcId: '...', stage: '...']
 *   [type: 'kube-context', name: '...']
 *
 * Typical usage (eksCdcAossIntegPipeline — the canonical caoss/ceaoss leak):
 *
 *   finalCleanup(
 *       region: params.REGION,
 *       steps: [
 *           [type: 'cdc-k8s',     kubeContext: env.eksKubeContext],
 *           [type: 'eks-k8s',     stackName: env.STACK_NAME,
 *                                 eksClusterName: env.eksClusterName,
 *                                 kubeContext: env.eksKubeContext],
 *           // AOSS collection lives inside the MA VPC — MUST be deleted before MA
 *           [type: 'cfn-destroy', stackName: env.CLUSTER_STACK,
 *                                 reason: 'AOSS collection inside MA VPC'],
 *           [type: 'cfn-destroy', stackName: env.STACK_NAME,
 *                                 reason: 'MA stack owns the VPC'],
 *           [type: 'kube-context', name: env.eksKubeContext],
 *       ],
 *       verifyStacks: [env.STACK_NAME, env.CLUSTER_STACK],
 *   )
 */
def call(Map config) {
    def region = config.region ?: error("finalCleanup: 'region' is required")
    def steps = config.steps ?: []
    def verifyStacks = (config.verifyStacks ?: []).findAll { it }

    echo "FINAL_CLEANUP: starting ${steps.size()} step(s); will verify ${verifyStacks.size()} stack(s)"
    def stepFailures = []
    steps.eachWithIndex { step, i ->
        def label = stepLabel(step)
        echo "FINAL_CLEANUP (${i + 1}/${steps.size()}): ${label}"
        try {
            runStep(step, region)
            echo "FINAL_CLEANUP: ${label} — OK"
        } catch (err) {
            // Jenkins wraps sh failures and script errors in various Throwables;
            // log the message and the class for post-mortem traceability.
            def msg = (err.message ?: err.toString())
            echo "FINAL_CLEANUP: ${label} — FAILED (${err.getClass().simpleName}): ${msg}"
            stepFailures << label
        }
    }

    def leaked = verifyStacksGone(verifyStacks, region)
    reportOutcome(stepFailures, leaked)
}

// ---------- private helpers ----------

private String stepLabel(Map step) {
    def key = step.stackName ?: step.stage ?: step.kubeContext ?: step.name ?: step.vpcId ?: step.sgId
    return key ? "[${step.type}] ${key}" : "[${step.type}]"
}

private void runStep(Map step, String region) {
    switch (step.type) {
        case 'cdc-k8s':
            if (!step.kubeContext) { error("finalCleanup cdc-k8s: kubeContext is required") }
            cdcCleanupStep(kubeContext: step.kubeContext)
            break

        case 'eks-k8s':
            if (!step.stackName || !step.eksClusterName || !step.kubeContext) {
                error("finalCleanup eks-k8s: stackName, eksClusterName, kubeContext are required")
            }
            eksCleanupStep(
                stackName: step.stackName,
                eksClusterName: step.eksClusterName,
                kubeContext: step.kubeContext,
            )
            break

        case 'cfn-destroy':
            if (!step.stackName) { error("finalCleanup cfn-destroy: stackName is required") }
            destroyCfnStack(stackName: step.stackName, region: region, reason: step.reason)
            break

        case 'cdk-destroy':
            if (!step.stage || !step.contextFile) {
                error("finalCleanup cdk-destroy: stage and contextFile are required")
            }
            dir(step.dir ?: 'test') {
                // If the context file doesn't exist (deploy never ran, workspace pruned,
                // etc.) there's nothing to destroy via CDK — skip rather than surface a
                // spurious UNSTABLE that obscures real leaks.
                if (!fileExists(step.contextFile)) {
                    echo "FINAL_CLEANUP: cdk-destroy skipped — '${step.contextFile}' not present in '${step.dir ?: 'test'}' (deploy never ran)"
                    return
                }
                def rc = sh(returnStatus: true,
                            script: "./awsDeployCluster.sh --stage ${step.stage} --context-file ${step.contextFile} --destroy")
                if (rc != 0) { error("cdk destroy failed for stage '${step.stage}' (rc=${rc})") }
            }
            break

        case 'revoke-sg':
            if (!step.sgId || !step.sourceSg) {
                error("finalCleanup revoke-sg: sgId and sourceSg are required")
            }
            // Revoke is idempotent-ish — a missing rule returns non-zero which we treat as OK.
            def rc = sh(returnStatus: true,
                        script: """aws ec2 revoke-security-group-ingress \
                                     --group-id '${step.sgId}' --protocol -1 --port -1 \
                                     --source-group '${step.sourceSg}' --region '${region}' 2>/dev/null""")
            if (rc != 0) {
                echo "FINAL_CLEANUP: revoke-sg rc=${rc} (rule likely already absent — ignoring)"
            }
            break

        case 'isolated-vpc':
            if (!step.vpcId || !step.stage) {
                error("finalCleanup isolated-vpc: vpcId and stage are required")
            }
            cleanupIsolatedVpc(region: region, vpcId: step.vpcId, stage: step.stage)
            break

        case 'kube-context':
            if (!step.name) { error("finalCleanup kube-context: name is required") }
            // Non-fatal best-effort cleanup of the local kubeconfig entry.
            sh(returnStatus: true,
               script: """if command -v kubectl >/dev/null 2>&1; then
                            kubectl config delete-context '${step.name}' 2>/dev/null || true
                          fi""")
            break

        default:
            error("finalCleanup: unknown step type '${step.type}'")
    }
}

private void destroyCfnStack(Map cfg) {
    def stackName = cfg.stackName
    def region = cfg.region
    def reasonTag = cfg.reason ? " (${cfg.reason})" : ''
    echo "FINAL_CLEANUP: deleting CFN stack '${stackName}'${reasonTag}"

    // Short-circuit if the stack is already gone — lets reruns be idempotent.
    def existsRc = sh(returnStatus: true,
                      script: "aws cloudformation describe-stacks --stack-name '${stackName}' --region '${region}' >/dev/null 2>&1")
    if (existsRc != 0) {
        echo "FINAL_CLEANUP: stack '${stackName}' already absent, skipping"
        return
    }

    def deleteRc = sh(returnStatus: true,
                      script: "aws cloudformation delete-stack --stack-name '${stackName}' --region '${region}'")
    if (deleteRc != 0) {
        error("delete-stack returned rc=${deleteRc} for '${stackName}'")
    }

    // aws cloudformation wait caps internally at ~60 min. If we hit that OR the
    // stack goes DELETE_FAILED, surface it clearly.
    def waitRc = sh(returnStatus: true,
                    script: "aws cloudformation wait stack-delete-complete --stack-name '${stackName}' --region '${region}'")
    if (waitRc != 0) {
        def status = stackStatus(stackName, region)
        if (status == 'GONE' || status == 'DELETE_COMPLETE') {
            echo "FINAL_CLEANUP: wait rc=${waitRc} but stack is actually gone — OK"
            return
        }
        // Dump recent DELETE_FAILED events so the human reviewing the console has a starting point.
        sh(returnStatus: true,
           script: """aws cloudformation describe-stack-events --stack-name '${stackName}' --region '${region}' \
                        --max-items 30 \
                        --query 'StackEvents[?ResourceStatus==`DELETE_FAILED`].[LogicalResourceId,ResourceStatusReason]' \
                        --output table 2>/dev/null || true""")
        error("wait stack-delete-complete failed for '${stackName}' — current status=${status}")
    }
    echo "FINAL_CLEANUP: stack '${stackName}' deleted"
}

/** Returns the stack's CloudFormation status, or 'GONE' if the stack does not exist. */
private String stackStatus(String stackName, String region) {
    return sh(returnStdout: true,
              script: """aws cloudformation describe-stacks --stack-name '${stackName}' --region '${region}' \
                           --query 'Stacks[0].StackStatus' --output text 2>/dev/null || echo 'GONE'""").trim()
}

private List verifyStacksGone(List stackNames, String region) {
    def leaked = []
    stackNames.each { name ->
        def status = stackStatus(name, region)
        if (status != 'GONE' && status != 'DELETE_COMPLETE') {
            echo "FINAL_CLEANUP: VERIFY FAILED — stack '${name}' still present, status=${status}"
            leaked << "${name}=${status}"
        } else {
            echo "FINAL_CLEANUP: VERIFY OK — stack '${name}' is ${status}"
        }
    }
    return leaked
}

private void reportOutcome(List stepFailures, List leaked) {
    if (!stepFailures && !leaked) {
        echo "FINAL_CLEANUP: ALL CLEAR — every step succeeded and every verified stack is gone"
        return
    }
    def parts = []
    if (stepFailures) { parts << "cleanup step(s) failed: ${stepFailures.join(', ')}" }
    if (leaked)       { parts << "stack(s) still present: ${leaked.join(', ')}" }
    def msg = "EKS cleanup incomplete — ${parts.join(' | ')}"
    echo "FINAL_CLEANUP: ${msg}"
    // Mark UNSTABLE without throwing so archiveArtifacts and other post-block
    // statements after this call still run.
    unstable(msg)
}
