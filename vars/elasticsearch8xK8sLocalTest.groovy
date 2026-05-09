def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: config.jobName ?: 'elasticsearch-8x-k8s-local-test',
            sourceVersion: 'ES_8.19',
            targetVersion: 'OS_3.1',
            testIds: '0001,0002,0040'
    )
}
