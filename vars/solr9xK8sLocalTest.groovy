def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: config.jobName ?: 'solr-9x-k8s-local-test',
            sourceVersion: 'SOLR_9.8',
            targetVersion: 'OS_2.19',
            testIds: 'Solr0001'
    )
}