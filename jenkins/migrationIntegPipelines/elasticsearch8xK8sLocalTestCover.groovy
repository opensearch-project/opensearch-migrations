def gitBranch = params.GIT_BRANCH ?: 'es-8-k8s-pipeline'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/lewijacn/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Shared library function (location from root: vars/elasticsearch8xK8sLocalTest.groovy)
elasticsearch8xK8sLocalTest(
    gitUrl: gitUrl,
    gitBranch: gitBranch
)
