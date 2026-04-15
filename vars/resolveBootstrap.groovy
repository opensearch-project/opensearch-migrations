// Shared bootstrap resolution for EKS pipelines.
// Returns a map with 'script' (path to bootstrap) and 'flags' (joined string of args).
//
// Three modes (matching aws-bootstrap.sh):
//   1. build=true (default for source checkout): --build (builds everything from source)
//   2. useReleaseBootstrap=true: downloads release bootstrap + artifacts for --version
//   3. Explicit version without build: downloads artifacts for that version
def call(Map config = [:]) {
    def bootstrapScript
    def version = config.containsKey('version') ? config.version : 'latest'
    def useRelease = config.containsKey('useReleaseBootstrap') ? config.useReleaseBootstrap : false
    def build = config.containsKey('build') ? config.build : false
    def skipTestImages = config.containsKey('skipTestImages') ? config.skipTestImages : false
    def useGeneralNodePool = config.containsKey('useGeneralNodePool') ? config.useGeneralNodePool : false

    if (useRelease) {
        // Download the self-contained aws-bootstrap.sh from the GitHub release.
        // This script downloads all artifacts (CFN templates, images, chart),
        // so --build and --base-dir are not needed.
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
    if (build) {
        flags << '--build'
        if (skipTestImages) flags << '--skip-test-images'
        // --build and --version are mutually exclusive; no --version needed
    } else {
        flags << "--version ${version}"
    }
    if (useGeneralNodePool) flags << '--use-general-node-pool'
    if (!useRelease) flags << "--base-dir \"\$(pwd)\""

    return [script: bootstrapScript, flags: flags.join(' ')]
}
