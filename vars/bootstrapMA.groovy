/**
 * Deploy the Migration Assistant CFN stack and capture exports.
 *
 * Supports two VPC modes:
 *   - Create VPC (default): --deploy-create-vpc-cfn
 *   - Import VPC: --deploy-import-vpc-cfn (requires vpcId, subnetIds)
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
 *       stackName: "...",
 *       stage: maStageName,
 *       region: params.REGION,
 *       bootstrap: bootstrap,           // from resolveBootstrap()
 *       eksAccessPrincipalArn: "arn:aws:iam::${accountId}:role/JenkinsDeploymentRole",
 *       kubectlContext: "my-custom-context",
 *       // Optional — import VPC mode:
 *       vpcId: "vpc-xxx",
 *       subnetIds: "subnet-a,subnet-b",
 *       createVpcEndpoints: true,
 *       // Optional — mirror images from another ECR:
 *       maImagesSource: "ecr-registry-url",
 *       // Optional — tag everything the deployment creates, including the EC2 instances,
 *       // EBS volumes and load balancers EKS Auto Mode provisions later. Mutually
 *       // exclusive with resolveBootstrap(useGeneralNodePool: true).
 *       resourceTags: "Key=Value,Key2=Value2",
 *       // Optional, TEST ONLY — also DENY the cluster role from creating anything untagged.
 *       enforceTagsOnCreateForTests: true
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

    def tlsMode = config.tlsMode
    def tlsFlag = tlsMode ? "--tls-mode ${tlsMode}" : ''

    // Determine VPC mode
    def vpcId = config.vpcId
    def subnetIds = config.subnetIds
    def deployFlag = vpcId ? '--deploy-import-vpc-cfn' : '--deploy-create-vpc-cfn'
    def vpcFlags = vpcId ? "--vpc-id ${vpcId} --subnet-ids ${subnetIds}" : ''
    def endpointFlag = config.createVpcEndpoints ? '--create-vpc-endpoints' : ''
    def imageSourceFlag = config.maImagesSource ? "--ma-images-source ${config.maImagesSource}" : ''

    // Deployer tags. Passing these makes aws-bootstrap.sh set CloudFormation stack tags AND stand
    // up a tagged Auto Mode NodeClass, so it is incompatible with resolveBootstrap's
    // useGeneralNodePool (which selects the built-in pool that --tags has to delete).
    def resourceTagsFlag = config.resourceTags ? "--tags '${config.resourceTags}'" : ''
    // TEST ONLY: turns the tag requirement into a hard IAM Deny on the cluster role, so an untagged
    // create fails at the moment it happens -- the same way a deployer SCP that requires tags on
    // create behaves. Only meaningful alongside resourceTags.
    def enforceTagsFlag = config.enforceTagsOnCreateForTests ? '--enforce-tags-on-create-for-tests' : ''

    sh """
        ${bootstrap.script} \
          ${deployFlag} \
          --stack-name "${stackName}" \
          --stage "${stage}" \
          --eks-access-principal-arn "${eksAccessPrincipalArn}" \
          ${bootstrap.flags} \
          ${tlsFlag} \
          ${vpcFlags} \
          ${endpointFlag} \
          ${imageSourceFlag} \
          ${resourceTagsFlag} \
          ${enforceTagsFlag} \
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
