// RFS against a deliberately starved target (Test0011RfsBackpressure): workers must throttle back and lose
// nothing. About 20 minutes on top of the deployment: ~10 of setup and data generation, ~10 of backfill.
def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: config.jobName ?: 'k8s-local-rfs-backpressure-test',
            sourceVersion: 'ES_7.10',
            targetVersion: 'OS_2.19',
            testIds: '0011'
    )
}
