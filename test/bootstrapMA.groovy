// Run from the repository root: groovy test/bootstrapMA.groovy
// No Jenkins, AWS, or Kubernetes calls are made.

def verifyBootstrap = { boolean bootstrapFails, boolean exportsFail ->
    def events = []
    def env = [:]
    def bootstrapFailure = new RuntimeException('BuildKit did not become ready')
    def exportsFailure = new RuntimeException('Stack has no outputs')
    def binding = new Binding([
        env: env,
        sh: { String command ->
            events << 'bootstrap'
            if (bootstrapFails) { throw bootstrapFailure }
        },
        parseCfnExports: { Map config ->
            assert config == [stackName: 'ma-test', region: 'us-east-1']
            events << 'exports'
            if (exportsFail) { throw exportsFailure }
            [
                MIGRATIONS_ECR_REGISTRY: 'test-registry',
                MIGRATIONS_EKS_CLUSTER_NAME: 'test-cluster',
                EKS_CLUSTER_SECURITY_GROUP: 'sg-test',
                VPC_ID: 'vpc-test',
            ]
        },
        echo: { String message -> events << message },
        error: { String message -> throw new RuntimeException(message) },
    ])
    def bootstrap = new GroovyShell(binding).parse(new File('vars/bootstrapMA.groovy'))
    Exception thrown = null
    try {
        bootstrap.call(
            stackName: 'ma-test', stage: 'test', region: 'us-east-1',
            bootstrap: [script: './aws-bootstrap.sh', flags: '--build'],
            eksAccessPrincipalArn: 'arn:aws:iam::123456789012:role/Test',
            kubectlContext: 'migration-eks-test',
        )
    } catch (Exception failure) {
        thrown = failure
    }
    assert events.take(2) == ['bootstrap', 'exports']
    assert thrown.is(bootstrapFails ? bootstrapFailure : exportsFail ? exportsFailure : null)
    assert env.MA_STACK_NAME == 'ma-test'
    assert env.eksKubeContext == 'migration-eks-test'
    if (!exportsFail) {
        assert env.eksClusterName == 'test-cluster'
        assert env.registryEndpoint == 'test-registry'
        assert env.clusterSecurityGroup == 'sg-test'
        assert env.maVpcId == 'vpc-test'

        // Prove that recovered metadata enables the existing cleanup steps,
        // including when image building fails after CloudFormation succeeds.
        def steps = []
        def cleanupBinding = new Binding([
            env: env,
            params: [:],
            timeout: { Map config, Closure body -> body() },
            script: { Closure body -> body() },
            withMigrationsTestAccount: { Map config, Closure body -> body('123456789012') },
            finalCleanup: { Map config -> steps.addAll(config.steps) },
            sh: { String command -> },
        ])
        def cleanup = new GroovyShell(cleanupBinding).parse(new File('vars/eksPostCleanup.groovy'))
        cleanup.call(
            maStackName: env.MA_STACK_NAME, region: 'us-east-1',
            kubeContext: env.eksKubeContext, eksClusterName: env.eksClusterName,
            archiveLogs: false,
        )
        assert steps*.type == ['ma-k8s', 'eks-k8s', 'cfn-destroy']
        assert steps[1].eksClusterName == 'test-cluster'
    } else {
        assert !env.eksClusterName
    }
    if (bootstrapFails && exportsFail) {
        assert events.last().contains('Could not recover stack exports')
    }
}

verifyBootstrap(false, false)
verifyBootstrap(true, false)
verifyBootstrap(true, true)
verifyBootstrap(false, true)
println 'PASS: bootstrap exports enable cleanup after failure without hiding the original error'
