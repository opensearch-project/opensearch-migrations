def gitBranch = params.GIT_BRANCH ?: 'main'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/opensearch-project/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

def jobNameOverride = params.JOB_NAME_OVERRIDE ?: env.JOB_BASE_NAME ?: ''
eksCdcIntegPipeline(jobName: jobNameOverride ?: null, defaultTestIds: '0032', defaultStageId: 'cdcbulk')
