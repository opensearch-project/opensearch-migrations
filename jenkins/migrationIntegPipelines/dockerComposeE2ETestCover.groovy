def gitBranch = params.GIT_BRANCH ?: 'main'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/opensearch-project/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Use JOB_NAME_OVERRIDE if set, otherwise derive from the Jenkins job name.
// This ensures the GenericTrigger regex stays in sync with the actual job name
// (e.g., main-* vs pr-*) across runs.
def jobNameOverride = params.JOB_NAME_OVERRIDE ?: env.JOB_BASE_NAME ?: ''
dockerComposeE2ETest(jobName: jobNameOverride ?: null)
