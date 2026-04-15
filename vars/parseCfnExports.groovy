/**
 * Parse the MigrationsExportString CFN output into a map of key-value pairs.
 *
 * The output format is: "export KEY1=val1;export KEY2=val2;..."
 *
 * Usage:
 *   def exports = parseCfnExports(stackName: 'my-stack', region: 'us-east-1')
 *   env.eksClusterName = exports['MIGRATIONS_EKS_CLUSTER_NAME']
 */
def call(Map config = [:]) {
    def stackName = config.stackName
    def region = config.region

    if (!stackName) { error("parseCfnExports: 'stackName' is required") }
    if (!region) { error("parseCfnExports: 'region' is required") }

    def rawOutput = sh(
        script: """
          aws cloudformation describe-stacks \
            --stack-name "${stackName}" \
            --query "Stacks[0].Outputs[?OutputKey=='MigrationsExportString'].OutputValue" \
            --output text \
            --region "${region}"
        """,
        returnStdout: true
    ).trim()
    if (!rawOutput) {
        error("Could not retrieve CloudFormation Output 'MigrationsExportString' from stack '${stackName}'")
    }
    return rawOutput.split(';')
            .collect { it.trim().replaceFirst(/^export\s+/, '') }
            .findAll { it.contains('=') }
            .collectEntries {
                def (key, value) = it.split('=', 2)
                [(key): value]
            }
}
