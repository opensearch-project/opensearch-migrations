/**
 * Resolve which `migration-assistant` CLI binary to run.
 *
 * Two modes:
 *   1. (default) source-checkout — use ./deployment/k8s/aws/cli/bin/migration-assistant
 *      from the cloned repo.
 *   2. useReleaseCli=true — download install.sh from the GitHub release for
 *      `version` (default 'latest') and install the CLI under
 *      $WORKSPACE/.migrate-cli-release/. Returns the installed binary's
 *      absolute path.
 *
 * The "release CLI" path lets a Jenkins job test the released artifact
 * surface end-to-end — the same path operators use via curl-pipe install
 * — instead of the source-checkout shells. It replaces the previous
 * USE_RELEASE_BOOTSTRAP mode that downloaded aws-bootstrap.sh from
 * the release; aws-bootstrap.sh no longer exists.
 *
 * Usage:
 *   def cliBin = resolveCli(useReleaseCli: params.USE_RELEASE_CLI, version: params.VERSION)
 *   sh "${cliBin} --non-interactive ..."
 *
 * Returns: an absolute path string suitable for `sh` interpolation.
 */
def call(Map config = [:]) {
    def useReleaseCli = config.containsKey('useReleaseCli') ? config.useReleaseCli : false
    def version = config.containsKey('version') ? config.version : 'latest'
    def repo = config.containsKey('repo') ? config.repo : 'opensearch-project/opensearch-migrations'

    if (!useReleaseCli) {
        // Source checkout. Path is relative to the workspace root, which
        // is what every pipeline's `sh` block runs from.
        return './deployment/k8s/aws/cli/bin/migration-assistant'
    }

    // Release CLI. install.sh is the canonical curl-pipe entry point;
    // running it with INSTALL_FROM_LOCAL unset + a workspace-private
    // MIGRATE_PREFIX + BIN_DIR keeps the install isolated to this build.
    //
    // We resolve to an absolute path so callers can `sh "${cliBin} ..."`
    // from any cwd without worrying about ./relative resolution.
    def installRoot = "${env.WORKSPACE}/.migrate-cli-release"
    def installPrefix = "${installRoot}/cli"
    def installBin = "${installRoot}/bin"
    def downloadUrl = (version == 'latest')
        ? "https://github.com/${repo}/releases/latest/download/install.sh"
        : "https://github.com/${repo}/releases/download/${version}/install.sh"

    sh """
        set -e
        mkdir -p "${installRoot}"
        curl -fsSL --max-time 60 -o "${installRoot}/install.sh" "${downloadUrl}"
        chmod +x "${installRoot}/install.sh"
        # Path mode (the install.sh default) installs the versioned tree
        # under MIGRATE_PREFIX and symlinks BIN_DIR/migration-assistant.
        # Both env vars are honored by install.sh; MIGRATE_VERSION pins the
        # tarball it downloads (default 'latest').
        MIGRATE_VERSION="${version}" \
        MIGRATE_PREFIX="${installPrefix}" \
        BIN_DIR="${installBin}" \
        bash "${installRoot}/install.sh"
    """

    // The branding.binaryName in the release manifest is "migration-assistant"
    // upstream; callers who flip this in a custom build would need to plumb
    // the same name here (we ALWAYS symlink as `migration-assistant` from
    // the source path so the contract is stable for CI).
    return "${installBin}/migration-assistant"
}
