def gitBranch = params.GIT_BRANCH ?: 'test-k8s-large-snapshot'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/jugal-chauhan/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Shared library function (location from root: vars/largeSnapshotGeneratorTest.groovy)
largeSnapshotGeneratorTest(
        NUM_SHARDS: params.NUM_SHARDS,
        MULTIPLICATION_FACTOR: params.MULTIPLICATION_FACTOR,
        BATCH_COUNT: params.BATCH_COUNT,
        DOCS_PER_BATCH: params.DOCS_PER_BATCH,
        BACKFILL_TIMEOUT_HOURS: params.BACKFILL_TIMEOUT_HOURS,
        LARGE_SNAPSHOT_RATE_MB_PER_NODE: params.LARGE_SNAPSHOT_RATE_MB_PER_NODE,
)
