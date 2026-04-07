def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: config.jobName ?: 'elasticsearch-5x-k8s-local-test',
            sourceVersion: 'SOLR_8.11',
            targetVersion: 'OS_2.19',
            testIds: 'Solr0001'
    )
}
