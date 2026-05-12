/**
 * Single source of truth for source/target cluster versions used across k8s test pipelines.
 */
def call() {
    return [
        sourceVersions: ['ES_1.5', 'ES_2.4', 'ES_5.6', 'ES_6.8', 'ES_7.10', 'ES_8.19', 'SOLR_8.11'],
        targetVersions: ['OS_1.3', 'OS_2.19', 'OS_3.1']
    ]
}
