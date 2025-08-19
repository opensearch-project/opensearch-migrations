def gitBranch = params.GIT_BRANCH ?: 'pytest-doc-multiplier'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/jugal-chauhan/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Shared library function (location from root: vars/DocMultiplicationE2ETest.groovy)
// This should contain the parameters I have setup manually on my jenkins pipeline 
// So that when doing a manual 'build with parameters' every time, my user chosen values should be picked up
// The first two are GIT_REPO_URL and GIT_BRANCH, which we have already declared a static value for
DocMultiplicationE2ETest(
        GIT_REPO_URL: gitUrl,
        GIT_BRANCH: gitBranch,
        STAGE: params.STAGE, //default = dev
        SNAPSHOT_REGION: params.SNAPSHOT_REGION, //default = us-west-2
        CLUSTER_VERSION: params.CLUSTER_VERSION, //default = es7x
        VERSION: params.VERSION, //default = ES_7.10
        LARGE_SNAPSHOT_BUCKET_PREFIX: params.LARGE_SNAPSHOT_BUCKET_PREFIX, //default = migrations-jenkins-snapshot-
        LARGE_S3_DIRECTORY_PREFIX: params.LARGE_S3_DIRECTORY_PREFIX, //default = large-snapshot-
        NUM_SHARDS: params.NUM_SHARDS, //default = 10
        INDEX_NAME: params.INDEX_NAME, //default = basic_index
        DOCS_PER_BATCH: params.DOCS_PER_BATCH, //default = 50
        MULTIPLICATION_FACTOR: params.MULTIPLICATION_FACTOR, //default = 100
        RFS_WORKERS: params.RFS_WORKERS, //default = 5
        SKIP_CLEANUP: params.SKIP_CLEANUP, //default = false
        DEBUG_MODE: params.DEBUG_MODE //default = false
)
