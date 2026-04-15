def gitBranch = params.GIT_BRANCH ?: 'main'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/opensearch-project/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

def jobNameOverride = params.JOB_NAME_OVERRIDE ?: env.JOB_BASE_NAME ?: ''
eksCdcUnifiedPipeline(
    jobName: jobNameOverride ?: null,
    defaultStageId: 'cdcall',
    deployOsTarget: true,
    deployAossTarget: true,
    esOsTestIds: '0030,0031,0032,0033,0040',
    aossTestIds: '0034,0041'
)
