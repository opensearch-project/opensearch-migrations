def gitBranch = params.GIT_BRANCH ?: 'main'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/opensearch-project/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Shared library function (location from root: vars/eksSolutionsCFNTest.groovy)
eksSolutionsCFNTest(
    vpcMode: 'import',
    defaultStage: 'eksimportvpc',
    defaultGitUrl: 'https://github.com/opensearch-project/opensearch-migrations.git',
    defaultGitBranch: 'main'
)
