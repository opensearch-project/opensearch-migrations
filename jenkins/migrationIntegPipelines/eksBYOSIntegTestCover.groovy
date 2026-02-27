// BYOS (Bring Your Own Snapshot) Integration Test Pipeline
// Tests migration from pre-existing S3 snapshots to OpenSearch target clusters.
// See vars/eksBYOSIntegPipeline.groovy for implementation details.

// Use the SCM source (the fork/branch that triggered this build) for library
// loading and checkout, so images are always built from the triggering fork.
def gitUrl = scm.getUserRemoteConfigs()[0].getUrl()
def gitBranch = scm.getBranches()[0].getName().replaceAll('^\\*/', '')

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

eksBYOSIntegPipeline()
