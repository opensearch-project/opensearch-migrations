// Shared bootstrap resolution for EKS pipelines.
// Returns a map with 'script' (path to bootstrap) and 'flags' (joined string of args).
//
// Three modes:
//   1. useReleaseBootstrap=true: downloads the released aws-bootstrap.sh from
//      the GitHub release for `version` and runs it. Legacy production path —
//      this is what operators have always used; kept as a stable fallback
//      while the TUI bakes in.
//   2. useReleaseBootstrap=false (DEFAULT for source builds): runs the new
//      migration-assistant TUI CLI directly out of the source checkout. This
//      is the path the team is actively baking through real CI runs to gain
//      confidence before promoting it to the released artifact. Operators on
//      the released path are NOT affected — they still get aws-bootstrap.sh.
//   3. build=true: passes --build to whichever path is selected (both
//      aws-bootstrap.sh and the TUI CLI accept --build to mean "build all
//      artifacts from source"). With release-bootstrap=true + build=true the
//      released script handles the build itself; with release-bootstrap=false
//      the TUI wraps :buildImages:buildImagesToRegistry.
//
// The flag set is intentionally a SUBSET that BOTH backends accept identically
// (--build, --version, --skip-test-images, --use-general-node-pool, --base-dir).
// Any flags specific to one backend stay out of resolveBootstrap and live in
// bootstrapMA's own arg list.
def call(Map config = [:]) {
    def bootstrapScript
    def version = config.containsKey('version') ? config.version : 'latest'
    def useRelease = config.containsKey('useReleaseBootstrap') ? config.useReleaseBootstrap : false
    def build = config.containsKey('build') ? config.build : false
    def skipTestImages = config.containsKey('skipTestImages') ? config.skipTestImages : false
    def useGeneralNodePool = config.containsKey('useGeneralNodePool') ? config.useGeneralNodePool : false

    if (useRelease) {
        // Download the self-contained aws-bootstrap.sh from the GitHub release.
        // Stable production path. The script downloads all artifacts (CFN
        // templates, images, chart) on its own, so --build and --base-dir
        // aren't passed below.
        def downloadUrl = version == 'latest'
            ? "https://github.com/opensearch-project/opensearch-migrations/releases/latest/download/aws-bootstrap.sh"
            : "https://github.com/opensearch-project/opensearch-migrations/releases/download/${version}/aws-bootstrap.sh"
        sh """curl -sL -o /tmp/aws-bootstrap.sh "${downloadUrl}" && chmod +x /tmp/aws-bootstrap.sh"""
        bootstrapScript = "/tmp/aws-bootstrap.sh"
    } else {
        // Source-checkout default: run the new migration-assistant TUI CLI.
        // The CLI is deliberately wired here (not behind another opt-in flag)
        // so every Jenkins run on a non-release branch exercises the new
        // path. This is the bake mechanism — until the TUI ships as the
        // production deploy script, we get coverage from CI runs first.
        //
        // The CLI accepts the same flags (--build, --version,
        // --skip-test-images, --use-general-node-pool, --base-dir) so no
        // flag translation is needed in the caller (bootstrapMA). The
        // CLI bin sits at deployment/k8s/aws/cli/bin/migration-assistant
        // in the source tree.
        bootstrapScript = "./deployment/k8s/aws/cli/bin/migration-assistant"
        // Some pipelines run the script from a different cwd; ensure
        // the bin is executable in case the checkout came from a tarball
        // archive that lost +x bits.
        sh """chmod +x ${bootstrapScript} || true"""
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
