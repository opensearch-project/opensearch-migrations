/**
 * Single source of truth for source/target cluster versions used across k8s test pipelines.
 */
def call() {
    return [
        sourceVersions: ['SOLR_6.6', 'SOLR_7.7', 'SOLR_9.8'],
        targetVersions: ['OS_2.19']
    ]
}
