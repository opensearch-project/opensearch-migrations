/**
 * Run the migration-assistant CLI to deploy the Migration Assistant CFN
 * stack. State and the run log land under
 * ./migration-assistant-workspace/<stage>/ in the Jenkins workspace.
 *
 * The CLI source is Amber (deployment/k8s/aws/cli/src/*.ab). By default
 * resolveCli compiles src/main.ab to a Bash entrypoint from the source
 * checkout. Pass useReleaseCli=true (typically wired to
 * params.USE_RELEASE_CLI) to download install.sh from the GitHub release for
 * `version` and run the released CLI instead — this is the path operators use via
 * curl-pipe install. Replaces the old USE_RELEASE_BOOTSTRAP toggle
 * (which downloaded aws-bootstrap.sh; that script no longer exists).
 *
 * Sets these env vars from CFN outputs after deploy:
 *   - env.MA_STACK_NAME
 *   - env.registryEndpoint
 *   - env.eksClusterName
 *   - env.eksKubeContext
 *   - env.clusterSecurityGroup (if present)
 *   - env.maVpcId (if present)
 *
 * Usage:
 *   bootstrapMA(
 *       stackName:             "...",
 *       stage:                 maStageName,
 *       region:                params.REGION,
 *       eksAccessPrincipalArn: "arn:aws:iam::${accountId}:role/JenkinsDeploymentRole",
 *       kubectlContext:        "my-custom-context",
 *       build:                 params.BUILD,
 *       skipTestImages:        true,
 *       useGeneralNodePool:    true,
 *       version:               params.VERSION,         // ignored when build=true
 *       useReleaseCli:         params.USE_RELEASE_CLI, // false → source checkout (default)
 *       // Optional — import VPC mode:
 *       vpcId:               "vpc-xxx",
 *       subnetIds:           "subnet-a,subnet-b",
 *       createVpcEndpoints:  "s3,ecr,ecrDocker",
 *       // Optional:
 *       maImagesSource: "ecr-registry-url",
 *       tlsMode:        "self-signed",
 *       pcaArn:         "arn:aws:acm-pca:..."
 *   )
 */
def call(Map config = [:]) {
    def stackName = config.stackName
    def stage = config.stage
    def region = config.region
    def eksAccessPrincipalArn = config.eksAccessPrincipalArn
    def kubectlContext = config.kubectlContext

    if (!stackName) { error("bootstrapMA: 'stackName' is required") }
    if (!stage) { error("bootstrapMA: 'stage' is required") }
    if (!region) { error("bootstrapMA: 'region' is required") }
    if (!eksAccessPrincipalArn) { error("bootstrapMA: 'eksAccessPrincipalArn' is required") }
    if (!kubectlContext) { error("bootstrapMA: 'kubectlContext' is required") }

    env.MA_STACK_NAME = stackName
    env.eksKubeContext = kubectlContext

    def flags = []

    // VPC mode.
    if (config.vpcId) {
        flags << '--deploy-import-vpc-cfn'
        flags << "--vpc-id ${config.vpcId}"
        flags << "--subnet-ids ${config.subnetIds}"
        if (config.createVpcEndpoints) {
            flags << "--create-vpc-endpoints ${config.createVpcEndpoints}"
        }
    } else {
        flags << '--deploy-create-vpc-cfn'
    }

    // Build-from-source vs released artifacts.
    if (config.build) {
        flags << '--build'
        if (config.skipTestImages) flags << '--skip-test-images'
        // base-dir defaults to the repo root the CLI was invoked out of;
        // pwd is that root in the Jenkins workspace.
    } else if (config.version && config.version != 'latest') {
        flags << "--version ${config.version}"
    }

    if (config.useGeneralNodePool)        flags << '--use-general-node-pool'
    if (config.disableGeneralPurposePool) flags << '--disable-general-purpose-pool'

    if (config.tlsMode)        flags << "--tls-mode ${config.tlsMode}"
    if (config.pcaArn)         flags << "--pca-arn ${config.pcaArn}"
    if (config.maImagesSource) flags << "--ma-images-source ${config.maImagesSource}"

    def flagStr = flags.join(' ')
    def workspaceDir = "${env.WORKSPACE}/migration-assistant-workspace"

    // Resolve which CLI binary to run: source-checkout (default) or the
    // released CLI installed via install.sh. See vars/resolveCli.groovy.
    def cliBin = resolveCli(
        useReleaseCli: config.useReleaseCli ?: false,
        version:       config.version ?: 'latest'
    )

    // Run the CLI. Stream stdout+stderr live (each line tagged with the
    // wall-clock time so a hang is obvious in the Jenkins console). On
    // non-zero exit, dump the full migrate.log so the failure root cause
    // is in the same console output without a Jenkins archive round trip.
    sh """
        set +e
        export MIGRATE_HOME="${workspaceDir}"
        ${cliBin} \
          --non-interactive \
          --verbose \
          --stack-name "${stackName}" \
          --stage "${stage}" \
          --eks-access-principal-arn "${eksAccessPrincipalArn}" \
          ${flagStr} \
          --skip-console-exec \
          --skip-setting-k8s-context \
          --kubectl-context "${kubectlContext}" \
          --region ${region} \
          2>&1 | { set +x; while IFS= read -r line; do printf '%s | %s\\n' "\$(date '+%H:%M:%S')" "\$line"; done; }
        rc=\${PIPESTATUS[0]}
        set -e
        if [ \$rc -ne 0 ]; then
          echo "=== migration-assistant FAILED (rc=\$rc) — full migrate.log ==="
          cat "${workspaceDir}/${stage}/log/migrate.log" 2>&1 || \
            echo "(migrate.log not found at ${workspaceDir}/${stage}/log/migrate.log)"
          echo "=== end migrate.log ==="
        fi
        exit \$rc
    """

    // Copy the full migrate.log into the workspace and archive it. Runs
    // both on success and failure so the log is always captureable.
    sh """
        mkdir -p migrate-cli-logs/${stage}
        cp -R "${workspaceDir}/${stage}/log/." migrate-cli-logs/${stage}/ 2>/dev/null || true
    """
    archiveArtifacts(
        artifacts: "migrate-cli-logs/**",
        allowEmptyArchive: true,
        fingerprint: false
    )

    def exportsMap = parseCfnExports(stackName: stackName, region: region)
    env.registryEndpoint = exportsMap['MIGRATIONS_ECR_REGISTRY']
    env.eksClusterName = exportsMap['MIGRATIONS_EKS_CLUSTER_NAME']
    env.clusterSecurityGroup = exportsMap['EKS_CLUSTER_SECURITY_GROUP'] ?: ''
    env.maVpcId = exportsMap['VPC_ID'] ?: ''
}
