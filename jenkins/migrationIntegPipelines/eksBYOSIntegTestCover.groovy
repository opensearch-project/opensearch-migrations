// BYOS (Bring Your Own Snapshot) Integration Test Pipeline
// Tests migration from pre-existing S3 snapshots to OpenSearch target clusters.
// See vars/eksBYOSIntegPipeline.groovy for implementation details.

def gitBranch = params.GIT_BRANCH ?: 'jenkins-pipeline-eks-large-migration'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/jugal-chauhan/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Allow job name override for webhook routing (e.g., pr-* vs main-*)
def jobNameOverride = params.JOB_NAME_OVERRIDE ?: ''
eksBYOSIntegPipeline(jobName: jobNameOverride ?: null)
