def gitBranch = params.GIT_BRANCH ?: 'main'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/opensearch-project/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Shared library function (location from root: vars/eksSolutionsCFNTest.groovy)
def jobNameOverride = params.JOB_NAME_OVERRIDE ?: ''
eksSolutionsCFNTest(
    vpcMode: 'create',
    defaultStage: 'ekscreatevpc',
    defaultGitUrl: 'https://github.com/opensearch-project/opensearch-migrations.git',
    defaultGitBranch: 'main',
    jobName: jobNameOverride ?: null
)
