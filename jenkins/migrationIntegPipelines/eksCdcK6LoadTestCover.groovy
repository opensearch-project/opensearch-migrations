def gitBranch = params.GIT_BRANCH ?: 'main'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/opensearch-project/opensearch-migrations.git'

// For release canary jobs (release-*), resolve the latest release tag
// and load the library from that version so pipeline code matches the release.
if (env.JOB_BASE_NAME?.startsWith('release-')) {
    node('Jenkins-Default-Agent-X64-C5xlarge-Single-Host') {
        gitBranch = sh(
            script: "git ls-remote --tags --sort=-v:refname '${gitUrl}' | grep -oP 'refs/tags/\\K[0-9]+\\.[0-9]+\\.[0-9]+\$' | head -1",
            returnStdout: true
        ).trim()
        echo "Release canary: resolved latest tag ${gitBranch}"
    }
}

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Use JOB_NAME_OVERRIDE if set, otherwise derive from the Jenkins job name.
// This ensures the GenericTrigger regex stays in sync with the actual job name
// (e.g., main-* vs pr-*) across runs.
def jobNameOverride = params.JOB_NAME_OVERRIDE ?: env.JOB_BASE_NAME ?: ''

// Test 0080 drives the CDC pipeline with a k6 load test instead of generate-data. The test runner
// installs the standalone k6LoadTest chart on its own for any 008x ID, so this job differs from
// eksCdcFullE2eIntegTestCover only in its test IDs and its own stage prefix (so it can run beside
// the plain CDC job without sharing a stack).
eksCdcIntegPipeline(jobName: jobNameOverride ?: null, gitBranchDefault: gitBranch, defaultTestIds: '0080', defaultStageId: 'esosk6')
