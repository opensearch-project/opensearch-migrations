def gitBranch = params.GIT_BRANCH ?: 'jenkins-pipeline-eks-large-migration'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/jugal-chauhan/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Shared library function (location from root: vars/eksBYOSIntegPipeline.groovy)
eksBYOSIntegPipeline()
