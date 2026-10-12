def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: config.jobName ?: 'k8s-local-elasticsearch5x-test',
            sourceVersion: 'ES_5.6',
            targetVersion: 'OS_3.1',
            testIds: '0001,0002,0003,0004,0005'
    )
}
