def gitBranch = params.GIT_BRANCH ?: 'main'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/opensearch-project/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Allow job name override for webhook routing. CHILD_JOB_NAME_OVERRIDE is still
// read for compatibility with existing Jenkins job configs, but k8sMatrixTest
// now routes to per-source child jobs itself.
def jobNameOverride = params.JOB_NAME_OVERRIDE ?: env.JOB_BASE_NAME ?: ''
def childJobNameOverride = params.CHILD_JOB_NAME_OVERRIDE ?: ''
k8sMatrixTest(
        jobName: jobNameOverride ?: null,
        childJobName: childJobNameOverride ?: null
)
