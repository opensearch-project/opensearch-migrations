def gitBranch = params.GIT_BRANCH ?: 'jenkins-pipeline-eks-cfn-import-vpc'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/jugal-chauhan/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Shared library function (location from root: vars/eksSolutionsCFNTest.groovy)
eksSolutionsCFNTest(
    vpcMode: 'import',
    defaultStage: 'eks-import-vpc',
    defaultGitUrl: 'https://github.com/jugal-chauhan/opensearch-migrations.git',
    defaultGitBranch: 'jenkins-pipeline-eks-cfn-import-vpc'
)
