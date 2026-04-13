// Shared bootstrap resolution for EKS pipelines.
// Returns a map with 'script' (path to bootstrap) and 'flags' (joined string of args).
def call(Map config = [:]) {
    def bootstrapScript
    def version = config.containsKey('version') ? config.version : 'latest'
    def useRelease = config.containsKey('useReleaseBootstrap') ? config.useReleaseBootstrap : false
    def buildImages = config.containsKey('buildImages') ? config.buildImages : false
    def buildChart = config.containsKey('buildChartAndDashboards') ? config.buildChartAndDashboards : false
    def skipTestImages = config.containsKey('skipTestImages') ? config.skipTestImages : false

    if (useRelease) {
        // Download the self-contained aws-bootstrap.sh from the GitHub release.
        // This script downloads all artifacts (CFN templates, images, chart),
        // so --build-cfn and --base-dir are not needed.
        def downloadUrl = version == 'latest'
            ? "https://github.com/opensearch-project/opensearch-migrations/releases/latest/download/aws-bootstrap.sh"
            : "https://github.com/opensearch-project/opensearch-migrations/releases/download/${version}/aws-bootstrap.sh"
        sh """curl -sL -o /tmp/aws-bootstrap.sh "${downloadUrl}" && chmod +x /tmp/aws-bootstrap.sh"""
        bootstrapScript = "/tmp/aws-bootstrap.sh"
    } else {
        sh "./deployment/k8s/aws/assemble-bootstrap.sh"
        bootstrapScript = "./deployment/k8s/aws/dist/aws-bootstrap.sh"
    }

    def flags = []
    if (!useRelease) flags << '--build-cfn'
    if (buildImages) {
        flags << '--build-images'
        if (skipTestImages) flags << '--skip-test-images'
    }
    if (buildChart) flags << '--build-chart-and-dashboards'
    if (!useRelease) flags << "--base-dir \"\$(pwd)\""
    flags << "--version ${version}"

    return [script: bootstrapScript, flags: flags.join(' ')]
}
