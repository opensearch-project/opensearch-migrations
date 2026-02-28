def gitBranch = params.GIT_BRANCH ?: 'main'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/opensearch-project/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Allow child job name override for matrix test routing
def childJobNameOverride = params.CHILD_JOB_NAME_OVERRIDE ?: ''
k8sMatrixTest(childJobName: childJobNameOverride ?: null)