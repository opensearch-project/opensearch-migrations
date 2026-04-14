/**
 * Deploy the Migration Assistant CFN stack (Create-VPC mode) and capture exports.
 *
 * Runs the bootstrap script with --deploy-create-vpc-cfn, then parses the
 * MigrationsExportString output into env vars.
 *
 * Sets these env vars:
 *   - env.MA_STACK_NAME
 *   - env.registryEndpoint
 *   - env.eksClusterName
 *   - env.eksKubeContext
 *   - env.clusterSecurityGroup (if present)
 *   - env.maVpcId (if present)
 *
 * Usage:
 *   bootstrapMA(
 *       stackName: "Migration-Assistant-Infra-Create-VPC-eks-${stage}-${region}",
 *       stage: maStageName,
 *       region: params.REGION,
 *       bootstrap: bootstrap,           // from resolveBootstrap()
 *       eksAccessPrincipalArn: "arn:aws:iam::${accountId}:role/JenkinsDeploymentRole",
 *       kubectlContext: "my-custom-context"
 *   )
 */
def call(Map config = [:]) {
    def stackName = config.stackName
    def stage = config.stage
    def region = config.region
    def bootstrap = config.bootstrap
    def eksAccessPrincipalArn = config.eksAccessPrincipalArn
    def kubectlContext = config.kubectlContext

    if (!stackName) { error("bootstrapMA: 'stackName' is required") }
    if (!stage) { error("bootstrapMA: 'stage' is required") }
    if (!region) { error("bootstrapMA: 'region' is required") }
    if (!bootstrap) { error("bootstrapMA: 'bootstrap' is required") }
    if (!eksAccessPrincipalArn) { error("bootstrapMA: 'eksAccessPrincipalArn' is required") }
    if (!kubectlContext) { error("bootstrapMA: 'kubectlContext' is required") }

    env.MA_STACK_NAME = stackName
    env.eksKubeContext = kubectlContext

    sh """
        ${bootstrap.script} \
          --deploy-create-vpc-cfn \
          --stack-name "${stackName}" \
          --stage "${stage}" \
          --eks-access-principal-arn "${eksAccessPrincipalArn}" \
          ${bootstrap.flags} \
          --skip-console-exec \
          --skip-setting-k8s-context \
          --kubectl-context "${kubectlContext}" \
          --region ${region} \
          2>&1 | { set +x; while IFS= read -r line; do printf '%s | %s\\n' "\$(date '+%H:%M:%S')" "\$line"; done; }; exit \${PIPESTATUS[0]}
    """

    def exportsMap = parseCfnExports(stackName: stackName, region: region)
    env.registryEndpoint = exportsMap['MIGRATIONS_ECR_REGISTRY']
    env.eksClusterName = exportsMap['MIGRATIONS_EKS_CLUSTER_NAME']
    env.clusterSecurityGroup = exportsMap['EKS_CLUSTER_SECURITY_GROUP'] ?: ''
    env.maVpcId = exportsMap['VPC_ID'] ?: ''
}
