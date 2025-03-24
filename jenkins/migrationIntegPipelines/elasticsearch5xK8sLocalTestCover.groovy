def gitBranch = params.GIT_BRANCH ?: 'add-k8s-jenkins-pipeline'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/lewijacn/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Shared library function (location from root: vars/k8sLocalDeployment.groovy)
k8sLocalDeployment()
