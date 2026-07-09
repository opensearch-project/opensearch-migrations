def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: config.jobName ?: 'k8s-local-elasticsearch1x-test',
            sourceVersion: 'ES_1.5',
            targetVersion: 'OS_3.1',
            testIds: '0001,0002,0003,0010'
    )
}
