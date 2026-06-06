/**
 * Resolve which `migration-assistant` CLI binary to run.
 *
 * The CLI is written in Amber (amber-lang.com) — typed source under
 * deployment/k8s/aws/cli/src/*.ab that COMPILES to a single self-contained
 * Bash script. There is no committed bash binary; both modes below produce a
 * compiled, runnable entrypoint.
 *
 * Two modes:
 *   1. (default) source-checkout — compile cli/src/main.ab with the Amber
 *      toolchain (target bash-3.2) into a workspace-private bin and return its
 *      absolute path. Requires `amber` on the agent PATH (or AMBER env / the
 *      `amber` param). Compiled once per build; the output is plain Bash so the
 *      deploy `sh` steps need no toolchain.
 *   2. useReleaseCli=true — download install.sh from the GitHub release for
 *      `version` (default 'latest') and install the CLI under
 *      $WORKSPACE/.migrate-cli-release/. The release artifact is already
 *      compiled Bash, so this path has no Amber dependency.
 *
 * The "release CLI" path lets a Jenkins job test the released artifact surface
 * end-to-end — the same path operators use via curl-pipe install — instead of
 * the source-compiled entrypoint. It replaces the previous USE_RELEASE_BOOTSTRAP
 * mode that downloaded aws-bootstrap.sh from the release; aws-bootstrap.sh no
 * longer exists.
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
    def amber = config.containsKey('amber') ? config.amber : (env.AMBER ?: 'amber')

    if (!useReleaseCli) {
        // Source checkout. Compile the Amber entrypoint to a Bash script via
        // Gradle, which PROVISIONS the Amber toolchain itself (downloads the
        // pinned release binary and caches it under build/amber-toolchain — like
        // the Java/Corretto toolchain) so the agent needs no host `amber`. The
        // compiled script inlines every imported module, so that single file IS
        // the CLI — no lib/. We return its absolute path.
        //
        // A pre-installed toolchain can still be pinned via AMBER=/path (Gradle's
        // provisionAmber honors it). gradlew runs from the workspace root.
        def amberArg = (env.AMBER?.trim()) ? "-Pamber='${env.AMBER}'" : ''
        def compiled = "${env.WORKSPACE}/deployment/k8s/aws/build/migrate-cli-compiled/migration-assistant"
        sh """
            set -e
            ./gradlew :deployment:k8s:aws:compileMigrationAssistantCli ${amberArg}
            test -x "${compiled}"
        """
        return compiled
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
