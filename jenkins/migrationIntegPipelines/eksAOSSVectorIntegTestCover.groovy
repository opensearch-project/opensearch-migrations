// AOSS Vector Collection Integration Test
// See vars/eksAOSSIntegPipeline.groovy for implementation details.

def gitBranch = params.GIT_BRANCH ?: 'jenkins-target-aoss-collection'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/jugal-chauhan/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

def jobNameOverride = params.JOB_NAME_OVERRIDE ?: ''
eksAOSSIntegPipeline(collectionType: 'VECTORSEARCH', defaultStageId: 'aossvec', jobName: jobNameOverride ?: null)
