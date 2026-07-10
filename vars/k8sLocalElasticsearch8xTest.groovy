def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: config.jobName ?: 'k8s-local-elasticsearch8x-test',
            sourceVersion: 'ES_8.19',
            targetVersion: 'OS_3.1',
            testIds: '0001,0002,0003,0020,0035,0040'
    )
}
