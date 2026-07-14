def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: config.jobName ?: 'k8s-local-elasticsearch7x-test',
            sourceVersion: 'ES_7.10',
            targetVersion: 'OS_3.1',
            testIds: '0001,0002,0003,0006,0010,0020'
    )
}
