def gitBranch = params.GIT_BRANCH ?: 'initial-eks-cdc-test'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/jugal-chauhan/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Using JOB_NAME_OVERRIDE ensures the GenericTrigger regex stays in sync with the actual job name
def jobNameOverride = params.JOB_NAME_OVERRIDE ?: env.JOB_BASE_NAME ?: ''
eksCdcIntegPipeline(jobName: jobNameOverride ?: null)
