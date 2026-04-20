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

// Shared library function (location from root: vars/eksSolutionsCFNTest.groovy)
def jobNameOverride = params.JOB_NAME_OVERRIDE ?: env.JOB_BASE_NAME ?: ''
eksSolutionsCFNTest(defaultGitBranch: gitBranch, 
    vpcMode: 'create',
    defaultStage: 'ekscvpc',
    defaultGitUrl: 'https://github.com/opensearch-project/opensearch-migrations.git',
    jobName: jobNameOverride ?: null
)
