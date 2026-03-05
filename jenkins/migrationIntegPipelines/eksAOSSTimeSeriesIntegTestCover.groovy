// AOSS Time-Series Collection Integration Test
// See vars/eksAOSSIntegPipeline.groovy for implementation details.

def gitBranch = params.GIT_BRANCH ?: 'main'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/opensearch-project/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

def jobNameOverride = params.JOB_NAME_OVERRIDE ?: ''
eksAOSSIntegPipeline(collectionType: 'TIMESERIES', jobName: jobNameOverride ?: null)
