def gitBranch = params.GIT_BRANCH ?: 'test-k8s-large-snapshot'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/jugal-chauhan/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Shared library function (location from root: vars/new_k8slargesnapshotTest.groovy)
new_k8slargesnapshotTest()
