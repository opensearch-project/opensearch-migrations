def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: config.jobName ?: 'solr-6x-k8s-local-test',
            sourceVersion: 'SOLR_6.6',
            targetVersion: 'OS_2.19',
            testIds: 'Solr0001'
    )
}