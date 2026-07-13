def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: config.jobName ?: 'k8s-local-solr-other-test',
            sourceVersion: 'SOLR_6.6,SOLR_7.7,SOLR_9.8',
            targetVersion: 'OS_2.19',
            testIds: 'Solr0001'
    )
}
