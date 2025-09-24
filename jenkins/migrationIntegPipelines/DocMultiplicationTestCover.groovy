def gitBranch = params.GIT_BRANCH ?: 'pytest-doc-multiplier'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/jugal-chauhan/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: "${gitUrl}"
])

DocMultiplicationE2ETest(
    GIT_REPO_URL: gitUrl,
    GIT_BRANCH: gitBranch,
    STAGE: params.STAGE,
    SNAPSHOT_REGION: params.SNAPSHOT_REGION,
    CLUSTER_VERSION: params.CLUSTER_VERSION,
    FINAL_SNAPSHOT_BUCKET_PREFIX: params.FINAL_SNAPSHOT_BUCKET_PREFIX,
    FINAL_SNAPSHOT_FOLDER_PREFIX: params.FINAL_SNAPSHOT_FOLDER_PREFIX,
    NUM_SHARDS: params.NUM_SHARDS,
    INDEX_NAME: params.INDEX_NAME,
    BATCH_COUNT: params.BATCH_COUNT,
    DOCS_PER_BATCH: params.DOCS_PER_BATCH,
    MULTIPLICATION_FACTOR: params.MULTIPLICATION_FACTOR,
    RFS_WORKERS: params.RFS_WORKERS,
    SKIP_CLEANUP: params.SKIP_CLEANUP,
    DEBUG_MODE: params.DEBUG_MODE
)
