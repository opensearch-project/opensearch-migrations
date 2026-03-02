def gitBranch = params.GIT_BRANCH ?: 'main'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/opensearch-project/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Allow job name overrides for matrix parent and child job routing
def jobNameOverride = params.JOB_NAME_OVERRIDE ?: ''
def childJobNameOverride = params.CHILD_JOB_NAME_OVERRIDE ?: ''
k8sMatrixTest(
        jobName: jobNameOverride ?: null,
        childJobName: childJobNameOverride ?: null
)
