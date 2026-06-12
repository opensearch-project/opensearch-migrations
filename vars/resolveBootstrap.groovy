// Shared bootstrap resolution for EKS pipelines.
// Returns a map with 'script' (path to the migration-assistant CLI) and
// 'flags' (joined string of args).
//
// The deploy backend is the migration-assistant CLI — a Rust binary rooted at
// deployment/k8s/aws/cli/. Two modes:
//
//   1. useReleaseBootstrap=false (DEFAULT for source builds): run the CLI
//      straight out of the source checkout via the wrapper at
//      deployment/k8s/aws/cli/bin/migration-assistant. The wrapper builds the
//      release binary on first use (cargo build --release) and caches it, so
//      every Jenkins run on a non-release branch exercises the from-source CLI.
//   2. useReleaseBootstrap=true: install the RELEASED CLI via install.sh from
//      the GitHub release for `version` (delegated to resolveCli), which ships
//      a prebuilt binary so no toolchain is needed. This tests the released
//      artifact surface end-to-end — the same path operators use.
//
//   build=true passes --build to either path (the CLI wraps
//   :buildImages:buildImagesToRegistry to build all artifacts from source).
//
// The flag set is intentionally a SUBSET the CLI accepts identically in both
// modes (--build, --version, --skip-test-images, --use-general-node-pool,
// --base-dir). Any backend-specific flags live in bootstrapMA's own arg list.
def call(Map config = [:]) {
    def version = config.containsKey('version') ? config.version : 'latest'
    def useRelease = config.containsKey('useReleaseBootstrap') ? config.useReleaseBootstrap : false
    def build = config.containsKey('build') ? config.build : false
    def skipTestImages = config.containsKey('skipTestImages') ? config.skipTestImages : false
    def useGeneralNodePool = config.containsKey('useGeneralNodePool') ? config.useGeneralNodePool : false

    // Resolve which migration-assistant binary to run. resolveCli handles both
    // the source-checkout wrapper (default) and the install.sh release path.
    def bootstrapScript = resolveCli(useReleaseCli: useRelease, version: version)
    if (!useRelease) {
        // The source path is a wrapper script; ensure +x in case the checkout
        // came from an archive that lost the bit.
        sh """chmod +x ${bootstrapScript} || true"""
    }

    def flags = []
    if (build) {
        flags << '--build'
        if (skipTestImages) flags << '--skip-test-images'
        // --build and --version are mutually exclusive; no --version needed.
    } else {
        flags << "--version ${version}"
    }
    if (useGeneralNodePool) flags << '--use-general-node-pool'
    // --base-dir lets the CLI find the repo root for --build; only meaningful
    // for the source-checkout path (the released CLI builds from its own tree).
    if (!useRelease) flags << "--base-dir \"\$(pwd)\""

    return [script: bootstrapScript, flags: flags.join(' ')]
}
