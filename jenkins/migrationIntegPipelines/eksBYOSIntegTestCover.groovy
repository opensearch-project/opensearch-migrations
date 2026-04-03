// BYOS (Bring Your Own Snapshot) Integration Test Pipeline
// Tests migration from pre-existing S3 snapshots to OpenSearch target clusters.
// See vars/eksBYOSIntegPipeline.groovy for implementation details.

def gitBranch = params.GIT_BRANCH ?: 'main'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/opensearch-project/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Use JOB_NAME_OVERRIDE if set, otherwise derive from the Jenkins job name.
// This ensures the GenericTrigger regex stays in sync with the actual job name
// (e.g., main-* vs pr-*) across runs.
def jobNameOverride = params.JOB_NAME_OVERRIDE ?: env.JOB_BASE_NAME ?: ''
eksBYOSIntegPipeline(jobName: jobNameOverride ?: null)
