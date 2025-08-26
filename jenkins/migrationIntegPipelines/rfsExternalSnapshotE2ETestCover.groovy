// Jenkins Pipeline Script for RFS External Snapshot E2E Test Coverage

def gitBranch = params.GIT_BRANCH ?: 'jenkins-rfs-metrics'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/jugal-chauhan/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: "${gitUrl}"
])

rfsExternalSnapshotE2ETest(
    GIT_REPO_URL: gitUrl,
    GIT_BRANCH: gitBranch,
    STAGE: params.STAGE,
    BACKFILL_SCALE: params.BACKFILL_SCALE,
    CUSTOM_COMMIT: params.CUSTOM_COMMIT,
    SNAPSHOT_S3_URI: params.SNAPSHOT_S3_URI,
    SNAPSHOT_NAME: params.SNAPSHOT_NAME,
    SNAPSHOT_REGION: params.SNAPSHOT_REGION,
    SNAPSHOT_REPO_NAME: params.SNAPSHOT_REPO_NAME,
    TARGET_VERSION: params.TARGET_VERSION,
    TARGET_DATA_NODE_COUNT: params.TARGET_DATA_NODE_COUNT,
    TARGET_DATA_NODE_TYPE: params.TARGET_DATA_NODE_TYPE,
    TARGET_MANAGER_NODE_COUNT: params.TARGET_MANAGER_NODE_COUNT,
    TARGET_MANAGER_NODE_TYPE: params.TARGET_MANAGER_NODE_TYPE,
    TARGET_EBS_ENABLED: params.TARGET_EBS_ENABLED
)
