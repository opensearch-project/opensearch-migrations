def call(Map config = [:]) {
    k8sLocalDeployment(
            jobName: config.jobName ?: 'k8s-local-solr-other-test',
            // SOLR_6.6/7.7 temporarily disabled — need filesystem-backup path for Solr <8
            // (no S3BackupRepository module). Re-enable when SolrBackupStrategy handles the <8 case.
            sourceVersion: 'SOLR_9.8',
            targetVersion: 'OS_2.19',
            testIds: 'Solr0001'
    )
}
