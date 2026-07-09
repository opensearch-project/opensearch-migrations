def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: config.jobName ?: 'k8s-local-solr8x-test',
            sourceVersion: 'SOLR_8.11',
            targetVersion: 'OS_2.19',
            testIds: 'Solr0001'
    )
}
