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
        // Source checkout. Compile the Amber entrypoint to a workspace-private
        // bin (plain Bash, target 3.2). The amber-compiled script inlines every
        // imported module, so the single output file IS the CLI — no lib/.
        def cliSrc = 'deployment/k8s/aws/cli'
        def outBin = "${env.WORKSPACE}/.migrate-cli-src/migration-assistant"
        sh """
            set -e
            if ! command -v '${amber}' >/dev/null 2>&1; then
              echo 'resolveCli: Amber compiler not found on PATH (tried ${amber}).' >&2
              echo '  Install it on the agent: brew install amber-lang/amber/amber-lang' >&2
              echo '  or pin AMBER=/path/to/amber. The CLI source is Amber (.ab); the' >&2
              echo '  source-checkout mode compiles it to Bash here.' >&2
              exit 1
            fi
            mkdir -p "${env.WORKSPACE}/.migrate-cli-src"
            ( cd "${cliSrc}" && '${amber}' build --target bash-3.2 src/main.ab "${outBin}" )
            chmod +x "${outBin}"
        """
        return outBin
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
